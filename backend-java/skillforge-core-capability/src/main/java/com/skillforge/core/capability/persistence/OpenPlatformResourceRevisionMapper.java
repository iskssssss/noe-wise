package com.skillforge.core.capability.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** Open Platform resource revision Mapper。 */
public interface OpenPlatformResourceRevisionMapper extends BaseMapper<OpenPlatformResourceRevisionDO> {
    /** 每个资源的最新匹配修订摘要；正文不参与目录投影，limit在资源归并后应用。 */
    List<OpenPlatformResourceRevisionDO> listLatestSummaries(@Param("tenant") long tenant,
            @Param("type") String type, @Param("status") String status, @Param("limit") int limit);
}
