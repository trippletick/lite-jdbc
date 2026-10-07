package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteReturningTest extends DatabaseTestBase {

    @Test
    void returnsIdFromReturningClause() throws SQLException {
        List<Long> ids = DAO.executeReturning(
            connection,
            "insert into employee(name, email) values (?, ?) returning id",
            SimpleTypes.toLong(),
            "Alice",
            "alice@example.com"
        );

        assertEquals(1, ids.size());
        assertEquals(
            List.of("Alice"),
            DAO.query(connection, "select name from employee where id = ?", SimpleTypes.toText(), ids.getFirst())
        );
    }

    @Test
    void returnsGeneratedKeyWithoutReturningClause() throws SQLException {
        // PostgreSQL returns every column of the inserted row as generated keys
        record Generated(long id, Instant createdAt) {
        }

        Generated generated = DAO.executeReturning(
            connection,
            "insert into employee(name) values (?)",
            rs -> new Generated(rs.getLong("id"), rs.getTimestamp("created_at").toInstant()),
            "Alice"
        ).getFirst();

        assertEquals(
            List.of(generated.createdAt()),
            DAO.query(connection, "select created_at from employee where id = ?",
                SimpleTypes.toInstant(), generated.id())
        );
    }

    @Test
    void returnsOneRowPerInsertedRow() throws SQLException {
        record Created(long id, String name) {
        }

        List<Created> created = DAO.executeReturning(
            connection,
            "insert into project(name) values (?), (?), (?) returning id, name",
            rs -> new Created(rs.getLong("id"), rs.getString("name")),
            "Apollo",
            "Gemini",
            "Mercury"
        );

        assertEquals(List.of("Apollo", "Gemini", "Mercury"), created.stream().map(Created::name).toList());
        assertEquals(3, created.stream().map(Created::id).distinct().count());
    }

    @Test
    void returnsUpdatedRows() throws SQLException {
        long engineering = insertDepartment("Engineering");
        insertEmployee(new Employee(null, "Alice", null, 30, new BigDecimal("100.00"), null, true,
            null, null, null, Instant.parse("2024-01-01T00:00:00Z"), engineering));
        insertEmployee(new Employee(null, "Bob", null, 40, new BigDecimal("200.00"), null, true,
            null, null, null, Instant.parse("2024-01-01T00:00:00Z"), engineering));

        List<BigDecimal> salaries = DAO.executeReturning(
            connection,
            "update employee set salary = salary * ? where department_id = ? returning salary",
            SimpleTypes.toBigDecimal(),
            new BigDecimal("1.10"),
            engineering
        );

        assertEquals(List.of(new BigDecimal("110.00"), new BigDecimal("220.00")), salaries.stream().sorted().toList());
    }

    @Test
    void returnsDeletedRowsFromJoinTable() throws SQLException {
        long alice = insertEmployee("Alice", null, null);
        long apollo = insertProject("Apollo");
        long gemini = insertProject("Gemini");
        assignProject(alice, apollo, "LEAD");
        assignProject(alice, gemini, "DEV");

        List<String> roles = DAO.executeReturning(
            connection,
            "delete from employee_project where employee_id = ? returning role",
            SimpleTypes.toText(),
            alice
        );

        assertEquals(List.of("DEV", "LEAD"), roles.stream().sorted().toList());
        assertEquals(0, count("employee_project"));
    }

    @Test
    void returnsEmptyListWhenNothingAffected() throws SQLException {
        List<Long> ids = DAO.executeReturning(
            connection,
            "update employee set age = ? where id = ? returning id",
            SimpleTypes.toLong(),
            1,
            42L
        );

        assertTrue(ids.isEmpty());
    }

    @Test
    void propagatesConstraintViolation() throws SQLException {
        insertDepartment("Engineering");

        assertThrows(SQLException.class, () ->
            DAO.executeReturning(
                connection,
                "insert into department(name) values (?) returning id",
                SimpleTypes.toLong(),
                "Engineering"
            )
        );
    }
}
