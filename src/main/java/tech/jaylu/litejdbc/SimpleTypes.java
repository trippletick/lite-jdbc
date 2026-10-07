package tech.jaylu.litejdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Ready-made {@link RowMapper}s that read the first column of each row.
 * SQL {@code NULL} is mapped to Java {@code null}.
 */
public final class SimpleTypes {

    private SimpleTypes() {
    }

    public static RowMapper<String> toText() {
        return rs -> rs.getString(1);
    }

    public static RowMapper<Integer> toInteger() {
        return rs -> {
            int value = rs.getInt(1);
            return rs.wasNull() ? null : value;
        };
    }

    public static RowMapper<Long> toLong() {
        return rs -> {
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        };
    }

    public static RowMapper<Boolean> toBoolean() {
        return rs -> {
            boolean value = rs.getBoolean(1);
            return rs.wasNull() ? null : value;
        };
    }

    public static RowMapper<Double> toDouble() {
        return rs -> {
            double value = rs.getDouble(1);
            return rs.wasNull() ? null : value;
        };
    }

    public static RowMapper<BigDecimal> toBigDecimal() {
        return rs -> rs.getBigDecimal(1);
    }

    public static RowMapper<LocalDate> toLocalDate() {
        return rs -> rs.getObject(1, LocalDate.class);
    }

    public static RowMapper<LocalDateTime> toLocalDateTime() {
        return rs -> rs.getObject(1, LocalDateTime.class);
    }

    public static RowMapper<Instant> toInstant() {
        return rs -> {
            Timestamp timestamp = rs.getTimestamp(1);
            return timestamp == null ? null : timestamp.toInstant();
        };
    }
}
