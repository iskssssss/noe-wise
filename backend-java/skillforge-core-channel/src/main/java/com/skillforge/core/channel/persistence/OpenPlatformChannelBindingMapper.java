package com.skillforge.core.channel.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.channel.persistence.OpenPlatformChannelBindingDO;

/** Open Platform channel binding Mapper。 */
public interface OpenPlatformChannelBindingMapper extends BaseMapper<OpenPlatformChannelBindingDO> {
    int advancePublication(@org.apache.ibatis.annotations.Param("rows") java.util.List<OpenPlatformChannelBindingDO> rows);
}
