package com.skillforge.institution.chat.context;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 同一上下文来源的事务互斥，不增加幂等表，也不以唯一约束异常恢复事务。 */
@Mapper
public interface OpenPlatformContextMessageMapper {
    boolean lock(@Param("identity") String identity);
}
