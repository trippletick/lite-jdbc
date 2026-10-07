package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTest extends DatabaseTestBase {

    @Test
    void returnsEmptyListWhenNoRows() throws SQLException {
        List<Employee> employees = DAO.query(connection, "select * from employee", EMPLOYEE_MAPPER);

        assertTrue(employees.isEmpty());
    }

    @Test
    void mapsAllColumnTypes() throws SQLException {
        long departmentId = insertDepartment("Engineering");
        Employee expected = new Employee(
            null,
            "Alice",
            "alice@example.com",
            30,
            new BigDecimal("12345.67"),
            4.5,
            true,
            Status.ON_LEAVE,
            LocalDate.of(1995, 3, 14),
            LocalDateTime.of(2024, 1, 2, 9, 30, 15),
            Instant.parse("2024-01-02T01:30:15.123456Z"),
            departmentId
        );
        long id = insertEmployee(expected);

        List<Employee> employees = DAO.query(
            connection,
            "select * from employee where id = ?",
            EMPLOYEE_MAPPER,
            id
        );

        assertEquals(List.of(withId(expected, id)), employees);
    }

    @Test
    void mapsSqlNullsToJavaNulls() throws SQLException {
        long id = insertEmployee("Bob", null, null);

        Employee employee = DAO.query(
            connection,
            "select * from employee where id = ?",
            EMPLOYEE_MAPPER,
            id
        ).getFirst();

        assertEquals("Bob", employee.name());
        assertEquals(null, employee.email());
        assertEquals(null, employee.age());
        assertEquals(null, employee.salary());
        assertEquals(null, employee.birthDate());
        assertEquals(null, employee.departmentId());
    }

    @Test
    void returnsRowsInQueryOrder() throws SQLException {
        insertEmployee("Carol", null, null);
        insertEmployee("Alice", null, null);
        insertEmployee("Bob", null, null);

        List<String> names = DAO.query(
            connection,
            "select name from employee order by name",
            SimpleTypes.toText()
        );

        assertEquals(List.of("Alice", "Bob", "Carol"), names);
    }

    @Test
    void bindsParametersOfDifferentTypes() throws SQLException {
        long departmentId = insertDepartment("Engineering");
        insertEmployee(new Employee(null, "Alice", "a@x.com", 30, new BigDecimal("100.00"), 4.0, true,
            Status.ACTIVE, LocalDate.of(1990, 1, 1), null, Instant.parse("2024-01-01T00:00:00Z"), departmentId));
        insertEmployee(new Employee(null, "Bob", "b@x.com", 40, new BigDecimal("200.00"), 3.0, false,
            Status.ON_LEAVE, LocalDate.of(1980, 1, 1), null, Instant.parse("2024-06-01T00:00:00Z"), departmentId));

        List<String> names = DAO.query(
            connection,
            """
            select name
            from employee
            where age >= ?
              and salary < ?
              and rating > ?
              and active = ?
              and status = ?
              and birth_date < ?
              and created_at < ?
              and department_id = ?
            """,
            SimpleTypes.toText(),
            25,                                   // Integer
            new BigDecimal("150"),                // BigDecimal
            3.5,                                  // Double
            true,                                 // Boolean
            Status.ACTIVE,                        // Enum -> name()
            LocalDate.of(2000, 1, 1),             // LocalDate (driver default)
            Instant.parse("2024-03-01T00:00:00Z"), // Instant -> Timestamp
            departmentId                          // Long
        );

        assertEquals(List.of("Alice"), names);
    }

    @Test
    void bindsNullParameter() throws SQLException {
        insertEmployee("Alice", null, null);
        insertEmployee("Bob", "bob@example.com", null);

        List<String> names = DAO.query(
            connection,
            "select name from employee where email is not distinct from ?",
            SimpleTypes.toText(),
            (Object) null
        );

        assertEquals(List.of("Alice"), names);
    }

    @Test
    void mapsJoinAcrossJoinTable() throws SQLException {
        long engineering = insertDepartment("Engineering");
        long sales = insertDepartment("Sales");
        long alice = insertEmployee("Alice", "alice@example.com", engineering);
        long bob = insertEmployee("Bob", "bob@example.com", sales);
        insertEmployee("Carol", "carol@example.com", engineering);
        long apollo = insertProject("Apollo");
        long gemini = insertProject("Gemini");
        assignProject(alice, apollo, "LEAD");
        assignProject(alice, gemini, "DEV");
        assignProject(bob, apollo, "SALES");

        record Assignment(String employee, String department, String project, String role) {
        }

        List<Assignment> assignments = DAO.query(
            connection,
            """
            select e.name as employee, d.name as department, p.name as project, ep.role
            from employee e
            join department d on d.id = e.department_id
            join employee_project ep on ep.employee_id = e.id
            join project p on p.id = ep.project_id
            where p.name in (?, ?)
            order by e.name, p.name
            """,
            rs -> new Assignment(
                rs.getString("employee"),
                rs.getString("department"),
                rs.getString("project"),
                rs.getString("role")
            ),
            "Apollo",
            "Gemini"
        );

        assertEquals(
            List.of(
                new Assignment("Alice", "Engineering", "Apollo", "LEAD"),
                new Assignment("Alice", "Engineering", "Gemini", "DEV"),
                new Assignment("Bob", "Sales", "Apollo", "SALES")
            ),
            assignments
        );
    }

    @Test
    void aggregatesOverJoin() throws SQLException {
        long engineering = insertDepartment("Engineering");
        insertDepartment("Empty");
        insertEmployee("Alice", null, engineering);
        insertEmployee("Bob", null, engineering);

        record Headcount(String department, long employees) {
        }

        List<Headcount> headcounts = DAO.query(
            connection,
            """
            select d.name, count(e.id) as employees
            from department d
            left join employee e on e.department_id = d.id
            group by d.name
            order by d.name
            """,
            rs -> new Headcount(rs.getString("name"), rs.getLong("employees"))
        );

        assertEquals(List.of(new Headcount("Empty", 0), new Headcount("Engineering", 2)), headcounts);
    }

    @Test
    void zeroOrOneRowViaStream() throws SQLException {
        long id = insertEmployee("Alice", null, null);

        Optional<String> found = DAO.query(
            connection, "select name from employee where id = ?", SimpleTypes.toText(), id
        ).stream().findFirst();
        Optional<String> missing = DAO.query(
            connection, "select name from employee where id = ?", SimpleTypes.toText(), id + 1
        ).stream().findFirst();

        assertEquals(Optional.of("Alice"), found);
        assertEquals(Optional.empty(), missing);
    }

    @Test
    void readsFromCteAndValues() throws SQLException {
        insertEmployee("Alice", null, null);

        assertEquals(
            List.of("ALICE"),
            DAO.query(connection, "with e as (select name from employee) select upper(name) from e",
                SimpleTypes.toText())
        );
        assertEquals(
            List.of(1, 2),
            DAO.query(connection, "values (1), (2)", SimpleTypes.toInteger())
        );
    }

    @Test
    void rejectsWriteThatReturnsNoRows() {
        assertThrows(SQLException.class, () ->
            DAO.query(connection, "insert into department(name) values (?)", SimpleTypes.toLong(), "Sales")
        );
    }

    @Test
    void propagatesSqlErrors() {
        assertThrows(SQLException.class, () ->
            DAO.query(connection, "select * from no_such_table", SimpleTypes.toText())
        );
    }

    @Test
    void propagatesMapperErrors() throws SQLException {
        insertEmployee("Alice", null, null);

        SQLException error = assertThrows(SQLException.class, () ->
            DAO.query(connection, "select name from employee", rs -> rs.getString("no_such_column"))
        );
        assertTrue(error.getMessage().contains("no_such_column"));
    }

    private static Employee withId(Employee e, long id) {
        return new Employee(id, e.name(), e.email(), e.age(), e.salary(), e.rating(), e.active(),
            e.status(), e.birthDate(), e.hiredAt(), e.createdAt(), e.departmentId());
    }
}
