package tech.jaylu.litejdbc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the per-database decision without real MySQL/Oracle/... servers by
 * faking the connection metadata.
 */
class FetchStrategyTest {

    @Test
    void postgresqlNeedsTransactionAndFetchSize() throws SQLException {
        assertEquals(
            new FetchStrategy(500, true),
            FetchStrategy.of(connection("PostgreSQL", "PostgreSQL JDBC Driver", "jdbc:postgresql://h/db"), 500)
        );
    }

    @Test
    void mysqlConnectorJStreamsRowByRowByDefault() throws SQLException {
        assertEquals(
            new FetchStrategy(Integer.MIN_VALUE, false),
            FetchStrategy.of(connection("MySQL", "MySQL Connector/J", "jdbc:mysql://h/db"), 500)
        );
    }

    @Test
    void mysqlConnectorJUsesCursorFetchWhenEnabled() throws SQLException {
        assertEquals(
            new FetchStrategy(500, false),
            FetchStrategy.of(connection("MySQL", "MySQL Connector/J",
                "jdbc:mysql://h/db?useSSL=false&useCursorFetch=true"), 500)
        );
    }

    @Test
    void mariadbDriverUsesPlainFetchSizeEvenAgainstMysql() throws SQLException {
        assertEquals(
            new FetchStrategy(500, false),
            FetchStrategy.of(connection("MySQL", "MariaDB Connector/J", "jdbc:mariadb://h/db"), 500)
        );
    }

    @Test
    void otherDatabasesUsePlainFetchSize() throws SQLException {
        for (String product : new String[] { "Oracle", "Microsoft SQL Server", "DB2/LINUXX8664", "H2", "SQLite" }) {
            assertEquals(
                new FetchStrategy(500, false),
                FetchStrategy.of(connection(product, product + " driver", "jdbc:x"), 500),
                product
            );
        }
    }

    @Test
    void toleratesMissingMetadata() throws SQLException {
        assertEquals(new FetchStrategy(7, false), FetchStrategy.of(connection(null, null, null), 7));
    }

    private static Connection connection(String product, String driver, String url) {
        DatabaseMetaData meta = (DatabaseMetaData) Proxy.newProxyInstance(
            FetchStrategyTest.class.getClassLoader(),
            new Class<?>[] { DatabaseMetaData.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getDatabaseProductName" -> product;
                case "getDriverName" -> driver;
                case "getURL" -> url;
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
        return (Connection) Proxy.newProxyInstance(
            FetchStrategyTest.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            (proxy, method, args) -> {
                if (method.getName().equals("getMetaData")) {
                    return meta;
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
