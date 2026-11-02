package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.foundation.persistence.ControlOperationDO;

/** ControlOperation 受理账本 Mapper。 */
public interface ControlOperationMapper extends BaseMapper<ControlOperationDO> {
    /** 同键并发受理由数据库返回插入行数，不让唯一约束异常中止原事务。 */
    int insertAccepted(ControlOperationDO operation);
}
