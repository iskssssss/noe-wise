package com.skillforge.core.billing.export;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 平台单流提交后发布器；数据库固定函数持有尾锁到提交，不跨网络事务。 */
@Service
public class PlatformUsagePublisher {
    private final PlatformUsageMapper mapper;
    /** 正式 Mapper 只允许发布有界已提交 pending，不接受调用者提供事实正文。 */
    public PlatformUsagePublisher(PlatformUsageMapper mapper) { this.mapper=mapper; }
    /** 独立提交可恢复发布；与源事务隔离，迟提交事实留在 pending 下轮续接。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW,rollbackFor=Exception.class)
    public long publishCommitted() { return mapper.publishCommitted(1_000); }
}
