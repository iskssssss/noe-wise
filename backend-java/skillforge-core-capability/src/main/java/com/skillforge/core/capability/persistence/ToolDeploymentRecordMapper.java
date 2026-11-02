package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.capability.AvailableToolDeployment;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Tool Deployment Mapper。 */
public interface ToolDeploymentRecordMapper extends BaseMapper<ToolDeploymentRecordDO> {
    List<AvailableToolDeployment> listAvailableTools(@Param("tenant") Long tenant);
}
