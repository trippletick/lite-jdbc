package tech.jaylu.litejdbc;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Starts one embedded PostgreSQL for the whole test run and gives every test
 * a freshly created schema and its own {@link Connection}.
 */
public abstract class DatabaseTestBase {

    private static EmbeddedPostgres postgres;

    protected Connection connection;

    @BeforeAll
    static void startDatabase() throws IOException {
        synchronized (DatabaseTestBase.class) {
            if (postgres == null) {
                postgres = EmbeddedPostgres.start();
                Runtime.getRuntime().addShutdownHook(new Thread(DatabaseTestBase::stopDatabase));
            }
        }
    }

    private static void stopDatabase() {
        try {
            postgres.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void resetSchema() throws SQLException {
        connection = postgres.getPostgresDatabase().getConnection();

        try (Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists public cascade");
            statement.execute("create schema public");
            statement.execute(readResource("/schema.sql"));
        }
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    private static String readResource(String name) {
        try (InputStream in = DatabaseTestBase.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- fixtures ---

    protected long insertDepartment(String name) throws SQLException {
        return DAO.executeReturning(
            connection,
            "insert into department(name) values (?) returning id",
            SimpleTypes.toLong(),
            name
        ).getFirst();
    }

    protected long insertProject(String name) throws SQLException {
        return DAO.executeReturning(
            connection,
            "insert into project(name) values (?) returning id",
            SimpleTypes.toLong(),
            name
        ).getFirst();
    }

    protected long insertEmployee(String name, String email, Long departmentId) throws SQLException {
        return DAO.executeReturning(
            connection,
            "insert into employee(name, email, department_id) values (?, ?, ?) returning id",
            SimpleTypes.toLong(),
            name,
            email,
            departmentId
        ).getFirst();
    }

    protected long insertEmployee(Employee e) throws SQLException {
        return DAO.executeReturning(
            connection,
            """
            insert into employee(name, email, age, salary, rating, active, status,
                                 birth_date, hired_at, created_at, department_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            returning id
            """,
            SimpleTypes.toLong(),
            e.name(),
            e.email(),
            e.age(),
            e.salary(),
            e.rating(),
            e.active(),
            e.status(),
            e.birthDate(),
            e.hiredAt(),
            e.createdAt(),
            e.departmentId()
        ).getFirst();
    }

    protected void assignProject(long employeeId, long projectId, String role) throws SQLException {
        DAO.execute(
            connection,
            "insert into employee_project(employee_id, project_id, role) values (?, ?, ?)",
            employeeId,
            projectId,
            role
        );
    }

    protected long count(String table) throws SQLException {
        return DAO.query(connection, "select count(*) from " + table, SimpleTypes.toLong()).getFirst();
    }

    enum Status { ACTIVE, ON_LEAVE }

    record Employee(
        Long id,
        String name,
        String email,
        Integer age,
        java.math.BigDecimal salary,
        Double rating,
        boolean active,
        Status status,
        LocalDate birthDate,
        LocalDateTime hiredAt,
        Instant createdAt,
        Long departmentId
    ) {
    }

    static final RowMapper<Employee> EMPLOYEE_MAPPER = rs -> new Employee(
        rs.getLong("id"),
        rs.getString("name"),
        rs.getString("email"),
        (Integer) rs.getObject("age"),
        rs.getBigDecimal("salary"),
        (Double) rs.getObject("rating"),
        rs.getBoolean("active"),
        rs.getString("status") == null ? null : Status.valueOf(rs.getString("status")),
        rs.getObject("birth_date", LocalDate.class),
        rs.getObject("hired_at", LocalDateTime.class),
        rs.getTimestamp("created_at").toInstant(),
        (Long) rs.getObject("department_id")
    );
}
