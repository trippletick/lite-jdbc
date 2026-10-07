# Lite-JDBC

A small JDBC helper for Java 21+. You write the SQL; Lite-JDBC handles statements, parameter binding, result mapping, resource cleanup, and transactions. It is not an ORM, and it has no runtime dependencies.

```java
List<User> users = DAO.query(
    connection,
    "select id, name from users where status = ?",
    rs -> new User(rs.getLong("id"), rs.getString("name")),
    "ACTIVE"
);
```

## Installation

Maven:

```xml
<dependency>
    <groupId>tech.jaylu</groupId>
    <artifactId>lite-jdbc</artifactId>
    <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("tech.jaylu:lite-jdbc:0.1.0")
```

Bring your own JDBC driver and `DataSource`.

## The basics

Everything is a static method on `DAO`. Each takes a `Connection` you own: Lite-JDBC never opens or closes it.

| Method | Use for | Returns |
|---|---|---|
| `query` | `SELECT` | `List<T>` of mapped rows |
| `queryBatch` | a `SELECT` too large for memory | rows delivered to a callback in batches |
| `execute` | `INSERT`, `UPDATE`, `DELETE`, DDL | number of affected rows |
| `executeBatch` | the same write with many parameter sets | `int[]` of per-entry counts |
| `executeReturning` | a write whose generated id or `RETURNING` rows you need | `List<T>` of mapped rows |
| `transaction` | grouping calls into one commit / rollback | the callback's result |

SQL uses `?` placeholders. Parameters are passed last, in order.

```java
try (Connection connection = dataSource.getConnection()) {
    // use DAO here
}
```

## Reading

### Rows

Pass a `RowMapper`, usually a lambda, that turns the current row into an object:

```java
List<User> users = DAO.query(
    connection,
    """
    select id, name, email
    from users
    where department = ? and active = ?
    """,
    rs -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")),
    department,
    true
);
```

### Single values

`SimpleTypes` has ready-made mappers that read the first column:

```java
long count = DAO.query(connection, "select count(1) from users", SimpleTypes.toLong()).getFirst();

List<String> names = DAO.query(connection, "select name from users order by name", SimpleTypes.toText());
```

| Mapper | Java type |
|---|---|
| `toText()` | `String` |
| `toInteger()` | `Integer` |
| `toLong()` | `Long` |
| `toBoolean()` | `Boolean` |
| `toDouble()` | `Double` |
| `toBigDecimal()` | `BigDecimal` |
| `toLocalDate()` | `LocalDate` |
| `toLocalDateTime()` | `LocalDateTime` |
| `toInstant()` | `Instant` |

SQL `NULL` becomes `null`. For other types, write a lambda: `rs -> rs.getObject(1, UUID.class)`.

### One row

```java
// exactly one row expected (throws NoSuchElementException if none)
User user = DAO.query(connection, sql, mapper, id).getFirst();

// zero or one row
Optional<User> user = DAO.query(connection, sql, mapper, id).stream().findFirst();
```

### Large results

`queryBatch` reads through a database cursor and hands rows to your callback in batches, so the full result is never in memory:

```java
DAO.queryBatch(
    connection,
    "select id, name, email from users where status = ?",
    1000,                                          // batch size
    rs -> new User(rs.getLong("id"), rs.getString("name"), rs.getString("email")),
    users -> streaming(users),           // a List<User> of up to 1000
    "ACTIVE"
);
```

- Every batch is a new, non-empty list; the last may be smaller.
- An empty result never calls the callback.
- To stop early, throw from the callback.

See [Database notes](#database-notes) for PostgreSQL and MySQL.

## Writing

### Insert, update, delete

```java
int updated = DAO.execute(
    connection,
    "update users set status = ? where id = ?",
    "DISABLED",
    userId
);
```

### Getting generated ids back

```java
long id = DAO.executeReturning(
    connection,
    "insert into users(name, email) values (?, ?) returning id",
    SimpleTypes.toLong(),
    name,
    email
).getFirst();
```

`executeReturning` also works for `UPDATE ... RETURNING` and `DELETE ... RETURNING`. On databases without `RETURNING`, leave the clause out and map the generated key columns by name:

```java
long id = DAO.executeReturning(
    connection,
    "insert into users(name, email) values (?, ?)",
    rs -> rs.getLong("id"),
    name,
    email
).getFirst();
```

### Many rows at once

It's recommended that try not put more than 2000 in a batch for better resilience processing. 
Each `Object[]` is one set of parameters:

```java
int[] counts = DAO.executeBatch(
    connection,
    "insert into users(id, name) values (?, ?)",
    users.stream()
        .map(user -> new Object[] { user.id(), user.name() })
        .toList()
);
```

## Transactions

Wrap work in `DAO.transaction` to commit it together. If the callback throws, everything is rolled back. Inside the callback, keep using the same `connection`.

```java
long userId = DAO.transaction(connection, () -> {
    long id = DAO.executeReturning(
        connection,
        "insert into users(name) values (?) returning id",
        SimpleTypes.toLong(),
        "Jack"
    ).getFirst();

    DAO.execute(connection, "insert into audit_log(user_id, action) values (?, ?)", id, "USER_CREATED");

    return id;   // return null if there is nothing to return
});
```

- The connection's auto-commit setting is restored afterwards.
- A `DAO.transaction` nested inside another on the same connection joins the outer one, so there is still only one commit.
- Outside `DAO.transaction`, Lite-JDBC never commits or rolls back.

## Parameter types

| Java value | Sent as |
|---|---|
| `String`, `Integer`, `Long`, `Boolean`, `Double`, `BigDecimal` | the matching SQL type |
| `Instant` | timestamp |
| any `Enum` | its `name()` as text |
| `null` | SQL `NULL` |
| anything else (`LocalDate`, `LocalDateTime`, `UUID`, `byte[]`, ...) | passed to the driver via `setObject` |

## Errors

Every method throws `SQLException`. If your callback (`BatchConsumer` or `Transaction`) throws another exception, it is wrapped in a `SQLException` with the original as its cause.

Using the wrong method for a statement fails fast: `query` on an `INSERT`, or `execute` on a `SELECT`, throws `SQLException`.

## Database notes

`queryBatch` detects the database and picks the right settings. Two cases affect you:

- **PostgreSQL:** streaming needs a transaction. If auto-commit is on, `queryBatch` runs inside its own transaction and commits at the end, along with anything your callback wrote on the same connection. If you are already in a transaction, it uses yours and does not commit.
- **MySQL (Connector/J):** add `useCursorFetch=true` to the JDBC URL. Without it, rows are streamed one at a time and the connection cannot run any other statement until `queryBatch` returns, so your callback must not use it.

Oracle, MariaDB, SQL Server, DB2, H2 and SQLite need nothing extra.

## Cheat sheet

```java
DAO.query(connection, sql, rowMapper, params...);
DAO.query(connection, sql, SimpleTypes.toLong(), params...);
DAO.queryBatch(connection, sql, batchSize, rowMapper, batchConsumer, params...);

DAO.execute(connection, sql, params...);
DAO.executeReturning(connection, sql, rowMapper, params...);
DAO.executeBatch(connection, sql, paramsList);

DAO.transaction(connection, () -> { ...; return result; });
```

## Building

```bash
mvn test
```

Tests start an embedded PostgreSQL automatically; no database setup is needed.

## Learn more

- [DESIGN.md](DESIGN.md): design goals, non-goals, and the reasoning behind the API.
- Javadoc on `DAO` for the full contract of each method.
