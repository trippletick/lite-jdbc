# Lite-JDBC design

This document explains why Lite-JDBC looks the way it does. For how to use it, see [README.md](README.md). The implementation lives in `src/main/java/tech/jaylu/litejdbc/`; this document describes behaviour and reasoning rather than repeating the code.

## Principle

> Abstract JDBC ceremony, not SQL.

SQL stays visible and explicit. The library handles the repetitive JDBC mechanics: statement creation, parameter binding, result iteration, generated keys, resource cleanup, batch execution, and transaction lifecycle.

## Goals

- Keep SQL explicit.
- Keep the API small.
- Let the caller own the `Connection`.
- Let the caller decide transaction boundaries.
- Centralize JDBC resource handling.
- Centralize parameter type binding.
- Make intent obvious: `query` reads, `execute` writes.
- Make row mapping explicit: every method that returns rows takes a `RowMapper`.
- Avoid annotations, reflection, repositories, sessions, and hidden SQL generation.

## Non-goals

The library is intentionally **not** an ORM and will not provide:

```text
@Entity / @Table / @Repository
findById() / save()
dirty checking
lazy loading
object graphs
SQL generation
schema management
connection pooling
dependency injection
```

Those belong to higher-level libraries.

## Ownership rules

```text
Caller owns Connection.
DAO owns PreparedStatement.
DAO owns ResultSet.
```

Every DAO method closes the statements and result sets it creates before returning, on success or failure. It never closes the connection.

