package com.skillforge.core.knowledge.mapper;

import com.skillforge.core.knowledge.KnowledgeObservationMapper;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只读核对冻结知识绑定与当前来源状态，不返回文档或分块正文。 */
@Mapper
@KnowledgeObservationMapper
public interface KnowledgeObservationAccessMapper {
    boolean readable(@Param("tenant") String tenant, @Param("target") FrozenKnowledgeTarget target,
            @Param("targetVersions") List<KnowledgeSourceVersion> targetVersions,
            @Param("selectedVersions") List<KnowledgeSourceVersion> selectedVersions);
}
