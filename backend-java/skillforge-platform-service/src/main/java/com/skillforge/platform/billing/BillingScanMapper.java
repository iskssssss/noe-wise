package com.skillforge.platform.billing;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台手动扫描幂等回执，认证主体与操作标识共同确定身份。 */
@Mapper
public interface BillingScanMapper {
    int claim(@Param("actor") long actor, @Param("operation") String operation);
    BillingScanReceipt find(@Param("actor") long actor, @Param("operation") String operation);
    int complete(@Param("actor") long actor, @Param("operation") String operation,
            @Param("scanned") int scanned, @Param("warning") int warning, @Param("active") int active);
}
