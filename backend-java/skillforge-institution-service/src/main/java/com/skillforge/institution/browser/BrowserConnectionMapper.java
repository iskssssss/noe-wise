package com.skillforge.institution.browser;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 连接请求按机构 RLS 隔离；写入统一使用版本 CAS。 */
@Mapper
public interface BrowserConnectionMapper {
    /** 首次创建；同一稳定请求不覆盖已提交事实。 */
    int insert(@Param("row") BrowserConnectionRequest row);
    /** 读取本机构的准确请求。 */
    BrowserConnectionRequest find(@Param("tenant") long tenant, @Param("id") String id);
    /** 每批读取有限活动等待；已完成记录不再轮询。 */
    List<BrowserConnectionRequest> pending(@Param("tenant") long tenant, @Param("after") String after, @Param("limit") int limit);
    /** 按旧版本推进，竞争失败返回零，不覆盖新目标或取消。 */
    int update(@Param("row") BrowserConnectionRequest row, @Param("expected") long expected);
}
