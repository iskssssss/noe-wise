package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.capability.persistence.CapabilityDefinitionDO;
import com.skillforge.core.capability.SkillCatalogSummary;
import com.skillforge.core.capability.CapabilityRequirementOption;
import com.skillforge.core.capability.CapabilityType;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Capability Definition Mapper。 */
public interface CapabilityDefinitionMapper extends BaseMapper<CapabilityDefinitionDO> {
    /** 平台 Skill 精确默认版本目录，不读取 Descriptor。 */
    List<SkillCatalogSummary> listPlatformSkills();
    /** 只读取作用域内指定类型的当前发布身份。tenant为空表示平台作用域。 */
    List<CapabilityRequirementOption> listRequirementOptions(
            @Param("tenant") Long tenant, @Param("type") CapabilityType type);
}
