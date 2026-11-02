package com.skillforge.institution.chat;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 本机聊天身份映射；所有操作由服务事务与认证上下文约束。 */
@Mapper
public interface ChatClientProfileMapper {
    void lock(@Param("key") String key);
    boolean channelExists(@Param("tenant") long tenant, @Param("channel") long channel);
    Long find(@Param("tenant") long tenant, @Param("channel") long channel, @Param("ref") String ref);
    void insert(@Param("tenant") long tenant, @Param("channel") long channel,
                @Param("ref") String ref, @Param("user") long user);
}
