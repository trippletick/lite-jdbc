package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecuteBatchTest extends DatabaseTestBase {

    @Test
    void insertsEveryEntry() throws SQLException {
        record NewEmployee(String name, int age, BigDecimal salary, LocalDate birthDate, Instant createdAt) {
        }

        List<NewEmployee> employees = List.of(
            new NewEmployee("Alice", 30, new BigDecimal("100.00"), LocalDate.of(1994, 1, 1),
                Instant.parse("2024-01-01T00:00:00Z")),
            new NewEmployee("Bob", 40, new BigDecimal("200.00"), LocalDate.of(1984, 1, 1),
                Instant.parse("2024-02-01T00:00:00Z")),
            new NewEmployee("Carol", 50, new BigDecimal("300.00"), LocalDate.of(1974, 1, 1),
                Instant.parse("2024-03-01T00:00:00Z"))
        );

        int[] counts = DAO.executeBatch(
            connection,
            "insert into employee(name, age, salary, birth_date, created_at) values (?, ?, ?, ?, ?)",
            employees.stream()
                .map(e -> new Object[] { e.name(), e.age(), e.salary(), e.birthDate(), e.createdAt() })
                .toList()
        );

        assertArrayEquals(new int[] { 1, 1, 1 }, counts);
        assertEquals(
            List.of(new BigDecimal("600.00")),
            DAO.query(connection, "select sum(salary) from employee", SimpleTypes.toBigDecimal())
        );
        assertEquals(
            List.of(Instant.parse("2024-03-01T00:00:00Z")),
            DAO.query(connection, "select max(created_at) from employee", SimpleTypes.toInstant())
        );
    }

    @Test
    void emptyListDoesNothing() throws SQLException {
        int[] counts = DAO.executeBatch(connection, "insert into department(name) values (?)", List.of());

        assertArrayEquals(new int[0], counts);
        assertEquals(0, count("department"));
    }

    @Test
    void reportsPerEntryUpdateCounts() throws SQLException {
        long engineering = insertDepartment("Engineering");
        long sales = insertDepartment("Sales");
        insertEmployee("Alice", null, engineering);
        insertEmployee("Bob", null, engineering);
        insertEmployee("Carol", null, sales);

        int[] counts = DAO.executeBatch(
            connection,
            "update employee set active = ? where department_id = ?",
            List.of(
                new Object[] { false, engineering },
                new Object[] { false, sales },
                new Object[] { false, 999L }
            )
        );

        assertArrayEquals(new int[] { 2, 1, 0 }, counts);
    }

    @Test
    void populatesJoinTable() throws SQLException {
        long alice = insertEmployee("Alice", null, null);
        long bob = insertEmployee("Bob", null, null);
        long apollo = insertProject("Apollo");
        long gemini = insertProject("Gemini");

        DAO.executeBatch(
            connection,
            "insert into employee_project(employee_id, project_id, role) values (?, ?, ?)",
            List.of(
                new Object[] { alice, apollo, "LEAD" },
                new Object[] { alice, gemini, "DEV" },
                new Object[] { bob, gemini, "QA" }
            )
        );

        assertEquals(
            List.of("Apollo", "Gemini"),
            DAO.query(
                connection,
                """
                select p.name
                from project p
                join employee_project ep on ep.project_id = p.id
                where ep.employee_id = ?
                order by p.name
                """,
                SimpleTypes.toText(),
                alice
            )
        );
        assertEquals(3, count("employee_project"));
    }

    @Test
    void bindsNullsInEntries() throws SQLException {
        DAO.executeBatch(
            connection,
            "insert into employee(name, email, age) values (?, ?, ?)",
            List.of(
                new Object[] { "Alice", null, null },
                new Object[] { "Bob", "bob@example.com", 40 }
            )
        );

        assertEquals(
            java.util.Arrays.asList(null, 40),
            DAO.query(connection, "select age from employee order by name", SimpleTypes.toInteger())
        );
    }

    @Test
    void failingEntryThrowsBatchUpdateException() {
        assertThrows(BatchUpdateException.class, () ->
            DAO.executeBatch(
                connection,
                "insert into department(name) values (?)",
                List.of(new Object[] { "Engineering" }, new Object[] { "Engineering" })
            )
        );
    }
}
