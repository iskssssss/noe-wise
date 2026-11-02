package com.skillforge.institution.artifact;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 批量读取发布事实；当前权限只收窄可见性，不改写历史版本。 */
@Service
public class DeliverableReader {
    private final DeliverableMapper mapper; private final ArtifactAccess access; private final ArtifactStorage storage;
    public DeliverableReader(DeliverableMapper mapper,ArtifactAccess access,ArtifactStorage storage){this.mapper=mapper;this.access=access;this.storage=storage;}

    @Transactional(readOnly=true)
    public List<DeliverableView> list(String session,boolean history) {
        String tenant=access.tenant();
        try (var tenantScope = DeliverableTenantScope.open(tenant)) {
            access.authorizeCurrent(tenant,session);
            var sessions=new LinkedHashSet<String>();sessions.add(session);
            sessions.addAll(access.deliverableSessions(tenant,session));
            Map<String,Boolean> permitted=new HashMap<>();
            Map<String,List<ArtifactProvenance>> provenance=new HashMap<>();
            List<DeliverableView> result=new ArrayList<>();
            for(String sourceSession:sessions) {
                List<Map<String,Object>> rows=mapper.readRevisions(Long.parseLong(tenant), sourceSession);
                List<Map<String,Object>> files=mapper.readFiles(Long.parseLong(tenant), sourceSession);
                var links=mapper.readResultLinks(Long.parseLong(tenant), sourceSession);
                var evidence=mapper.readEvidence(Long.parseLong(tenant), sourceSession);
                for(var row:rows) {
                    String revision=(String)row.get("id"); boolean current=revision.equals(row.get("current_revision_id"));
                    if(!history&&!current)continue;
                    List<String> runs=links.stream().filter(l->revision.equals(l.get("revision_id"))).map(l->(String)l.get("run_id")).toList();
                    if(runs.isEmpty())continue;
                    var resultRuns=new LinkedHashSet<>(runs);
                    if(!sourceSession.equals(session)) {
                        try {
                            var route=access.deliverableRoute(tenant,sourceSession,(String)row.get("run_id"));
                            if(route.isEmpty()||!session.equals(route.orElseThrow().productSession()))continue;
                            resultRuns.add(route.orElseThrow().parentRunId());
                        } catch(com.skillforge.core.shared.exception.ApiException|SecurityException denied){continue;}
                    }
                    List<DeliverableView.File> items=new ArrayList<>(); boolean readable=true;
                    for(var file:files) if(revision.equals(file.get("revision_id"))) {
                        String artifact=(String)file.get("artifact_id");
                        if(!permitted.computeIfAbsent(artifact,id->readable(tenant,id))){readable=false;break;}
                        items.add(new DeliverableView.File((String)file.get("id"),artifact,(String)file.get("relative_path"),
                                (String)file.get("download_name"),((Number)file.get("size")).longValue(),(String)file.get("sha256"),
                                DeliverableService.extension((String)file.get("path")),(String)file.get("representation_id"),(Boolean)file.get("primary_preview"),
                                Objects.equals(file.get("entry_path"),file.get("relative_path"))));
                    }
                    if(!readable)continue;
                    // 投影已发布文件的真实来源图；不能以空列表丢弃执行时持久化的来源事实。
                    var inputs=items.stream().map(DeliverableView.File::artifactId).distinct()
                            .flatMap(id->provenance.computeIfAbsent(id,key->storage.metadata(storage.require(tenant,key)).artifactInputs()).stream())
                            .distinct().toList();
                    var proof=evidence.stream().filter(e->runs.contains(e.get("run_id")))
                            .filter(e->permitted.computeIfAbsent((String)e.get("artifact_id"),id->readable(tenant,id)))
                            .map(e->new DeliverableView.Evidence((String)e.get("artifact_id"),(String)e.get("path"),(String)e.get("summary"))).toList();
                    result.add(new DeliverableView(revision,(String)row.get("deliverable_id"),((Number)row.get("revision_no")).intValue(),current,
                            List.copyOf(resultRuns),session,(String)row.get("run_id"),"website".equals(row.get("kind"))?"website":"file",(String)row.get("title"),
                            items.stream().mapToLong(DeliverableView.File::size).sum(),(String)row.get("fingerprint"),null,"ready",items,inputs,proof));
                }
            }
            return List.copyOf(result);
        }
    }

