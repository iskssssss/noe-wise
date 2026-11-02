package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.capability.persistence.CapabilityVersionDO;
import com.skillforge.core.capability.SkillVersionSummary;
import com.skillforge.core.capability.CapabilityVersionSummary;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Capability Version Mapper。 */
public interface CapabilityVersionMapper extends BaseMapper<CapabilityVersionDO> {
    List<SkillVersionSummary> listSkillVersionSummaries(@Param("tenant") Long tenant,
            @Param("definitionId") Long definitionId);
    List<CapabilityVersionSummary> listVersionSummaries(@Param("tenant") Long tenant,
            @Param("definitionId") Long definitionId);
}
