package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.foundation.persistence.EndUserDO;
import org.apache.ibatis.annotations.Param;

/** C 端用户 MyBatis-Plus 映射接口。 */
public interface EndUserMapper extends BaseMapper<EndUserDO> {
    /** 冲突不写入，调用者随后读取同一外部身份；不捕获已使事务失效的唯一键异常。 */
    int insertExternal(@Param("user") EndUserDO user);
}
