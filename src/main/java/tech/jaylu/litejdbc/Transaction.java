package tech.jaylu.litejdbc;

/**
 * Work executed inside one JDBC transaction by
 * {@link DAO#transaction(java.sql.Connection, Transaction)}.
 *
 * <p>The callback takes no parameter: use the same {@code Connection} that was
 * passed to {@code DAO.transaction}. Return a result, or {@code null} when
 * there is nothing to return.
 */
@FunctionalInterface
public interface Transaction<T> {
    T run() throws Exception;
}
