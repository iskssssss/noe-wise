package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.capability.persistence.OpenPlatformDeploymentDO;

/** Open Platform deployment Mapper。 */
public interface OpenPlatformDeploymentMapper extends BaseMapper<OpenPlatformDeploymentDO> {
    int advancePublication(@org.apache.ibatis.annotations.Param("rows") java.util.List<OpenPlatformDeploymentDO> rows);
}
