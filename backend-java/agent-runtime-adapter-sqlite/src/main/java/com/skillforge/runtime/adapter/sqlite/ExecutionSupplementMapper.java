package com.skillforge.runtime.adapter.sqlite;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

/** 原Run补充输入的专有关系读写；调用方已持同租户Run锁，映射本身不建立事务。 */
interface ExecutionSupplementMapper {
    static void configure(Configuration configuration) {
        Objects.requireNonNull(configuration);
        if (configuration.hasMapper(ExecutionSupplementMapper.class)) return;
        String resource = "mapper/SqliteExecutionSupplementMapper.xml";
        try (InputStream xml = ExecutionSupplementMapper.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("execution_supplement_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("execution_supplement_mapper_initialization_failed", failure); }
    }

    ExecutionSupplementRow find(@Param("tenant") String tenant, @Param("run") String run, @Param("input") String input);
    List<ExecutionSupplementRow> inputs(@Param("tenant") String tenant, @Param("run") String run,
            @Param("after") long after, @Param("pendingOnly") boolean pendingOnly, @Param("limit") int limit);
    long nextOrdinal(@Param("tenant") String tenant, @Param("run") String run);
    int insert(ExecutionSupplementRow row);
    int apply(ExecutionSupplementRow row);
}
