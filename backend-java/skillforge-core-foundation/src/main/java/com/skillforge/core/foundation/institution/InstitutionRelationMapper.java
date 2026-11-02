package com.skillforge.core.foundation.institution;

import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 机构关系管理仓储；目录先限制合法目标及精确关系再分页。 */
@Mapper
public interface InstitutionRelationMapper {
    long lockTree();
    long treeRevision();
    Row find(@Param("tenantId")long tenantId);
    boolean wouldCycle(@Param("child")long child,@Param("parent")long parent);
    int change(@Param("child")long child,@Param("parent")Long parent,@Param("expected")long expected);
    int append(@Param("id")long id,@Param("child")long child,@Param("parent")Long parent,@Param("revision")long revision,
            @Param("tree")long tree,@Param("actor")String actor,@Param("key")String key);
    List<Row> platformRows(@Param("allowed")Set<Long> allowed,@Param("parent")Long parent,@Param("limit")int limit,@Param("offset")long offset);
    long platformCount(@Param("allowed")Set<Long> allowed,@Param("parent")Long parent);
    List<Row> subRows(@Param("parent")long parent,@Param("limit")int limit,@Param("offset")long offset);
    long subCount(@Param("parent")long parent);
    void lockRequest(@Param("actor")String actor,@Param("operation")String operation,@Param("key")String key);
    Receipt request(@Param("actor")String actor,@Param("operation")String operation,@Param("key")String key);
    int receipt(@Param("actor")String actor,@Param("operation")String operation,@Param("key")String key,
            @Param("fingerprint")String fingerprint,@Param("child")long child,@Param("result")String result);
    int grantRead(@Param("id")long id,@Param("parent")long parent,@Param("child")long child,@Param("relation")long relation);
    record Row(Long tenantId,Long parentTenantId,String name,String status,long revision) { }
    record Receipt(String fingerprint,String result) { }
}
