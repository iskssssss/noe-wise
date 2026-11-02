package com.skillforge.runtime.adapter.jdbc.schema;

import java.util.List;

/** 结构预检与接管锁的唯一 SQL 边界。 */
interface RuntimeSchemaMapper {
    boolean hasHistory();
    List<RuntimeSchemaEntry> structure();
    void lockOwnedTables();
}
