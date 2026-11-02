package com.skillforge.institution.service;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
/** 工单受理行锁与恢复查询；所有写入显式绑定租户。 */
@Mapper
public interface HandoffReplyMapper {
 String lockTicket(@Param("tenant")long tenant,@Param("ticket")String ticket);
 HandoffReplyRequest find(@Param("tenant")long tenant,@Param("id")String id);
 HandoffReplyRequest claim(@Param("tenant")long tenant,@Param("id")String id);
 List<HandoffReplyRequest> list(@Param("tenant")long tenant,@Param("ticket")String ticket);
 List<String> pending(@Param("tenant")long tenant);
 int insert(HandoffReplyRequest request);
 int commit(@Param("tenant")long tenant,@Param("id")String id,@Param("source")String source,@Param("sequence")long sequence);
 int reject(@Param("tenant")long tenant,@Param("id")String id,@Param("error")String error);
}
