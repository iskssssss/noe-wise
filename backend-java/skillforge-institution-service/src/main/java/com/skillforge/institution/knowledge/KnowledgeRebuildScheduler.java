package com.skillforge.institution.knowledge;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.KnowledgeRebuildService;
import com.skillforge.core.knowledge.KnowledgeIndexLifecycleService;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.shared.logging.SafeFailureDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/** 重建只消费已授权创建的任务；每个租户独立RLS上下文，租约/fence由应用服务拥有。 */
@Component public class KnowledgeRebuildScheduler {
 private static final Logger log=LoggerFactory.getLogger(KnowledgeRebuildScheduler.class);
 private final TenantMapper tenants;private final KnowledgeRebuildService rebuilds;private final KnowledgeIndexLifecycleService indexes;
 public KnowledgeRebuildScheduler(TenantMapper tenants,KnowledgeRebuildService rebuilds,KnowledgeIndexLifecycleService indexes){this.tenants=tenants;this.rebuilds=rebuilds;this.indexes=indexes;}
 @Scheduled(fixedDelayString="${skillforge.knowledge-rebuild-delay-ms:5000}")
 public void scan(){for(var tenant:tenants.selectList(new LambdaQueryWrapper<TenantDO>().eq(TenantDO::getStatus,"active"))){
  String id=tenant.getId().toString();TenantContext.setTenantId(id);
  try{for(Long job:rebuilds.due(id))try{rebuilds.run(id,job);}catch(RuntimeException failure){log.warn("知识库重建调度失败 tenantId={} reason=knowledge_rebuild_schedule_failed diagnostic={}",id,SafeFailureDiagnostics.of(failure));}
   try{indexes.drain(Long.parseLong(id),1);}catch(RuntimeException failure){log.warn("知识来源投影清理调度失败 tenantId={} reason=knowledge_index_cleanup_schedule_failed diagnostic={}",id,SafeFailureDiagnostics.of(failure));}}
  finally{TenantContext.clear();}
 }}
}
