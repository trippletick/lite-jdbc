package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleTypesTest extends DatabaseTestBase {

    @Test
    void toText() throws SQLException {
        assertEquals(
            Arrays.asList("abc", null),
            DAO.query(connection, "select * from (values ('abc'), (null)) v", SimpleTypes.toText())
        );
    }

    @Test
    void toInteger() throws SQLException {
        assertEquals(
            Arrays.asList(42, null),
            DAO.query(connection, "select * from (values (42), (null::int)) v", SimpleTypes.toInteger())
        );
    }

    @Test
    void toLong() throws SQLException {
        assertEquals(
            Arrays.asList(9_000_000_000L, null),
            DAO.query(connection, "select * from (values (9000000000::bigint), (null)) v", SimpleTypes.toLong())
        );
    }

    @Test
    void toBoolean() throws SQLException {
        assertEquals(
            Arrays.asList(true, false, null),
            DAO.query(connection, "select * from (values (true), (false), (null)) v", SimpleTypes.toBoolean())
        );
    }

    @Test
    void toDouble() throws SQLException {
        assertEquals(
            Arrays.asList(1.5, null),
            DAO.query(connection, "select * from (values (1.5::float8), (null)) v", SimpleTypes.toDouble())
        );
    }

    @Test
    void toBigDecimal() throws SQLException {
        assertEquals(
            Arrays.asList(new BigDecimal("12345.67"), null),
            DAO.query(connection, "select * from (values (12345.67::numeric(12,2)), (null)) v",
                SimpleTypes.toBigDecimal())
        );
    }

    @Test
    void toLocalDate() throws SQLException {
        assertEquals(
            Arrays.asList(LocalDate.of(2024, 2, 29), null),
            DAO.query(connection, "select * from (values (date '2024-02-29'), (null)) v",
                SimpleTypes.toLocalDate())
        );
    }

    @Test
    void toLocalDateTime() throws SQLException {
        assertEquals(
            Arrays.asList(LocalDateTime.of(2024, 2, 29, 13, 45, 1), null),
            DAO.query(connection, "select * from (values (timestamp '2024-02-29 13:45:01'), (null)) v",
                SimpleTypes.toLocalDateTime())
        );
    }

    @Test
    void toInstant() throws SQLException {
        assertEquals(
            Arrays.asList(Instant.parse("2024-02-29T13:45:01.123456Z"), null),
            DAO.query(connection,
                "select * from (values (timestamptz '2024-02-29 13:45:01.123456+00'), (null)) v",
                SimpleTypes.toInstant())
        );
    }

    @Test
    void readsFirstColumnOnly() throws SQLException {
        long id = insertEmployee("Alice", "alice@example.com", null);

        assertEquals(
            List.of("Alice"),
            DAO.query(connection, "select name, email, id from employee where id = ?", SimpleTypes.toText(), id)
        );
    }
}
