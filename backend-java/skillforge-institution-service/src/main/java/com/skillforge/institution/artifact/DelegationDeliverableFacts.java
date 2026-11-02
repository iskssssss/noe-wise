package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.port.RunIdentity;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 委派交接只读取精确子 Run 已正式发布的成果，不授予父 Agent 子会话文件读取权。 */
@Service
public class DelegationDeliverableFacts {
    private final DeliverableMapper mapper;
    private final StudioArtifactProjection projection;
    private final ArtifactStorage storage;

    public DelegationDeliverableFacts(DeliverableMapper mapper, StudioArtifactProjection projection, ArtifactStorage storage) {
        this.mapper=mapper;this.projection=projection;this.storage=storage;
    }

    /** pending 发布尚未形成交付事实，此时不能抢先冻结缺失文件的委派结果。 */
    @Transactional(readOnly=true)
    public Optional<Published> read(RunIdentity parent, RunIdentity child) {
        String tenant=parent.runRef().tenantId().value();
        if(!parent.runRef().tenantId().equals(child.runRef().tenantId())
                ||!parent.owner().requireUser().equals(child.owner().requireUser()))throw denied();
        var route=projection.route(tenant,child.owner().sessionRef().sessionId(),child.runRef().runId()).orElseThrow(DelegationDeliverableFacts::denied);
        if(!route.parentRunId().equals(parent.runRef().runId())
                ||!route.productSession().equals(parent.owner().sessionRef().sessionId())
                ||!route.sourceAgentId().equals(child.agentId().value()))throw denied();
        try(var scope=DeliverableTenantScope.open(tenant)) {
            var publication=mapper.publication(tenant,child.runRef().runId());
            if(publication!=null&&"pending".equals(publication.get("status")))return Optional.empty();
            if(publication==null||!"published".equals(publication.get("status")))return Optional.of(Published.empty());
            var revisions=new HashSet<>(mapper.revisionIds(tenant,child.runRef().runId()));
            var rows=mapper.readRevisions(Long.parseLong(tenant),child.owner().sessionRef().sessionId());
            var files=mapper.readFiles(Long.parseLong(tenant),child.owner().sessionRef().sessionId());
            var evidence=new ArrayList<String>();var names=new ArrayList<String>();
            var guard=storage.publicScope(child);
            for(var row:rows) {
                String id=(String)row.get("id");
                if(!revisions.contains(id)||!child.runRef().runId().equals(row.get("run_id")))continue;
                var selected=files.stream().filter(file->id.equals(file.get("revision_id"))).toList();
                if(selected.isEmpty())throw denied();
                var formats=new TreeSet<String>();
                for(var file:selected) {
                    var artifact=storage.require(tenant,(String)file.get("artifact_id"));
                    if(!artifact.runId().equals(child.runRef().runId())
                            ||!artifact.sessionId().equals(child.owner().sessionRef().sessionId()))throw denied();
                    var path=(String)file.get("path");
                    storage.requireFileReadable(artifact,path);
                    boolean exact=storage.metadata(artifact).files().stream().anyMatch(value->value.path().equals(path)
                            &&value.sha256().equals(file.get("sha256"))&&value.size()==((Number)file.get("size")).longValue());
                    if(!exact||!"ready".equals(file.get("status")))throw denied();
                    formats.add(DeliverableService.extension(path));
                }
                String title=(String)row.get("title");
                if(title==null||guard.rejects(title))throw denied();
                evidence.add("deliverable-revision:"+id);
                names.add(title+"（"+String.join("、",formats)+"）");
            }
            if(evidence.size()!=revisions.size())throw denied();
            if(!projection.route(tenant,child.owner().sessionRef().sessionId(),child.runRef().runId())
                    .equals(Optional.of(route)))throw denied();
            return Optional.of(new Published(List.copyOf(evidence),names.isEmpty()?"":
                    "宿主核验：本次成员 Run 的正式成果已发布，可在当前用户会话成果面板预览或下载："+String.join("；",names)+"。来源仍为成员 Run，未生成父 Run 的新版本；父 Agent 的 list_artifacts 仅查询自身会话，空清单不否定这些已发布成果。"));
        }
    }

    private static SecurityException denied(){return new SecurityException("delegation_deliverable_source_rejected");}
    public record Published(List<String> evidence,String summary) {
        public Published { evidence=List.copyOf(evidence);Objects.requireNonNull(summary); }
        static Published empty(){return new Published(List.of(),"");}
    }
}
