package com.skillforge.core.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.knowledge.model.KnowledgeDocumentDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseCount;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 知识文档 MyBatis-Plus 映射接口。
 */
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {

    /** 仅在调用方已读取的非空知识库集合内聚合未删除文档。 */
    List<KnowledgeBaseCount> countByBase(@Param("tenantId") long tenantId, @Param("kbIds") List<Long> kbIds);

    /** 仅在当前指针仍等于预期值时切换到新 ready 来源版本。 */
    int compareAndSetCurrentVersion(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("expectedSourceVersion") String expectedSourceVersion,
            @Param("newSourceVersion") String newSourceVersion);

    /** 首次摄取时仅允许从空指针安装第一版。 */
    int installInitialVersion(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("newSourceVersion") String newSourceVersion);

    /** expected_source_version 匹配时软删除容器。 */
    int softDelete(
            @Param("tenantId") long tenantId,
            @Param("documentId") long documentId,
            @Param("expectedSourceVersion") String expectedSourceVersion);
}
