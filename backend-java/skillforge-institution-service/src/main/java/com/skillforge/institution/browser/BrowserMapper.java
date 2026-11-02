package com.skillforge.institution.browser;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 浏览器调用投影，SQL 由 XML 维护。 */
@Mapper
public interface BrowserMapper {
    void receipt(@Param("tenant")long tenant,@Param("user")long user,@Param("invocation")String invocation,
        @Param("task")String task,@Param("session")String session,@Param("run")String run,
        @Param("operation")String operation,@Param("status")String status,@Param("payload")String payload);
}
