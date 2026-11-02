package com.skillforge.runtime.adapter.jdbc.schema;

/** PostgreSQL 规范化 catalog 定义；比较内容包含安全属性与约束，排除宿主角色和对象 OID。 */
record RuntimeSchemaEntry(String kind, String name, String definition) { }