    @Transactional(readOnly=true)
    public DeliverableView revisionCurrent(String revision) {
        String tenant=access.tenant();
        try (var tenantScope = DeliverableTenantScope.open(tenant)) {
            var sessions=mapper.revisionSessions(Long.parseLong(tenant), revision);
            if(sessions.size()!=1)throw ArtifactStorage.unavailable();
            String session=revisionProductSession(tenant,(String)sessions.getFirst().get("session_id"),revision);
            return list(session,true).stream().filter(v->v.id().equals(revision)).findFirst().orElseThrow(ArtifactStorage::unavailable);
        }
    }
    @Transactional(readOnly=true)
    public FileGrant file(String tenant,String revision,String fileId) {
        try (var tenantScope = DeliverableTenantScope.open(tenant)) {
            var rows=mapper.revisionFile(Long.parseLong(tenant), revision, fileId);
            if(rows.isEmpty())throw ArtifactStorage.unavailable();
            var row=rows.getFirst();
            ArtifactRow artifact=storage.require(tenant,(String)row.get("artifact_id"));
            String filename=(String)row.get("download_name");
            if(!revision.equals(row.get("current_revision_id")))filename=DeliverableFilename.legacyVersion(filename,((Number)row.get("revision_no")).longValue());
            String session=revisionProductSession(tenant,(String)row.get("session_id"),revision);
            return new FileGrant(revision,fileId,session,artifact,(String)row.get("path"),filename,DeliverableService.extension((String)row.get("path")),
                    (String)row.get("representation_id"),(String)row.get("entry_path"),(String)row.get("relative_path"));
        }
    }
    @Transactional(readOnly=true)
    public FileGrant resource(String tenant,String revision,String representation,String relativePath) {
        try (var tenantScope = DeliverableTenantScope.open(tenant)) {
            ArtifactStorage.safePath(relativePath);
            var rows=mapper.representationPath(Long.parseLong(tenant), representation, relativePath);
            if(rows.size()!=1)throw ArtifactStorage.unavailable();
            FileGrant result=file(tenant,revision,(String)rows.getFirst().get("file_id"));
            if(!result.representationId().equals(representation))throw ArtifactStorage.unavailable();
            return result;
        }
    }
    @Transactional(readOnly=true)
    public int representationFileCount(String tenant,String representation) {
        try (var tenantScope = DeliverableTenantScope.open(tenant)) {
            return mapper.representationFileCount(Long.parseLong(tenant), representation);
        }
    }
    private boolean readable(String tenant,String artifact) {
        try { var metadata=storage.require(tenant,artifact);access.authorizeArtifactCurrent(tenant,metadata);return true; }
        catch(com.skillforge.core.shared.exception.ApiException|SecurityException denied){return false;}
    }
    /** 发布版本自身的来源 Run 决定父会话，不能借其中某个文件的来源替换版本归属。 */
    private String revisionProductSession(String tenant,String session,String revision) {
        if(!session.startsWith("delegation:"))return session;
        var rows=mapper.readRevisions(Long.parseLong(tenant),session).stream().filter(row->revision.equals(row.get("id"))).toList();
        if(rows.size()!=1)throw ArtifactStorage.unavailable();
        return access.deliverableRoute(tenant,session,(String)rows.getFirst().get("run_id"))
                .map(StudioArtifactProjection.Route::productSession).orElseThrow(ArtifactStorage::unavailable);
    }
    public record FileGrant(String revisionId,String fileId,String sessionId,ArtifactRow artifact,String path,String downloadName,
            String format,String representationId,String entryPath,String relativePath) { }
}
