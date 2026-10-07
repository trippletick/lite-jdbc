package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTest extends DatabaseTestBase {

    @Test
    void commitsOnSuccessAndReturnsResult() throws SQLException {
        long apollo = insertProject("Apollo");

        long employeeId = DAO.transaction(connection, () -> {
            long id = DAO.executeReturning(
                connection,
                "insert into employee(name) values (?) returning id",
                SimpleTypes.toLong(),
                "Alice"
            ).getFirst();

            DAO.execute(
                connection,
                "insert into employee_project(employee_id, project_id, role) values (?, ?, ?)",
                id,
                apollo,
                "LEAD"
            );

            return id;
        });

        assertEquals(1, count("employee"));
        assertEquals(1, count("employee_project"));
        assertEquals(
            java.util.List.of(employeeId),
            DAO.query(connection, "select employee_id from employee_project", SimpleTypes.toLong())
        );
    }

    @Test
    void rollsBackAndRethrowsSqlException() throws SQLException {
        long alice = insertEmployee("Alice", null, null);

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                DAO.execute(connection, "update employee set name = ? where id = ?", "Changed", alice);
                // violates the foreign key on project_id
                DAO.execute(
                    connection,
                    "insert into employee_project(employee_id, project_id, role) values (?, ?, ?)",
                    alice,
                    999L,
                    "DEV"
                );
                return null;
            })
        );

        assertTrue(error.getMessage().contains("foreign key"));
        assertEquals(
            java.util.List.of("Alice"),
            DAO.query(connection, "select name from employee where id = ?", SimpleTypes.toText(), alice)
        );
    }

    @Test
    void rollsBackAndWrapsNonSqlException() throws SQLException {
        IllegalStateException cause = new IllegalStateException("boom");

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                DAO.execute(connection, "insert into department(name) values (?)", "Engineering");
                throw cause;
            })
        );

        assertEquals("Transaction failed", error.getMessage());
        assertSame(cause, error.getCause());
        assertEquals(0, count("department"));
    }

    @Test
    void wrapsCheckedException() {
        SQLException error = assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                throw new java.io.IOException("io");
            })
        );

        assertInstanceOf(java.io.IOException.class, error.getCause());
    }

    @Test
    void restoresAutoCommitTrue() throws SQLException {
        connection.setAutoCommit(true);

        DAO.transaction(connection, () -> {
            assertFalse(connection.getAutoCommit());
            return null;
        });
        assertTrue(connection.getAutoCommit());

        assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                throw new RuntimeException("fail");
            })
        );
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void restoresAutoCommitFalse() throws SQLException {
        connection.setAutoCommit(false);

        DAO.transaction(connection, () -> {
            DAO.execute(connection, "insert into department(name) values (?)", "Engineering");
            return null;
        });

        assertFalse(connection.getAutoCommit());
        // the transaction committed, so a rollback afterwards must not undo it
        connection.rollback();
        assertEquals(1, count("department"));
    }

    @Test
    void nestedCallJoinsOuterTransaction() throws SQLException {
        DAO.transaction(connection, () -> {
            DAO.execute(connection, "insert into department(name) values (?)", "Engineering");

            DAO.transaction(connection, () -> {
                DAO.execute(connection, "insert into department(name) values (?)", "Sales");
                return null;
            });

            // the inner call must not commit or restore auto-commit
            assertFalse(connection.getAutoCommit());
            connection.rollback();
            assertEquals(0, count("department"));
            return null;
        });

        assertTrue(connection.getAutoCommit());
        assertEquals(0, count("department"));
    }

    @Test
    void nestedFailureRollsBackOuterWork() throws SQLException {
        IllegalStateException cause = new IllegalStateException("inner failed");

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                DAO.execute(connection, "insert into department(name) values (?)", "Engineering");

                return DAO.transaction(connection, () -> {
                    DAO.execute(connection, "insert into department(name) values (?)", "Sales");
                    throw cause;
                });
            })
        );

        assertSame(cause, error.getCause());
        assertEquals(0, count("department"));
        assertTrue(connection.getAutoCommit());
    }

    @Test
    void sequentialCallsAreIndependent() throws SQLException {
        DAO.transaction(connection, () ->
            DAO.execute(connection, "insert into department(name) values (?)", "Engineering")
        );

        assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                DAO.execute(connection, "insert into department(name) values (?)", "Sales");
                throw new IllegalStateException("fail");
            })
        );

        assertEquals(
            java.util.List.of("Engineering"),
            DAO.query(connection, "select name from department", SimpleTypes.toText())
        );
    }

    @Test
    void cleanupFailuresAreSuppressedNotMasking() {
        IllegalStateException cause = new IllegalStateException("boom");

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                // makes both rollback() and setAutoCommit() fail afterwards
                connection.close();
                throw cause;
            })
        );

        assertSame(cause, error.getCause());
        assertEquals(2, error.getSuppressed().length);
    }
}
