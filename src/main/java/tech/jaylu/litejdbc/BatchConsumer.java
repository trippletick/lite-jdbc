package tech.jaylu.litejdbc;

import java.util.List;

/**
 * Receives one batch of mapped rows from
 * {@link DAO#queryBatch(java.sql.Connection, String, int, RowMapper, BatchConsumer, Object...)}.
 */
@FunctionalInterface
public interface BatchConsumer<T> {
    void accept(List<T> batch) throws Exception;
}
