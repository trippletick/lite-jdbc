package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteTest extends DatabaseTestBase {

    @Test
    void insertReturnsAffectedRowCount() throws SQLException {
        int inserted = DAO.execute(
            connection,
            "insert into department(name) values (?), (?)",
            "Engineering",
            "Sales"
        );

        assertEquals(2, inserted);
        assertEquals(2, count("department"));
    }

    @Test
    void insertsAllParameterTypes() throws SQLException {
        long departmentId = insertDepartment("Engineering");
        Instant createdAt = Instant.parse("2024-05-06T07:08:09.123456Z");

        DAO.execute(
            connection,
            """
            insert into employee(name, email, age, salary, rating, active, status,
                                 birth_date, hired_at, created_at, department_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            "Alice",
            null,
            30,
            new BigDecimal("1000.50"),
            4.25,
            false,
            Status.ON_LEAVE,
            LocalDate.of(1994, 2, 3),
            LocalDateTime.of(2020, 1, 1, 8, 0),
            createdAt,
            departmentId
        );

        Employee employee = DAO.query(connection, "select * from employee", EMPLOYEE_MAPPER).getFirst();
        assertEquals("Alice", employee.name());
        assertNull(employee.email());
        assertEquals(30, employee.age());
        assertEquals(new BigDecimal("1000.50"), employee.salary());
        assertEquals(4.25, employee.rating());
        assertEquals(false, employee.active());
        assertEquals(Status.ON_LEAVE, employee.status());
        assertEquals(LocalDate.of(1994, 2, 3), employee.birthDate());
        assertEquals(LocalDateTime.of(2020, 1, 1, 8, 0), employee.hiredAt());
        assertEquals(createdAt, employee.createdAt());
        assertEquals(departmentId, employee.departmentId());
    }

    @Test
    void updateReturnsMatchedRowCount() throws SQLException {
        long engineering = insertDepartment("Engineering");
        insertEmployee("Alice", null, engineering);
        insertEmployee("Bob", null, engineering);
        insertEmployee("Carol", null, null);

        int updated = DAO.execute(
            connection,
            "update employee set status = ? where department_id = ?",
            Status.ON_LEAVE,
            engineering
        );

        assertEquals(2, updated);
        assertEquals(
            List.of("Alice", "Bob"),
            DAO.query(connection, "select name from employee where status = ? order by name",
                SimpleTypes.toText(), "ON_LEAVE")
        );
    }

    @Test
    void updateMatchingNothingReturnsZero() throws SQLException {
        int updated = DAO.execute(connection, "update employee set age = ? where id = ?", 1, 42L);

        assertEquals(0, updated);
    }

    @Test
    void deleteFromJoinTable() throws SQLException {
        long alice = insertEmployee("Alice", null, null);
        long apollo = insertProject("Apollo");
        long gemini = insertProject("Gemini");
        assignProject(alice, apollo, "LEAD");
        assignProject(alice, gemini, "DEV");

        int deleted = DAO.execute(
            connection,
            "delete from employee_project where employee_id = ? and project_id = ?",
            alice,
            apollo
        );

        assertEquals(1, deleted);
        assertEquals(1, count("employee_project"));
    }

    @Test
    void ddlReturnsZero() throws SQLException {
        int result = DAO.execute(connection, "create table scratch (id integer)");

        assertEquals(0, result);
        assertEquals(0, count("scratch"));
    }

    @Test
    void constraintViolationThrows() throws SQLException {
        long alice = insertEmployee("Alice", null, null);

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.execute(
                connection,
                "insert into employee_project(employee_id, project_id, role) values (?, ?, ?)",
                alice,
                999L,
                "DEV"
            )
        );
        assertTrue(error.getMessage().contains("foreign key"));
    }

    @Test
    void rejectsStatementThatReturnsRows() {
        assertThrows(SQLException.class, () -> DAO.execute(connection, "select 1"));
        assertThrows(SQLException.class, () ->
            DAO.execute(connection, "insert into department(name) values (?) returning id", "Sales")
        );
    }

    @Test
    void doesNotCommitOrRollbackImplicitly() throws SQLException {
        connection.setAutoCommit(false);

        DAO.execute(connection, "insert into department(name) values (?)", "Engineering");
        assertEquals(1, count("department"));

        connection.rollback();
        assertEquals(0, count("department"));
    }
}
