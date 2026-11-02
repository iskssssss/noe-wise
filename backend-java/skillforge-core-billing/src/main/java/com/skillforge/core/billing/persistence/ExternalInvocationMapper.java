package com.skillforge.core.billing.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.billing.invocation.ExternalInvocationDO;

/** 外部调用尝试账本 Mapper；状态迁移只能经 ExternalInvocationLedger。 */
public interface ExternalInvocationMapper extends BaseMapper<ExternalInvocationDO> {
}
