package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryBatchTest extends DatabaseTestBase {

    private void insertEmployees(int count) throws SQLException {
        DAO.execute(
            connection,
            "insert into employee(name, age) select 'emp-' || g, g from generate_series(1, ?) g",
            count
        );
    }

    @Test
    void deliversRowsInBatchesWithSmallerLastBatch() throws SQLException {
        insertEmployees(2500);
        List<Integer> sizes = new ArrayList<>();
        List<Long> ids = new ArrayList<>();

        DAO.queryBatch(
            connection,
            "select id from employee order by id",
            1000,
            SimpleTypes.toLong(),
            batch -> {
                sizes.add(batch.size());
                ids.addAll(batch);
            }
        );

        assertEquals(List.of(1000, 1000, 500), sizes);
        assertEquals(LongStream.rangeClosed(1, 2500).boxed().toList(), ids);
    }

    @Test
    void exactMultipleHasNoTrailingEmptyBatch() throws SQLException {
        insertEmployees(6);
        List<Integer> sizes = new ArrayList<>();

        DAO.queryBatch(connection, "select id from employee", 3, SimpleTypes.toLong(),
            batch -> sizes.add(batch.size()));

        assertEquals(List.of(3, 3), sizes);
    }

    @Test
    void emptyResultNeverCallsConsumer() throws SQLException {
        List<List<Long>> batches = new ArrayList<>();

        DAO.queryBatch(connection, "select id from employee", 10, SimpleTypes.toLong(), batches::add);

        assertTrue(batches.isEmpty());
    }

    @Test
    void eachBatchIsANewList() throws SQLException {
        insertEmployees(5);
        List<List<String>> batches = new ArrayList<>();

        DAO.queryBatch(connection, "select name from employee order by id", 2, SimpleTypes.toText(), batches::add);

        assertEquals(
            List.of(List.of("emp-1", "emp-2"), List.of("emp-3", "emp-4"), List.of("emp-5")),
            batches
        );
    }

    @Test
    void bindsParametersAndMapsJoinRowsWithNulls() throws SQLException {
        long engineering = insertDepartment("Engineering");
        long alice = insertEmployee("Alice", "alice@example.com", engineering);
        long bob = insertEmployee("Bob", null, engineering);
        insertEmployee("Carol", null, null);
        long apollo = insertProject("Apollo");
        assignProject(alice, apollo, "LEAD");
        assignProject(bob, apollo, "DEV");

        record Member(String name, String email, String role) {
        }
        List<Member> members = new ArrayList<>();

        DAO.queryBatch(
            connection,
            """
            select e.name, e.email, ep.role
            from employee e
            join employee_project ep on ep.employee_id = e.id
            join project p on p.id = ep.project_id
            where p.name = ?
            order by e.name
            """,
            1,
            rs -> new Member(rs.getString("name"), rs.getString("email"), rs.getString("role")),
            members::addAll,
            "Apollo"
        );

        assertEquals(
            List.of(new Member("Alice", "alice@example.com", "LEAD"), new Member("Bob", null, "DEV")),
            members
        );
    }

    @Test
    void usesServerSideCursorInAutoCommitMode() throws SQLException {
        insertEmployees(50);
        List<Long> openCursors = new ArrayList<>();

        DAO.queryBatch(connection, "select id from employee", 10, SimpleTypes.toLong(), batch -> {
            assertFalse(connection.getAutoCommit());
            openCursors.add(namedCursorCount());
        });

        // every batch was read while the driver's cursor was still open
        assertEquals(5, openCursors.size());
        assertTrue(openCursors.stream().allMatch(n -> n == 1), openCursors.toString());
        assertTrue(connection.getAutoCommit());
        assertEquals(0, namedCursorCount());
    }

    @Test
    void usesCallersTransactionWithoutCommitting() throws SQLException {
        insertEmployees(3);
        connection.setAutoCommit(false);
        DAO.execute(connection, "insert into department(name) values (?)", "Pending");

        List<Long> openCursors = new ArrayList<>();
        DAO.queryBatch(connection, "select id from employee", 1, SimpleTypes.toLong(),
            batch -> openCursors.add(namedCursorCount()));

        assertEquals(List.of(1L, 1L, 1L), openCursors);
        assertFalse(connection.getAutoCommit());

        connection.rollback();
        assertEquals(0, count("department"));
    }

    @Test
    void joinsDaoTransaction() throws SQLException {
        insertEmployees(3);

        assertThrows(SQLException.class, () ->
            DAO.transaction(connection, () -> {
                DAO.queryBatch(connection, "select name from employee", 2, SimpleTypes.toText(), batch ->
                    DAO.executeBatch(connection, "insert into project(name) values (?)",
                        batch.stream().map(name -> new Object[] { name }).toList())
                );
                assertEquals(3, count("project"));
                throw new IllegalStateException("roll back everything");
            })
        );

        assertEquals(0, count("project"));
    }

    @Test
    void consumerCanWriteOnSameConnection() throws SQLException {
        insertEmployees(25);

        DAO.queryBatch(connection, "select name from employee order by id", 10, SimpleTypes.toText(), batch ->
            DAO.executeBatch(connection, "insert into project(name) values (?)",
                batch.stream().map(name -> new Object[] { name.replace("emp", "proj") }).toList())
        );

        // committed by queryBatch's own transaction
        assertEquals(25, count("project"));
    }

    @Test
    void consumerExceptionIsWrappedAndCleansUp() throws SQLException {
        insertEmployees(10);
        IllegalStateException cause = new IllegalStateException("stop");
        List<Integer> seen = new ArrayList<>();

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.queryBatch(connection, "select id from employee", 3, SimpleTypes.toLong(), batch -> {
                seen.add(batch.size());
                throw cause;
            })
        );

        assertEquals("Batch consumer failed", error.getMessage());
        assertSame(cause, error.getCause());
        assertEquals(List.of(3), seen);
        assertTrue(connection.getAutoCommit());
        assertEquals(0, namedCursorCount());
    }

    @Test
    void consumerSqlExceptionIsRethrownAsIs() {
        SQLException cause = new SQLException("from consumer");

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.queryBatch(connection, "values (1)", 1, SimpleTypes.toInteger(), batch -> {
                throw cause;
            })
        );

        assertSame(cause, error);
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        for (int size : Arrays.asList(0, -1)) {
            assertThrows(IllegalArgumentException.class, () ->
                DAO.queryBatch(connection, "values (1)", size, SimpleTypes.toInteger(), batch -> { })
            );
        }
    }

    @Test
    void propagatesSqlErrors() {
        assertThrows(SQLException.class, () ->
            DAO.queryBatch(connection, "select * from no_such_table", 10, SimpleTypes.toText(), batch -> { })
        );
    }

    /** Portals pgjdbc opened for incremental fetching; the unnamed portal of this query is excluded. */
    private long namedCursorCount() throws SQLException {
        return DAO.query(connection, "select count(*) from pg_cursors where name <> ''", SimpleTypes.toLong())
            .getFirst();
    }
}
