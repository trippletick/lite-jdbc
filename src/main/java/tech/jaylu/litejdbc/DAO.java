package tech.jaylu.litejdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static helpers that remove JDBC ceremony while keeping SQL, connections and
 * transaction boundaries explicit.
 *
 * <h2>Choosing a method</h2>
 * <ul>
 *   <li>{@link #query} reads: {@code SELECT}, {@code WITH ... SELECT}, {@code VALUES}.</li>
 *   <li>{@link #queryBatch} reads a large result in batches without holding it all in memory.</li>
 *   <li>{@link #execute} writes: {@code INSERT}, {@code UPDATE}, {@code DELETE}, DDL.</li>
 *   <li>{@link #executeReturning} writes and maps the rows the write returns,
 *       e.g. generated keys or a {@code RETURNING} clause.</li>
 *   <li>{@link #executeBatch} repeats one write for many parameter sets in a JDBC batch.</li>
 *   <li>{@link #transaction} groups calls into one commit or rollback.</li>
 * </ul>
 *
 * <h2>Resources and transactions</h2>
 * <p>The caller owns the {@link Connection}: obtain it, pass it in, close it.
 * This class owns every {@link PreparedStatement} and {@link ResultSet} it
 * creates and always closes them before returning. No method commits or rolls
 * back on its own except {@link #transaction}, which {@link #queryBatch} uses
 * on PostgreSQL in auto-commit mode.
 *
 * <pre>{@code
 * try (Connection connection = dataSource.getConnection()) {
 *     List<String> names = DAO.query(connection, "select name from users", SimpleTypes.toText());
 * }
 * }</pre>
 *
 * <h2>Parameters</h2>
 * <p>SQL uses {@code ?} placeholders; {@code params} are bound in order:
 * {@code String}, {@code Integer}, {@code Long}, {@code Boolean},
 * {@code Double} and {@code BigDecimal} with their typed setters,
 * {@code Instant} as a {@code Timestamp}, an {@code Enum} as its
 * {@link Enum#name()}, {@code null} as SQL {@code NULL}, and any other type
 * through {@link PreparedStatement#setObject(int, Object)}.
 */
public final class DAO {

    /** Connections currently inside {@link #transaction}, used to join nested calls. */
    private static final Set<Connection> ACTIVE_TRANSACTIONS = ConcurrentHashMap.newKeySet();

    private DAO() {
    }

    /**
     * Runs a read and maps every row of the result.
     *
     * <p>Use it for {@code SELECT}, {@code WITH ... SELECT} and {@code VALUES}.
     * All rows are loaded into the returned list, so for a result too large
     * for memory use {@link #queryBatch} instead.
     *
     * <pre>{@code
     * List<User> users = DAO.query(
     *     connection,
     *     "select id, name from users where status = ?",
     *     rs -> new User(rs.getLong("id"), rs.getString("name")),
     *     "ACTIVE"
     * );
     *
     * // one-column results
     * long count = DAO.query(connection, "select count(*) from users", SimpleTypes.toLong()).getFirst();
     *
     * // zero or one row
     * Optional<User> user = DAO.query(connection, sql, mapper, id).stream().findFirst();
     * }</pre>
     *
     * @param connection the connection to use; not closed by this method
     * @param sql        the read statement, with {@code ?} placeholders
     * @param mapper     maps the current row; must not call {@code rs.next()}
     * @param params     values for the placeholders, in order
     * @param <T>        the mapped row type
     * @return the mapped rows in result order; empty if there are none
     * @throws SQLException if the statement fails, returns no result set
     *                      (e.g. a write), or the mapper throws
     */
    public static <T> List<T> query(
        Connection connection,
        String sql,
        RowMapper<T> mapper,
        Object... params
    ) throws SQLException {

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                List<T> result = new ArrayList<>();

                while (rs.next()) {
                    result.add(mapper.map(rs));
                }

                return result;
            }
        }
    }

    /**
     * Runs a read whose result is too large for memory, delivering the rows
     * to {@code consumer} in batches of up to {@code batchSize} through a
     * database cursor. Only one batch is held in memory at a time.
     *
     * <pre>{@code
     * DAO.queryBatch(
     *     connection,
     *     "select id, name, email from users where status = ?",
     *     1000,
     *     rs -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")),
     *     users -> searchIndex.bulkPut(users),   // users.size() <= 1000
     *     "ACTIVE"
     * );
     * }</pre>
     *
     * <p>Batches:
     * <ul>
     *   <li>Each batch is a new, non-empty list that the consumer may keep;
     *       only the last may be smaller than {@code batchSize}.</li>
     *   <li>An empty result never calls the consumer.</li>
     *   <li>{@code batchSize} is also used as the JDBC fetch size.</li>
     *   <li>To stop early, throw from the consumer.</li>
     * </ul>
     *
     * <p>Database specifics are handled here:
     * <ul>
     *   <li>PostgreSQL only uses a cursor with auto-commit off. A connection in
     *       auto-commit mode is therefore read inside {@link #transaction},
     *       which commits at the end, including any writes the consumer made on
     *       the same connection. A transaction the caller already has open is
     *       used as is and not committed.</li>
     *   <li>MySQL Connector/J uses a cursor only when the JDBC URL has
     *       {@code useCursorFetch=true}. Without it, rows are streamed one at a
     *       time and the connection cannot run any other statement until this
     *       method returns, so the consumer must not use it.</li>
     *   <li>Other databases need nothing beyond the fetch size.</li>
     * </ul>
     *
     * @param connection the connection to use; not closed by this method
     * @param sql        the read statement, with {@code ?} placeholders
     * @param batchSize  the maximum rows per batch, and the fetch size; at least 1
     * @param mapper     maps the current row; must not call {@code rs.next()}
     * @param consumer   receives each batch in result order
     * @param params     values for the placeholders, in order
     * @param <T>        the mapped row type
     * @throws IllegalArgumentException if {@code batchSize} is less than 1
     * @throws SQLException if the statement or mapper fails; a
     *                      {@code SQLException} from the consumer is rethrown
     *                      as is, any other exception is wrapped with the
     *                      message {@code "Batch consumer failed"}
     */
    public static <T> void queryBatch(
        Connection connection,
        String sql,
        int batchSize,
        RowMapper<T> mapper,
        BatchConsumer<T> consumer,
        Object... params
    ) throws SQLException {

        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
        }

        FetchStrategy strategy = FetchStrategy.of(connection, batchSize);

        if (strategy.requiresTransaction() && connection.getAutoCommit()) {
            transaction(connection, () -> {
                readBatches(connection, sql, strategy.fetchSize(), batchSize, mapper, consumer, params);
                return null;
            });
        } else {
            readBatches(connection, sql, strategy.fetchSize(), batchSize, mapper, consumer, params);
        }
    }

    private static <T> void readBatches(
        Connection connection,
        String sql,
        int fetchSize,
        int batchSize,
        RowMapper<T> mapper,
        BatchConsumer<T> consumer,
        Object... params
    ) throws SQLException {

        try (PreparedStatement ps = connection.prepareStatement(
            sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            ps.setFetchSize(fetchSize);
            bind(ps, params);

            try (ResultSet rs = ps.executeQuery()) {
                List<T> batch = new ArrayList<>();

                while (rs.next()) {
                    batch.add(mapper.map(rs));

                    if (batch.size() == batchSize) {
                        accept(consumer, batch);
                        batch = new ArrayList<>();
                    }
                }

                if (!batch.isEmpty()) {
                    accept(consumer, batch);
                }
            }
        }
    }

    private static <T> void accept(BatchConsumer<T> consumer, List<T> batch) throws SQLException {
        try {
            consumer.accept(batch);
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Batch consumer failed", e);
        }
    }

    /**
     * Runs a write and returns how many rows it affected.
     *
     * <p>Use it for {@code INSERT}, {@code UPDATE}, {@code DELETE} and DDL.
     * When the write's generated keys or {@code RETURNING} rows are needed,
     * use {@link #executeReturning}; for the same write with many parameter
     * sets, use {@link #executeBatch}.
     *
     * <pre>{@code
     * int updated = DAO.execute(
     *     connection,
     *     "update users set status = ? where id = ?",
     *     Status.DISABLED,
     *     userId
     * );
     * }</pre>
     *
     * @param connection the connection to use; not closed by this method
     * @param sql        the write statement, with {@code ?} placeholders
     * @param params     values for the placeholders, in order
     * @return the number of affected rows; {@code 0} for DDL
     * @throws SQLException if the statement fails or returns a result set
     *                      (e.g. a {@code SELECT} or a {@code RETURNING} clause)
     */
    public static int execute(
        Connection connection,
        String sql,
        Object... params
    ) throws SQLException {

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * Runs a write and maps the rows it returns, such as generated ids or the
     * rows changed by an {@code UPDATE}.
     *
     * <p>Rows are read through {@link PreparedStatement#getGeneratedKeys()},
     * so this works with any driver that supports generated keys. If the SQL
     * has its own {@code RETURNING} clause, exactly those columns are
     * returned. Otherwise the driver decides which columns count as generated
     * keys; PostgreSQL returns every column of the affected rows, so map by
     * column name.
     *
     * <pre>{@code
     * long id = DAO.executeReturning(
     *     connection,
     *     "insert into users(name, email) values (?, ?) returning id",
     *     SimpleTypes.toLong(),
     *     name,
     *     email
     * ).getFirst();
     *
     * // without RETURNING
     * long id = DAO.executeReturning(
     *     connection,
     *     "insert into users(name, email) values (?, ?)",
     *     rs -> rs.getLong("id"),
     *     name,
     *     email
     * ).getFirst();
     * }</pre>
     *
     * @param connection the connection to use; not closed by this method
     * @param sql        the write statement, with {@code ?} placeholders
     * @param mapper     maps the current returned row; must not call {@code rs.next()}
     * @param params     values for the placeholders, in order
     * @param <T>        the mapped row type
     * @return the mapped rows; empty if the write affected no rows
     * @throws SQLException if the statement or mapper fails
     */
    public static <T> List<T> executeReturning(
        Connection connection,
        String sql,
        RowMapper<T> mapper,
        Object... params
    ) throws SQLException {

        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                List<T> result = new ArrayList<>();

                while (rs.next()) {
                    result.add(mapper.map(rs));
                }

                return result;
            }
        }
    }

    /**
     * Runs one write repeatedly, once per parameter set, as a single JDBC batch.
     *
     * <p>Each element of {@code paramsList} holds the placeholder values for
     * one execution, bound with the same rules as {@link #execute}. To make
     * the whole batch atomic, call it inside {@link #transaction}.
     *
     * <pre>{@code
     * int[] counts = DAO.executeBatch(
     *     connection,
     *     "insert into users(id, name) values (?, ?)",
     *     users.stream()
     *         .map(user -> new Object[] { user.id(), user.name() })
     *         .toList()
     * );
     * }</pre>
     *
     * @param connection the connection to use; not closed by this method
     * @param sql        the write statement, with {@code ?} placeholders
     * @param paramsList one array of placeholder values per execution
     * @return one update count per entry, in order; an entry may be
     *         {@link Statement#SUCCESS_NO_INFO} or
     *         {@link Statement#EXECUTE_FAILED} depending on the driver;
     *         empty if {@code paramsList} is empty
     * @throws java.sql.BatchUpdateException if an entry fails
     * @throws SQLException if the statement cannot be prepared or bound
     */
    public static int[] executeBatch(
        Connection connection,
        String sql,
        List<Object[]> paramsList
    ) throws SQLException {

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Object[] params : paramsList) {
                bind(ps, params);
                ps.addBatch();
            }

            return ps.executeBatch();
        }
    }

    /**
     * Runs {@code transaction} as one database transaction on
     * {@code connection}: commit if it returns, roll back if it throws.
     *
     * <p>Auto-commit is turned off for the duration and then restored to its
     * original value. Inside the callback, keep using {@code connection}.
     *
     * <pre>{@code
     * long userId = DAO.transaction(connection, () -> {
     *     long id = DAO.executeReturning(
     *         connection,
     *         "insert into users(name) values (?) returning id",
     *         SimpleTypes.toLong(),
     *         "Jack"
     *     ).getFirst();
     *
     *     DAO.execute(connection, "insert into audit_log(user_id, action) values (?, ?)", id, "USER_CREATED");
     *     return id;
     * });
     * }</pre>
     *
     * <p>A nested call on the same connection joins the outer transaction: it
     * neither commits, rolls back nor touches auto-commit, and its failure
     * propagates to the outer call, which rolls back everything. Nesting is
     * tracked per {@code Connection} object.
     *
     * <p>If rollback or restoring auto-commit fails after the callback threw,
     * those errors are attached to the thrown exception as
     * {@linkplain Throwable#getSuppressed() suppressed} exceptions.
     *
     * @param connection  the connection to use; not closed by this method
     * @param transaction the work to run on {@code connection}; returns a result,
     *                    or {@code null} when there is nothing to return
     * @param <T>         the result type
     * @return the callback's result
     * @throws SQLException if the callback throws, rethrown as is when it is a
     *                      {@code SQLException} and otherwise wrapped with the
     *                      message {@code "Transaction failed"}; or if commit
     *                      or changing auto-commit fails
     */
    public static <T> T transaction(
        Connection connection,
        Transaction<T> transaction
    ) throws SQLException {

        if (!ACTIVE_TRANSACTIONS.add(connection)) {
            try {
                return transaction.run();
            } catch (Exception e) {
                throw toSqlException(e);
            }
        }

        try {
            boolean originalAutoCommit = connection.getAutoCommit();
            T result;

            try {
                connection.setAutoCommit(false);
                result = transaction.run();
                connection.commit();

            } catch (Exception e) {
                SQLException failure = toSqlException(e);

                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    failure.addSuppressed(rollbackError);
                }

                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException restoreError) {
                    failure.addSuppressed(restoreError);
                }

                throw failure;
            }

            connection.setAutoCommit(originalAutoCommit);
            return result;

        } finally {
            ACTIVE_TRANSACTIONS.remove(connection);
        }
    }

    private static SQLException toSqlException(Exception e) {
        return e instanceof SQLException sqlException
            ? sqlException
            : new SQLException("Transaction failed", e);
    }

    private static void bind(
        PreparedStatement ps,
        Object... params
    ) throws SQLException {

        for (int i = 0; i < params.length; i++) {
            setParameter(ps, i + 1, params[i]);
        }
    }

    private static void setParameter(
        PreparedStatement ps,
        int index,
        Object value
    ) throws SQLException {

        switch (value) {
            case null ->
                ps.setObject(index, null);

            case String v ->
                ps.setString(index, v);

            case Integer v ->
                ps.setInt(index, v);

            case Long v ->
                ps.setLong(index, v);

            case Boolean v ->
                ps.setBoolean(index, v);

            case Double v ->
                ps.setDouble(index, v);

            case BigDecimal v ->
                ps.setBigDecimal(index, v);

            case Instant v ->
                ps.setTimestamp(index, Timestamp.from(v));

            case Enum<?> v ->
                ps.setString(index, v.name());

            default ->
                ps.setObject(index, value);
        }
    }
}