DAO methods never commit or roll back implicitly. Only `DAO.transaction(...)`, or application code outside the DAO, controls the transaction boundary. The one documented case where the library opens a transaction itself is `queryBatch` on PostgreSQL in auto-commit mode, and it does so through `DAO.transaction(...)` (see [queryBatch](#querybatch)).

## API shape

### `query` reads, `execute` writes

| Method | Statement kind |
|---|---|
| `query`, `queryBatch` | `SELECT`, `WITH ... SELECT`, `VALUES` |
| `execute`, `executeReturning`, `executeBatch` | `INSERT`, `UPDATE`, `DELETE`, DDL |
| `transaction` | groups the above |

The names carry intent, which matches what readers expect at the call site. An earlier draft routed `INSERT ... RETURNING` through `query`; it worked, but read wrongly.

**The SQL text is not inspected.** Classifying SQL by its first keyword is fragile (CTEs, comments, `VALUES`, vendor extensions), and the JDBC driver already enforces the split. With pgjdbc:

| Call | Driver error |
|---|---|
| `execute("select 1")` | `A result was returned when none was expected.` |
| `query("insert ...")` | `No results were returned by the query.` |
| `execute("insert ... returning id")` | `A result was returned when none was expected.` |

### `executeReturning` instead of an overload

A write that returns data (generated ids, `RETURNING` rows) gets its own method rather than an `execute(connection, sql, mapper, params...)` overload. Java would resolve the overload correctly, but it is easy to misread at the call site.

It reads rows through `Statement.RETURN_GENERATED_KEYS` and `getGeneratedKeys()`, which is portable to databases without `RETURNING` (MySQL, H2, ...). On PostgreSQL:

- with an explicit `RETURNING` clause, the driver returns exactly those columns;
- without one, pgjdbc appends `RETURNING *`, so every column of the affected rows comes back and callers map by column name.

### No `queryOne`

The standard `List` API already covers it, with no extra method to learn:

```java
User user = DAO.query(connection, sql, mapper, id).getFirst();                     // exactly one
Optional<User> user = DAO.query(connection, sql, mapper, id).stream().findFirst(); // zero or one
```

Trade-off: `getFirst()` on an empty list throws `NoSuchElementException`, not `SQLException`, and there is no built-in "more than one row" check. Both are acceptable for the common cases (`count(*)`, lookups by primary key, `RETURNING id`).

### No `Class<T>` scalar mapping; `SimpleTypes` instead

A `query(connection, sql, Long.class)` overload needs a type-dispatch table that grows over time and hides which JDBC getter runs. `SimpleTypes` makes the conversion explicit at the call site with ready-made first-column `RowMapper`s. Anything not covered is a one-line lambda, e.g. `rs -> rs.getObject(1, UUID.class)`.

- Numeric and boolean mappers check `wasNull()` so SQL `NULL` becomes Java `null` rather than `0` / `false`.
- The `String` mapper is named `toText()` because a static `toString()` cannot hide `Object.toString()` and would not compile.

### Custom functional interfaces

`RowMapper`, `BatchConsumer` and `Transaction` are custom interfaces instead of `java.util.function` types because JDBC work throws checked `SQLException`, and consumers commonly throw `IOException`. `BatchConsumer` and `Transaction` declare `throws Exception`; non-SQL exceptions are wrapped in `SQLException` so every DAO method has one checked exception type.

`Transaction<T>` is `T run() throws Exception`:

- **No `Connection` parameter.** The callback would receive the very connection passed to `DAO.transaction`, so the parameter added nothing but a second name (`conn` vs `connection`) for the same object. The lambda captures `connection` instead.
- **Keeps the return value.** A transaction often produces something, typically a generated id. With `void run()`, a lambda cannot assign to an outer local variable, so those callers would need an `AtomicLong` or array holder. The cost of keeping `T` is a `return null;` in transactions with nothing to return.
- A `void` overload (plus `Callable<T>` for results) was considered and rejected to keep a single method.

### `executeBatch(List<Object[]>)` instead of a `Binder<T>`

An earlier draft took `Collection<T>` plus a `Binder<T>` that called `ps.setXxx(...)` by hand. A list of parameter arrays reuses the same central binding as every other method, so batch entries bind exactly like `execute` parameters, and the caller never touches `PreparedStatement`.

The result stays `int[]` because JDBC reports one result per entry and may return `Statement.SUCCESS_NO_INFO` or `Statement.EXECUTE_FAILED`. Collapsing those into one number would lose information.

## Parameter binding

All methods bind `?` parameters through one function:

| Java value | Binding |
|---|---|
| `null` | `setObject(i, null)` |
| `String`, `Integer`, `Long`, `Boolean`, `Double`, `BigDecimal` | the typed setter |
| `Instant` | `setTimestamp(i, Timestamp.from(v))` |
| `Enum` | `setString(i, v.name())` |
| anything else | `setObject(i, v)` |

The default case intentionally delegates other types (`LocalDate`, `LocalDateTime`, `UUID`, `byte[]`, ...) to the driver, which handles them well on modern drivers. The explicit cases exist only where the driver default is wrong or ambiguous.

## queryBatch

### Problem

`query` collects every row into a list. For large results the caller should decide what to do with rows as they arrive (stream them out, aggregate, or cache some), without the library building a list of everything.

### Shape

```java
DAO.queryBatch(connection, sql, batchSize, mapper, consumer, params...)
```

- **Callback, not `Stream` or `Iterator`.** A returned lazy stream keeps the statement and server cursor open until the caller closes it; forgetting try-with-resources leaks them. With a callback nothing escapes the method call, so the library can always close everything.
- **Separate name, not a `query` overload.** A row lambda like `rs -> list.add(...)` fits both a `RowMapper<T>` and a callback taking the `ResultSet`, which leads to ambiguous calls or silently picking the wrong overload.
- **Reuses `RowMapper`.** `SimpleTypes` and existing mappers work unchanged, and the caller never holds the live `ResultSet`.
- **The consumer receives `List<T>` batches.** This makes the name honest, and chunks are often exactly what is needed (bulk indexing, feeding `executeBatch`). Per-row processing is a loop inside the consumer.
- **One knob.** `batchSize` is both the batch size and the JDBC fetch size.
- **Stopping early means throwing.** A `boolean` "continue" return would add noise to every lambda for a rare need.

Batch rules: each batch is a new, non-empty list the consumer may keep; only the last may be smaller than `batchSize`; an empty result never calls the consumer; `batchSize < 1` throws `IllegalArgumentException`.

### Database differences

JDBC drivers disagree on what it takes to fetch incrementally:

| Database (driver) | Default | Incremental fetch | Needs auto-commit off |
|---|---|---|---|
| PostgreSQL (pgjdbc) | loads whole result | `setFetchSize(n)` + auto-commit off + forward-only | **yes** |
| Oracle | cursor, 10 rows per round trip | `setFetchSize(n)` tunes it | no |
| MySQL (Connector/J) | loads whole result; ignores `setFetchSize(n)` | `useCursorFetch=true` + `setFetchSize(n)`, or `setFetchSize(Integer.MIN_VALUE)` for row streaming | no |
| MariaDB (Connector/J) | loads whole result | `setFetchSize(n)` | no |
| SQL Server (mssql-jdbc) | adaptive buffering already streams | nothing | no |
| DB2 | cursor with block fetch | nothing (`setFetchSize` tunes it) | no |
| SQLite / H2 | embedded, rows stepped on demand | nothing | no |

`FetchStrategy` (package-private) reads `DatabaseMetaData` and decides the fetch size and whether a transaction is needed:

- **PostgreSQL** (database product name contains `postgresql`): fetch size `batchSize`; needs a transaction.
- **MySQL Connector/J** (driver name starts with `mysql connector`): `batchSize` if the URL contains `useCursorFetch=true`, otherwise `Integer.MIN_VALUE`. Detection uses the *driver* name because MariaDB Connector/J talking to a MySQL server still reports product `MySQL` but handles a plain fetch size.
- **Everything else:** fetch size `batchSize`.

### Transaction handling

`queryBatch` opens a transaction only when the strategy needs one **and** auto-commit is on:

- If it always used `DAO.transaction`, a caller who had turned auto-commit off themselves would get their pending writes committed by what looks like a read, breaking the "no implicit commit" rule.
- If auto-commit is already off (a `DAO.transaction` or a caller-managed transaction), it simply streams inside that transaction.
- When it does open one, it reuses `DAO.transaction`, so rollback, auto-commit restoration and nesting behave identically. The commit at the end is a no-op for a pure read; if the consumer writes on the same connection, those writes commit or roll back together with the read.

### MySQL row-streaming trade-off

`Integer.MIN_VALUE` is the only way to bound memory on Connector/J without a URL change, so it is the default there. The cost: while the result is open, the connection cannot run any other statement, so the consumer must not use it. Users who need that should add `useCursorFetch=true` to the URL, which switches `queryBatch` to a real server-side cursor.

## transaction

### Lifecycle

1. Remember the original auto-commit value.
2. Disable auto-commit.
3. Run the callback.
4. Commit on success.
5. Roll back on failure.
6. Restore the original auto-commit value.

A non-SQL exception from the callback is wrapped as `SQLException("Transaction failed", e)`; a `SQLException` is rethrown as is.

### Cleanup errors never mask the original failure

If rollback or restoring auto-commit fails after the callback failed, those errors are attached to the thrown exception with `addSuppressed`. Restoring auto-commit is deliberately not in a plain `finally` block, where its exception would replace the real one.

### Nested calls join the outer transaction

Without special handling, an inner `DAO.transaction` on the same connection would commit the outer transaction's work early and then turn auto-commit back off. Instead, `DAO` tracks connections currently inside `transaction` in a concurrent set. A nested call on a tracked connection just runs the callback: no commit, no rollback, no auto-commit change. Its failure propagates to the outer call, which rolls back everything.

Limitation: tracking is by `Connection` object identity. A wrapper or proxy around the same physical connection counts as a different connection, and its inner call would commit on its own.

### Caller-managed auto-commit off

Calling `DAO.transaction` on a connection whose auto-commit the caller already turned off (outside `DAO.transaction`) still commits at the end and restores auto-commit to `false`. `DAO.transaction` defines a transaction boundary; it does not try to guess at foreign ones.

## Testing approach

- Tests run against a real PostgreSQL started by [zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres), once per JVM, from `DatabaseTestBase`.
- Each test drops and recreates the `public` schema and loads `src/test/resources/schema.sql`, so every test starts from an empty database with its own connection.
- The schema covers text, integer, numeric, double, boolean, enum-as-text, date, timestamp, and timestamptz columns, a foreign key, and a many-to-many join table.
- `queryBatch` streaming is verified by querying `pg_cursors` from inside the consumer: the driver's cursor must be open during every batch. The test was confirmed to fail when the fetch size is forced to 0.
- `FetchStrategy` decisions for other databases are unit-tested with `java.lang.reflect.Proxy` fakes of `Connection` and `DatabaseMetaData`, since only PostgreSQL runs in the test suite.
