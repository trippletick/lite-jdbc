package tech.jaylu.litejdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the current row in a {@link ResultSet} to a Java object.
 */
@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
}
