package com.skillforge.core.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeRebuildJobMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.security.TokenHash;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 原发布身份的成功回执与封存，与知识库切换在同一事务串行化。 */
@Service
public class KnowledgeRebuildPublishReceiptService {
    private final JdbcTemplate jdbc;
    private final KnowledgeRebuildService rebuilds;
    private final KnowledgeBaseRevisionMapper bases;
    private final KnowledgeRebuildJobMapper jobs;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public KnowledgeRebuildPublishReceiptService(JdbcTemplate jdbc, KnowledgeRebuildService rebuilds,
            KnowledgeBaseRevisionMapper bases, KnowledgeRebuildJobMapper jobs, CapabilityCatalogDatabaseContext context,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.rebuilds=rebuilds; this.bases=bases; this.jobs=jobs;
        this.context=context; this.json=json; this.tx=new TransactionTemplate(manager);
    }

    public record Command(UUID requestId, long kbId, long jobId, long expectedRevision,
            long expectedBaseRevision, KnowledgeCapabilityPolicyRequest policy) { }
    public record Resolution(String status, KnowledgeRebuildService.Snapshot snapshot) { }
    private record Row(long kbId, long jobId, String actor, String fingerprint, String status, String snapshot) { }

    public Resolution publish(String tenantId, String actor, Command command) {
        return change(tenantId, actor, command, false);
    }

    public Resolution fence(String tenantId, String actor, Command command) {
        return change(tenantId, actor, command, true);
    }

    private Resolution change(String tenantId, String actor, Command command, boolean fence) {
        long tenant=Long.parseLong(tenantId);
        String fingerprint=fingerprint(command);
        return tx.execute(ignored -> {
            context.bindTenant(tenant);
            // 同一主体的相同 UUID 在不同知识库也须串行；请求键锁先于 KB/job 锁。
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", tenantId+":"+actor+":"+command.requestId());
            if (bases.lockBase(tenant, command.kbId()) == null) throw missing();
            var job=jobs.lock(tenant, command.jobId());
            if (job == null || job.getKbId() != command.kbId()) throw missing();
            Row existing=find(tenant,actor,command.requestId());
            if (existing != null) {
                if (existing.kbId()!=command.kbId() || existing.jobId()!=command.jobId()
                        || !existing.actor().equals(actor) || !existing.fingerprint().equals(fingerprint))
                    throw new ApiException(HttpStatus.CONFLICT,"knowledge_rebuild_publish_request_conflict");
                return resolution(existing);
            }
            KnowledgeRebuildService.Snapshot snapshot=null;
            String status="FENCED";
            if (!fence) {
                if ("PUBLISHED".equals(job.getStatus()))
                    throw new ApiException(HttpStatus.CONFLICT,"knowledge_rebuild_already_published");
                snapshot=rebuilds.publish(tenantId,command.kbId(),command.jobId(),command.expectedRevision(),
                        command.expectedBaseRevision(),command.policy());
                status="SUCCEEDED";
            }
            jdbc.update("INSERT INTO nw_knowledge_rebuild_publish_receipts "
                    + "(tenant_id,request_id,actor_ref,kb_id,job_id,fingerprint,status,snapshot) VALUES (?,?::uuid,?,?,?,?,?,?::jsonb)",
                    tenant,command.requestId().toString(),actor,command.kbId(),command.jobId(),fingerprint,status,
                    snapshot == null ? null : encode(snapshot));
            return new Resolution(status,snapshot);
        });
    }

    public Resolution resolve(String tenantId, String actor, long kbId, long jobId, UUID requestId) {
        long tenant=Long.parseLong(tenantId);
        return tx.execute(ignored -> {
            context.bindTenant(tenant);
            Row row=find(tenant,actor,requestId);
            return row == null || row.kbId()!=kbId || row.jobId()!=jobId || !row.actor().equals(actor)
                    ? new Resolution("UNKNOWN",null) : resolution(row);
        });
    }

    private Row find(long tenant, String actor, UUID requestId) {
        var rows=jdbc.query("SELECT kb_id,job_id,actor_ref,fingerprint,status,snapshot::text FROM nw_knowledge_rebuild_publish_receipts WHERE tenant_id=? AND actor_ref=? AND request_id=?::uuid",
                (rs,n)->new Row(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),tenant,actor,requestId.toString());
        return rows.isEmpty()?null:rows.getFirst();
    }

    private Resolution resolution(Row row) {
        try {
            return new Resolution(row.status(),row.snapshot()==null?null:json.readValue(row.snapshot(),KnowledgeRebuildService.Snapshot.class));
        } catch (JsonProcessingException e) { throw new IllegalStateException("重建发布回执损坏",e); }
    }

    private String fingerprint(Command command) {
        var policy=KnowledgeBaseRevisionService.normalizePolicy(command.policy());
        return TokenHash.sha256Hex(encode(List.of(command.kbId(),command.jobId(),command.expectedRevision(),command.expectedBaseRevision(),
                policy.get("model_invocable"),policy.get("user_invocable"),policy.get("allowed_operators"),
                policy.get("freshness_policy"),policy.get("citation_required"))));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("重建发布回执序列化失败",e); }
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"knowledge_rebuild_not_found"); }
}
