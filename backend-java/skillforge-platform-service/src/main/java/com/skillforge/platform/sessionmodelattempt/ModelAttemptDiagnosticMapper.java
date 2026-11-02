package com.skillforge.platform.sessionmodelattempt;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台私有诊断精确读取typed实际Attempt/材料；查询必须处于已授权租户快照内。 */
@Mapper
public interface ModelAttemptDiagnosticMapper {
    /** 同真实Host Session及typed Run校验，原实际request/result不来自旧fact/preparation。 */
    List<Map<String,Object>> attempt(@Param("tenant") long tenant,@Param("session") String session,@Param("attempt") String attempt,@Param("root") String root);
    /** 父产品轨迹已核验的真实后代Run；不能只凭source Session或attempt绕过父路径授权。 */
    List<Map<String,Object>> descendantAttempt(@Param("tenant") long tenant,@Param("session") String session,
            @Param("root") String root,@Param("attempt") String attempt,@Param("verifiedRun") String verifiedRun);
    /** 已授权请求manifest的精确叶集合，单次批读且不查询当前模型配置。 */
    List<Map<String,Object>> materials(@Param("tenant") String tenant, @Param("references") List<String> references);
    /** 原已采纳MODEL拥有的manifest或ordinal区间；最多65条，仅检测截断，不扫描Session。 */
    List<Map<String,Object>> capabilityFacts(@Param("tenant") String tenant,@Param("run") String run,@Param("attempt") String attempt,@Param("ordinal") long ordinal);
}
