package tech.jaylu.litejdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * How to make the JDBC driver fetch a large result incrementally instead of
 * loading it into memory at once.
 *
 * <ul>
 *   <li>PostgreSQL (pgjdbc) only uses a cursor when auto-commit is off and a
 *       fetch size is set.</li>
 *   <li>MySQL Connector/J ignores a positive fetch size unless the URL has
 *       {@code useCursorFetch=true}; otherwise {@link Integer#MIN_VALUE}
 *       switches it to row-by-row streaming.</li>
 *   <li>Everything else (Oracle, MariaDB, SQL Server, DB2, H2, SQLite, ...)
 *       honours, or does not need, a plain fetch size.</li>
 * </ul>
 */
record FetchStrategy(int fetchSize, boolean requiresTransaction) {

    static FetchStrategy of(Connection connection, int batchSize) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String product = lower(meta.getDatabaseProductName());
        String driver = lower(meta.getDriverName());

        if (product.contains("postgresql")) {
            return new FetchStrategy(batchSize, true);
        }

        // Oracle's Connector/J; MariaDB Connector/J streams with a normal fetch size
        if (driver.startsWith("mysql connector")) {
            boolean cursorFetch = lower(meta.getURL()).contains("usecursorfetch=true");
            return new FetchStrategy(cursorFetch ? batchSize : Integer.MIN_VALUE, false);
        }

        return new FetchStrategy(batchSize, false);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
