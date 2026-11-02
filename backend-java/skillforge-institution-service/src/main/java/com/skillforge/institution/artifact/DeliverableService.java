package com.skillforge.institution.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeGoalDriver;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.SessionGoal;
import static com.skillforge.institution.artifact.DeliverableContract.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 宿主成果事实源：冻结与公开发布分离，所有版本写入以真实Run和数据库预留为边界。 */
@Service
public class DeliverableService {
    private static final Logger log = LoggerFactory.getLogger(DeliverableService.class);
    private final ObjectMapper json;
    private final ArtifactStorage storage;
    private final ArtifactAccess access;
    private final AgentRunBindings bindings;
    private final StudioArtifactProjection projection;
    private final ExecutionStore executions;
    private final AgentRuntimeGoalDriver goals;
    private final DeliverableMapper completions;
    private final ArtifactClosingBudget closingBudget;
    private final com.skillforge.institution.runtime.InstitutionPlanHost plans;
    private final DeliverableRequirements requirements;
    private final org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeProgramTools> programs;

    public DeliverableService(ObjectMapper json, ArtifactStorage storage, ArtifactAccess access,
            AgentRunBindings bindings, StudioArtifactProjection projection, ExecutionStore executions,
            AgentRuntimeGoalDriver goals, DeliverableMapper completions, ArtifactClosingBudget closingBudget,
            com.skillforge.institution.runtime.InstitutionPlanHost plans, DeliverableRequirements requirements,
            org.springframework.beans.factory.ObjectProvider<com.skillforge.institution.runtime.AgentRuntimeProgramTools> programs) {
        this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.storage=storage; this.access=access; this.bindings=bindings; this.projection=projection;
        this.executions=Objects.requireNonNull(executions);
        this.goals=Objects.requireNonNull(goals);
        this.completions=Objects.requireNonNull(completions);
        this.closingBudget=Objects.requireNonNull(closingBudget);
        this.plans=Objects.requireNonNull(plans);
        this.requirements=Objects.requireNonNull(requirements);
        this.programs=Objects.requireNonNull(programs);
    }

    /** 本次完整交付清单的元数据与真实版本检查；只由最终冻结事务消费。 */
    private void validateManifest(Plan request, RunIdentity run) {
            require(!request.deliverables().isEmpty() && request.deliverables().size()<=64,"DELIVERABLE_MANIFEST_REQUIRED");
            // 子Run的成果先留在其私有会话，父Run选择文件后才进入用户会话的正式交付。
            String session=run.owner().sessionRef().sessionId(); Set<String> keys=new HashSet<>();
            var current = new HashMap<String, Planned>();
            currentOutputPlans(run).forEach(item -> current.put(item.key(), item));
            var corrections = new ArrayList<OutputContractRejection.Correction>();
            String rejectionCode = null;
            for (Planned item:request.deliverables()) {
                text(item.key(),160); text(item.title(),200); require(keys.add(item.key()),"DUPLICATE_DELIVERABLE_KEY");
                validateBaseRevision(item.baseRevisionId());
                require(PlatformToolCatalog.DELIVERABLE_KINDS.contains(item.kind()),"INVALID_DELIVERABLE_KIND");
                require(!item.formats().isEmpty() && item.formats().size()<=16,"OUTPUT_FORMAT_REQUIRED");
                var previous = current.get(item.key());
                String expectedBase = previous == null ? null : previous.baseRevisionId();
                // 文件 ID 不具有版本语义；反馈只使用当前会话正式版本，不采信或回显错误基准。
                if (!Objects.equals(expectedBase, item.baseRevisionId())) {
                    rejectionCode = "BASE_REVISION_CONFLICT";
                    corrections.add(new OutputContractRejection.Correction(item.key(), "base_revision_id",
                            expectedBase == null ? List.of() : List.of(expectedBase), expectedBase == null));
                }
                Set<String> formats=new HashSet<>();
                for(int formatIndex=0; formatIndex<item.formats().size(); formatIndex++) {
                    Format format=item.formats().get(formatIndex);
                    // 仅核验本次实际选择的格式，不将选择声明为用户自然语言义务。
                    require(formats.add(format.format()),"DUPLICATE_OUTPUT_FORMAT");
                    DeliverableFilename.validateFormat(format.format());
                }
            }
            if (rejectionCode != null) throw new OutputContractRejection(rejectionCode, corrections,
                    "交付尚未冻结。按当前会话正式成果修正全部所列字段后重试；omit_field=true 时省略字段。"
                            + "修改原成果保留原 key，不能通过改名新建规避版本冲突；artifact_id 不能作为 base_revision_id。"
                            + "仍不确定时调用 list_artifacts 查询当前正式版本；实际文件及版本由宿主核验。");
    }

    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> finalizeOutputs(Finalize request, PlatformToolExecutionContext context) throws Exception {
        RunIdentity run=context.run(); control(context);
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            active(run);
            // 已派发模型和当前工具由原账本计量；直接 finalization 仍核验本受理点尚需的保护。
            closingBudget.admitFinalization(run);
            var row=planRow(run,true);
            // planned 只存在于下方同一事务；不采用旧入口独立登记的模型清单作为可信需求。
            require(row==null || !"planned".equals(row.get("state")),"OUTPUT_FINALIZATION_STATE_INVALID");
            if (row == null) {
                boolean abandoning=request.abandonReason()!=null && !request.abandonReason().isBlank();
                if (!abandoning) {
                    require(!request.deliverables().isEmpty() && request.deliverables().size()<=64,"DELIVERABLE_MANIFEST_REQUIRED");
                    for (var selection : request.deliverables()) {
                        text(selection.key(),160); text(selection.title(),200);
                        require(selection.kind()!=null && PlatformToolCatalog.DELIVERABLE_KINDS.contains(selection.kind()),"INVALID_DELIVERABLE_KIND");
                        validateBaseRevision(selection.baseRevisionId());
                    }
                }
                var manifest = abandoning ? new Plan(List.of()) : new Plan(requirements.verifyForFinalization(verifiedSelections(run,request.deliverables())));
                if (!abandoning) {
                    plans.requireFinalizationReady(run);
                    validateManifest(manifest,run);
                }
                completions.insertPlan(tenant(run),run.runRef().runId(),run.owner().sessionRef().sessionId(),
                        run.owner().sessionRef().sessionId(),write(run),write(manifest));
                row=planRow(run,true);
                require(row!=null,"OUTPUT_FINALIZATION_CONFLICT");
            }
            require(request.abandonReason()==null || request.goalCompletion()==null,"ABANDONMENT_CANNOT_COMPLETE_GOAL");
            if(!"planned".equals(row.get("state"))) {
                require("finalizing".equals(row.get("state")) || "finalized".equals(row.get("state")),"OUTPUT_PLAN_ABANDONED");
                // 重放比较语义输入，不把新调用身份当作新的交付。
                require(Objects.equals(row.get("frozen_hash"),hash(write(request))),"OUTPUT_FINALIZATION_CONFLICT");
                return Map.of("success",true,"state",row.get("state"),"result_ref",resultRef(run),
                        "completion_pending","pending".equals(row.get("goal_completion_state")));
            }
            if(request.abandonReason()!=null && !request.abandonReason().isBlank()) {
                text(request.abandonReason(),2000); require(request.deliverables().isEmpty(),"ABANDONMENT_CANNOT_PUBLISH_FILES");
                completions.abandonPlan(request.abandonReason(), tenant(run), run.runRef().runId());
                completions.insertFailedResult(tenant(run), run.runRef().runId(), (String) row.get("session_id"), request.abandonReason());
                return Map.of("success",true,"state","abandoned","reason",request.abandonReason());
            }
            plans.requireFinalizationReady(run);
            Plan plan=read(row.get("plan"),Plan.class);
            var publicScope=storage.publicScope(run);
            for (Selection item : request.deliverables()) {
                text(item.key(),160);
            }
            if (request.deliverables().size()!=plan.deliverables().size())
                throw new OutputContractRejection("REQUIRED_DELIVERABLE_MISSING", List.of(
                        new OutputContractRejection.Correction("", "deliverables[].key",
                                plan.deliverables().stream().map(Planned::key).toList(), false)));
            List<FrozenItem> selected=new ArrayList<>(); Set<String> selectedKeys=new HashSet<>();
            List<Selection> ordered=request.deliverables().stream().sorted(Comparator.comparing(Selection::key)).toList();
            for(Selection item:ordered) {
                require(selectedKeys.add(item.key()),"DUPLICATE_DELIVERABLE_KEY");
                Planned expected=plan.deliverables().stream().filter(p->p.key().equals(item.key())).findFirst()
                        .orElseThrow(()->new OutputContractRejection("UNPLANNED_DELIVERABLE", List.of(
                                new OutputContractRejection.Correction("", "key",
                                        plan.deliverables().stream().map(Planned::key).toList(), false))));
                require(!publicScope.rejects(expected.title()),PublicOutputGuard.REJECTION_CODE);
                var d=reserve(run,(String)row.get("session_id"),expected);
                require(!item.representations().isEmpty() && item.representations().stream().filter(Representation::primaryPreview).count()==1,"PRIMARY_PREVIEW_REQUIRED");
                Set<String> actualFormats=new HashSet<>(); List<FrozenRepresentation> representations=new ArrayList<>();
                Set<String> downloadNames=new HashSet<>();
                for(Representation representation:item.representations()) {
                    require(Set.of("source","export","bundle").contains(representation.role()),"INVALID_REPRESENTATION_ROLE");
                    Format format=expected.formats().stream().filter(f->f.format().equals(representation.format())).findFirst()
                            .orElseThrow(()->new OutputContractRejection("UNPLANNED_OUTPUT_FORMAT", List.of(
                                    new OutputContractRejection.Correction(expected.key(), "representations[].format",
                                            expected.formats().stream().map(Format::format).toList(), false))));
                    require(actualFormats.add(format.format()),"DUPLICATE_REPRESENTATION_FORMAT");
                    require(!representation.files().isEmpty(),"OUTPUT_FILE_REQUIRED");
                    require("bundle".equals(representation.role()) == "zip".equals(format.format()),"BUNDLE_FORMAT_INVALID");
                    List<FrozenFile> files=new ArrayList<>(); Set<String> paths=new HashSet<>();
                    for(FileRef reference:representation.files()) {
                        FrozenFile file=verify(run,reference);
                        storage.inspectPublicFile(context,storage.require(Long.toString(tenant(run)),file.artifactId()),file.path(),publicScope);
                        String canonical=java.text.Normalizer.normalize(file.path(),java.text.Normalizer.Form.NFKC);
                        require(canonical.length()<=4096 && canonical.equals(file.path()),"OUTPUT_PATH_NOT_CANONICAL");
                        for(String segment:canonical.split("/"))DeliverableFilename.validateFilename(segment);
                        require(paths.stream().noneMatch(p->p.equalsIgnoreCase(canonical))&&paths.add(canonical),"DUPLICATE_OUTPUT_PATH");
                        String fileFormat=extension(file.path());
                        validateFileContent(run,file,fileFormat);
                        if(representation.files().size()==1) require(fileFormat.equals(format.format()),"OUTPUT_FORMAT_MISMATCH");
                        String name=DeliverableFilename.validateFilename(basename(file.path()));
                        name=DeliverableFilename.deduplicate(name,downloadNames); downloadNames.add(name);
                        files.add(new FrozenFile(file.fileId(),file.artifactId(),file.path(),name,file.size(),file.sha256()));
                        hold(run,file);
                    }
                    if("html".equals(format.format())) {
                        require(representation.entryPath()!=null && paths.contains(representation.entryPath()),"WEBSITE_ENTRY_REQUIRED");
                        var entry=files.stream().filter(f->f.path().equals(representation.entryPath())).findFirst().orElseThrow();
                        var bytes=storage.verifiedFile(storage.require(Long.toString(tenant(run)),entry.artifactId()),entry.path(),12*1024*1024).content();
                        require(bytes!=null,"HTML_ENTRY_TOO_LARGE");
                        String html=new String(bytes,StandardCharsets.UTF_8);
                        validateHtml(html,files.size()==1);
                        validateWebsiteResources(run,html,entry.path(),files,paths);
                    }
                    List<FrozenFile> sources=new ArrayList<>();
                    for(FileRef reference:representation.sourceFiles()) {
                        var source=verify(run,reference);
                        storage.inspectPublicFile(context,storage.require(Long.toString(tenant(run)),source.artifactId()),source.path(),publicScope);
                        sources.add(source); hold(run,source);
                    }
                    require(!"export".equals(representation.role()) || !sources.isEmpty(),"EXPORT_SOURCE_REQUIRED");
                    for(FrozenFile file:files) for(FrozenFile source:sources) {
                        boolean linked=storage.metadata(storage.require(Long.toString(tenant(run)),file.artifactId())).artifactInputs().stream()
                                .anyMatch(i->i.artifactId().equals(source.artifactId()) && i.path().equals(source.path()) && i.sha256().equals(source.sha256()));
                        require(linked,"EXPORT_SOURCE_NOT_IN_EXECUTION");
                    }
                    representations.add(new FrozenRepresentation(representation.role(),format.format(),representation.primaryPreview(),representation.entryPath(),sources.isEmpty()?null:hash(write(sources.stream().map(f->List.of(f.path(),f.sha256())).sorted(Comparator.comparing(Object::toString)).toList())),files,sources));
                }
                for(Format f:expected.formats()) require(actualFormats.contains(f.format()),"FROZEN_FORMAT_MISSING:"+f.format());
                validateSources(representations);
                String fingerprint=hash(write(List.of(expected.title(),expected.kind(),representations.stream().map(r->Arrays.asList(r.role(),r.format(),r.primaryPreview(),r.files().size()==1?null:r.entryPath(),r.sourceHash(),r.files().stream().map(f->List.of(r.files().size()==1?f.downloadName():f.path(),f.downloadName(),f.sha256())).toList())).toList())));
                selected.add(new FrozenItem((String)d.get("id"),item.key(),expected.title(),expected.kind(),expected.baseRevisionId(),fingerprint,representations));
            }
            List<FrozenEvidence> evidence=new ArrayList<>();
            for(Evidence e:request.evidence()) {
                text(e.summary(),2000); require(!publicScope.rejects(e.summary()),PublicOutputGuard.REJECTION_CODE);
                var file=verify(run,new FileRef(e.artifactId(),e.path()));
                storage.inspectPublicFile(context,storage.require(Long.toString(tenant(run)),file.artifactId()),file.path(),publicScope);
                hold(run,file); evidence.add(new FrozenEvidence(file,e.summary()));
            }
            control(context); active(run);
            GoalCompletionIntent completion=request.goalCompletion()==null?null:
                    goals.captureCompletion(run,request.goalCompletion().expectedRevision());
            require(completions.freeze(run.runRef().tenantId().value(),run.runRef().runId(),write(new Frozen(selected,evidence,completion)),
                    hash(write(request)),completion==null?null:"pending")==1,"OUTPUT_FINALIZATION_CONFLICT");
            completions.insertPendingResult(tenant(run), run.runRef().runId(), (String) row.get("session_id"));
            return Map.of("success",true,"state","finalizing","result_ref",resultRef(run),"completion_pending",completion!=null,"instruction",com.skillforge.prompts.OutputPrompts.finalized(),"files",selected.stream().flatMap(i->i.representations().stream()).flatMap(r->r.files().stream()).map(FrozenFile::downloadName).toList());
        }
    }

    private static void validateBaseRevision(String baseRevision) {
        require(baseRevision == null || !baseRevision.isBlank(), "BASE_REVISION_ID_MUST_NOT_BE_BLANK");
    }

    /** 单个计划事务内先锁成果再锁文件；清理器对同一文件行复核引用，不能与预留竞争删除。 */
    private Map<String,Object> reserve(RunIdentity run,String session,Planned item) {
        completions.ensureDeliverable(tenant(run), UUID.randomUUID().toString(), session, item.key());
        var d=completions.lockDeliverableKey(tenant(run), session, item.key());
        require(Objects.equals(d.get("current_revision_id"),item.baseRevisionId()),"BASE_REVISION_CONFLICT");
        require(d.get("reserved_run_id")==null || d.get("reserved_run_id").equals(run.runRef().runId()),"DELIVERABLE_RESERVED");
        completions.reserveDeliverable(run.runRef().runId(), tenant(run), (String) d.get("id"));
        return d;
    }

    private void validateSources(List<FrozenRepresentation> representations) {
        List<FrozenFile> newSources=representations.stream().filter(r->r.role().equals("source")).flatMap(r->r.files().stream()).toList();
        for(var r:representations) if(r.role().equals("export")) for(var source:r.sources()) {
            var replacement=newSources.stream().filter(f->extension(f.path()).equals(extension(source.path()))).toList();
            if(!replacement.isEmpty()) require(replacement.stream().anyMatch(f->f.fileId().equals(source.fileId())),"STALE_EXPORT_SOURCE");
        }
    }

    /** 文件名义务仅采用宿主查到的受管路径，模型传入的file_id/path不能自证。 */
    private List<Selection> verifiedSelections(RunIdentity run,List<Selection> selections) throws java.io.IOException {
        var result=new ArrayList<Selection>();
        for(var item:selections) {
            var representations=new ArrayList<Representation>();
            for(var representation:item.representations()) {
                var files=new ArrayList<FileRef>();
                for(var reference:representation.files()) {
                    var file=verify(run,reference);files.add(new FileRef(file.artifactId(),file.path(),file.fileId()));
                }
                representations.add(new Representation(representation.role(),representation.format(),representation.primaryPreview(),representation.entryPath(),files,representation.sourceFiles()));
            }
            result.add(new Selection(item.key(),item.title(),item.kind(),item.baseRevisionId(),representations));
        }
        return List.copyOf(result);
    }

    FrozenFile verify(RunIdentity run,FileRef reference) throws java.io.IOException {
        require(reference!=null,"FILE_REFERENCE_REQUIRED");
        Map<String,Object> row;
        if(reference.fileId()!=null) row=completions.lockFileId(tenant(run), reference.fileId());
        else row=completions.lockFilePath(tenant(run), reference.artifactId(), reference.path());
        require(row != null, "OUTPUT_FILE_NOT_FOUND");
        require("ready".equals(row.get("status")),"OUTPUT_FILE_EXPIRED");
        String artifact=(String)row.get("artifact_id"),path=(String)row.get("path");
        ArtifactRow metadata=storage.require(Long.toString(tenant(run)),artifact);
        String origin=metadata.sessionId();
        if(!origin.equals(run.owner().sessionRef().sessionId()) && !origin.equals(productSession(run))) {
            var route=projection.route(Long.toString(tenant(run)),origin,metadata.runId());
            require(route.isPresent() && route.get().productSession().equals(productSession(run)),"OUTPUT_FILE_SCOPE_DENIED");
        }
        var verified=storage.verifiedFile(metadata,path,0).provenance();
        return new FrozenFile((String)row.get("id"),artifact,path,path,verified.size(),verified.sha256());
    }

    private void hold(RunIdentity run,FrozenFile file) {
        completions.holdFile(tenant(run), run.runRef().runId(), file.fileId());
    }

    /** 仅采用真实Run终态；同事务写完所有成果再公开结果。调用重放不会再分配版本号。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reconcile(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            var plan=planRow(run,true); if(plan==null || Set.of("finalized","abandoned").contains(plan.get("state")))return;
            var actual=actualRun(run);
            if(!Set.of(RunStatus.COMPLETED,RunStatus.FAILED,RunStatus.CANCELLED,RunStatus.TERMINATED).contains(actual.status()))return;
            if(actual.status()!=RunStatus.COMPLETED || !"finalizing".equals(plan.get("state"))) {
                String state=actual.status()==RunStatus.CANCELLED?"cancelled":"failed";
                completions.upsertTerminalResult(tenant(run), run.runRef().runId(), (String) plan.get("session_id"), state, "run_"+actual.status().name().toLowerCase(Locale.ROOT));
                completions.abandonTerminalPlan(tenant(run), run.runRef().runId());
                release(run); return;
            }
            var resultPresent=executions.event(run.runRef(),AgentExecutionProgram.finishedEventId(run.runRef()))
                    .filter(event->completedReference(actual,event,resultRef(run),json)).isPresent();
            if(!resultPresent){failPublication(run,"completed_result_reference_missing");return;}
            Frozen frozen=read(plan.get("frozen"),Frozen.class);
            try {
                var binding=resourceBinding(run);bindings.authorizeRead(binding,binding.actor().value());
                var files=new LinkedHashMap<String,FrozenFile>();
                for(var item:frozen.deliverables())for(var rep:item.representations()) {
                    for(var file:rep.files())files.put(file.fileId(),file);
                    for(var file:rep.sources())files.put(file.fileId(),file);
                }
                for(var proof:frozen.evidence())files.put(proof.file().fileId(),proof.file());
                for(var file:files.values()) {
                    var current=verify(run,new FileRef(file.artifactId(),file.path(),file.fileId()));
                    require(current.sha256().equals(file.sha256())&&current.size()==file.size(),"FROZEN_FILE_CHANGED");
                }
            } catch(IllegalArgumentException|SecurityException|com.skillforge.core.shared.exception.ApiException|java.io.IOException rejected) {
                completions.failValidationResult(tenant(run), run.runRef().runId());
                completions.abandonValidationPlan(tenant(run), run.runRef().runId());
                release(run); return;
            }
            for(FrozenItem item:frozen.deliverables()) {
                var reservation=completions.lockReservation(tenant(run), item.deliverableId());
                if(reservation == null || !run.runRef().runId().equals(reservation.get("reserved_run_id")) || !Objects.equals(item.baseRevisionId(),reservation.get("current_revision_id"))) {
                    failPublication(run,"publication_reservation_lost");return;
                }
            }
            int ordinal=0;
            for(FrozenItem item:frozen.deliverables()) {
                var d=completions.lockDeliverableId(tenant(run), item.deliverableId());
                require(run.runRef().runId().equals(d.get("reserved_run_id")) && Objects.equals(item.baseRevisionId(),d.get("current_revision_id")),"PUBLICATION_RESERVATION_LOST");
                Map<String,Object> previous=item.baseRevisionId()==null?null:completions.revision(tenant(run), item.baseRevisionId());
                String revision;
                if(previous!=null && item.fingerprint().equals(previous.get("fingerprint"))) revision=item.baseRevisionId();
                else {
                    revision=UUID.randomUUID().toString(); int number=previous==null?1:((Number)previous.get("revision_no")).intValue()+1;
                    completions.insertRevision(tenant(run), revision, item.deliverableId(), number, run.runRef().runId(), item.baseRevisionId(), item.title(), item.kind(), item.fingerprint(), previous==null?"CREATED":"UPDATED");
                    for(FrozenRepresentation rep:item.representations()) {
                        String id=UUID.randomUUID().toString();
                        completions.insertRepresentation(tenant(run), id, revision, rep.role(), rep.format(), rep.primaryPreview(), rep.entryPath(), rep.sourceHash());
                        int index=0; for(FrozenFile file:rep.files()) completions.insertRepresentationFile(tenant(run), id, file.fileId(), file.path(), file.downloadName(), index++);
                        for(FrozenFile source:rep.sources()) completions.insertRepresentationSource(tenant(run), id, source.fileId(), source.sha256());
                    }
                    completions.advanceRevision(revision, tenant(run), item.deliverableId());
                }
                completions.linkResultRevision(tenant(run), run.runRef().runId(), revision, ordinal++);
            }
            for(FrozenEvidence evidence:frozen.evidence()) completions.insertEvidence(tenant(run), UUID.randomUUID().toString(), run.runRef().runId(), evidence.file().fileId(), evidence.summary());
            log.debug("成果版本写入完成 tenant={} deliverableCount={} evidenceCount={}", tenant(run), frozen.deliverables().size(), frozen.evidence().size());
            completions.publishResult(tenant(run), run.runRef().runId());
            completions.finalizePlan(tenant(run), run.runRef().runId());
            release(run);
            log.info("成果发布完成 tenant={} deliverableCount={}", tenant(run), frozen.deliverables().size());
        }
    }

    @Transactional(readOnly=true)
    public String actionRejection(RunIdentity run,String action) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            var row=planRow(run,false);
            if(row!=null && Set.of("finalizing","finalized","abandoned").contains(row.get("state"))) return "respond".equals(action)?null:"TASK_OUTPUTS_FROZEN";
            if("respond".equals(action) && row!=null) return "TASK_OUTPUT_RESOLUTION_REQUIRED";
            return null;
        }
    }

    /** 本次MODEL只读取真实成果状态；候选和自然语言不产生宿主完成门禁。 */
    public record OutputPreparation(Optional<String> state,List<Planned> plans,Optional<String> preparedResult,boolean hasOutputs) {
        public OutputPreparation { Objects.requireNonNull(state);plans=List.copyOf(plans);Objects.requireNonNull(preparedResult); }
    }

    public OutputPreparation prepareOutput(RunIdentity run) {
        try(var tenantScope=DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            var row=planRow(run,false);
            var state=row==null ? Optional.<String>empty() : Optional.of((String)row.get("state"));
            var result=state.filter(value->Set.of("finalizing","finalized","abandoned").contains(value)).map(value->resultRef(run));
            return new OutputPreparation(state,currentOutputPlans(run),result,row!=null);
        }
    }

    @Transactional(readOnly=true)
    public Optional<String> preparedResult(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            var row=planRow(run,false);
            return row!=null && Set.of("finalizing","finalized","abandoned").contains(row.get("state"))?Optional.of(resultRef(run)):Optional.empty();
        }
    }
    /** 仅真实成果提交状态影响Goal完成；候选文件不构成交付义务。 */
    @Transactional(readOnly=true)
    public boolean hasOutputs(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            return planRow(run,false)!=null;
        }
    }

    @Transactional(readOnly=true)
    public boolean completionPending(RunRef run) {
        try (var tenantScope = DeliverableTenantScope.open(run.tenantId().value())) {
            return completions.completionPending(run.tenantId().value(),run.runId());
        }
    }

    /** 精确绑定本 Goal 最后接受的 Run；不从会话成果列表推断发布成功。 */
    @Transactional(readOnly=true)
    public CompletionView completionView(SessionOwner owner,SessionGoal.Snapshot goal) {
        try (var tenantScope = DeliverableTenantScope.open(owner.sessionRef().tenantId().value())) {
            var run=goal.lastRunId().orElseThrow();
            var row=completions.completionPlan(run.tenantId().value(),run.runId(),false);
            if(row==null || row.get("frozen")==null)return new CompletionView(false,null);
            var identity=persistedPlanIdentity(run,row);
            require(identity.owner().equals(owner) && identity.runRef().equals(run),"OUTPUT_RUN_OWNER_MISMATCH");
            var intent=read(row.get("frozen"),Frozen.class).goalCompletion();
            if(intent==null || !intent.goalId().equals(goal.goalId()))return new CompletionView(false,null);
            boolean pending="pending".equals(row.get("goal_completion_state"))
                    && goal.state()==SessionGoal.State.ACTIVE && goal.revision()==intent.expectedRevision();
            return new CompletionView(pending,publication(identity));
        }
    }

    @Transactional(readOnly=true)
    public Optional<GoalCompletion> pendingCompletion(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            var row=completions.completionPlan(run.runRef().tenantId().value(),run.runRef().runId(),false);
            if(row==null || !"pending".equals(row.get("goal_completion_state")))return Optional.empty();
            var identity=persistedPlanIdentity(run.runRef(),row);
            require(identity.equals(run),"OUTPUT_RUN_OWNER_MISMATCH");
            var intent=read(row.get("frozen"),Frozen.class).goalCompletion();
            require(intent!=null,"GOAL_COMPLETION_INTENT_MISSING");
            var actual=actualRun(run);
            return Optional.of(new GoalCompletion(run,intent,actual.status(),publication(run)));
        }
    }

    /** 独立提交处理标记；不能与 Goal CAS 或成果发布放进同一个伪原子事务。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void markCompletion(RunIdentity run,GoalCompletionIntent intent,
            AgentRuntimeGoalDriver.CompletionSettlement settlement) {
        if(settlement==AgentRuntimeGoalDriver.CompletionSettlement.WAITING)return;
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            var row=completions.completionPlan(run.runRef().tenantId().value(),run.runRef().runId(),true);
            if(row==null || !"pending".equals(row.get("goal_completion_state")))return;
            verifyPlanIdentity(run,row);
            require(Objects.equals(read(row.get("frozen"),Frozen.class).goalCompletion(),intent),"GOAL_COMPLETION_INTENT_CONFLICT");
            completions.markCompletion(run.runRef().tenantId().value(),run.runRef().runId(),
                    settlement==AgentRuntimeGoalDriver.CompletionSettlement.SETTLED?"settled":"superseded");
            // 跨 Run 来源文件依赖 holds 保留；结算标记和释放必须同事务。发布尚未结束时继续保留。
            completions.releaseHolds(run.runRef().tenantId().value(),run.runRef().runId(),true);
        }
    }

    private Publication publication(RunIdentity run) {
        var result=completions.publication(run.runRef().tenantId().value(),run.runRef().runId());
        require(result!=null,"TASK_RESULT_MISSING");
        List<String> revisions=completions.revisionIds(run.runRef().tenantId().value(),run.runRef().runId()).stream()
                .map(id->"deliverable:"+id).toList();
        return new Publication(run.runRef().runId(),resultRef(run),(String)result.get("status"),(String)result.get("reason"),revisions);
    }

    /** 公开正文只采用本 Run 结果的正式结算；候选 ready 和 Run COMPLETED 均不能代替发布。 */
    public enum PublicationExposure {
        /** 尚未取得本轮结果的正式发布终态，公开出口须稍后重试。 */
        PENDING,
        /** 本轮结果已经正式发布，可采纳原始答复。 */
        PUBLISHED,
        /** 冻结前显式放弃，仍属未交付；保留经公开校验的进度答复，不能展示为发布成功。 */
        ABANDONED,
        /** 发布确定失败或取消，只能呈现宿主失败说明。 */
        FAILED
    }

    /** 可信来源 owner、Run 与结果引用必须和原计划身份一致；查询不改变原始 Runtime 事实。 */
    @Transactional(readOnly=true)
    public PublicationExposure publicationExposure(SessionOwner owner, RunRef run, String reference) {
        Objects.requireNonNull(owner); Objects.requireNonNull(run);
        require(owner.sessionRef().tenantId().equals(run.tenantId())
                && ("task-result:" + run.runId()).equals(reference), "OUTPUT_RUN_OWNER_MISMATCH");
        try (var tenantScope = DeliverableTenantScope.open(run.tenantId().value())) {
            var plan = completions.completionPlan(run.tenantId().value(), run.runId(), false);
            if (plan == null) return PublicationExposure.PENDING;
            var identity = persistedPlanIdentity(run,plan);
            require(identity.owner().equals(owner) && identity.runRef().equals(run), "OUTPUT_RUN_OWNER_MISMATCH");
            var result = completions.publication(run.tenantId().value(), run.runId());
            if (result == null) return PublicationExposure.PENDING;
            return switch (Objects.toString(result.get("status"), "")) {
                case "published" -> PublicationExposure.PUBLISHED;
                case "failed" -> "abandoned".equals(plan.get("state")) && plan.get("frozen") == null
                        && plan.get("abandonment_reason") instanceof String reason && !reason.isBlank()
                        && reason.equals(result.get("reason")) ? PublicationExposure.ABANDONED : PublicationExposure.FAILED;
                case "cancelled" -> PublicationExposure.FAILED;
                default -> PublicationExposure.PENDING;
            };
        }
    }
    @Transactional
    public void admitCollection(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            active(run);
            var row=planRow(run,false);
            require(row==null,"TASK_OUTPUTS_FROZEN");
            closingBudget.admit(run);
        }
    }
    /** 读取当前 Run 的持久计划状态；空值仅表示本轮尚未建计划，不从会话历史版本推断。 */
    @Transactional(readOnly=true)
    public Optional<String> currentOutputPlanState(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            var row = planRow(run, false);
            return row == null ? Optional.empty() : Optional.of((String) row.get("state"));
        }
    }

    /** 只从正式版本读取本轮改版基准，不推断自然语言要求。 */
    @Transactional(readOnly=true)
    public List<Planned> currentOutputPlans(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            return completions.currentPlans(tenant(run), run.owner().sessionRef().sessionId()).stream().map(row -> {
                var previous = read(row.plan(), Plan.class).deliverables().stream()
                        .filter(item -> item.key().equals(row.key())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("PUBLISHED_OUTPUT_PLAN_MISSING"));
                var formats = previous.formats();
                return new Planned(row.key(), row.title(), row.kind(), row.baseRevisionId(), formats);
            }).toList();
        }
    }

    /** 文件引用和基准版本分字段返回，格式只描述已发布表示。 */
    @Transactional(readOnly=true)
    public List<Map<String,Object>> currentOutputs(RunIdentity run) {
        try (var tenantScope = DeliverableTenantScope.open(run.runRef().tenantId().value())) {
            return completions.currentOutputs(tenant(run), run.owner().sessionRef().sessionId());
        }
    }
    @Transactional(readOnly=true)
    public List<RunIdentity> pending(String tenant) {
        return pending(tenant,100);
    }

    /** 只协调已提交终态的原任务；按引用从唯一Store恢复精确身份，不反序列化Runtime接口快照。 */
    @Transactional(readOnly=true)
    public List<RunIdentity> pending(String tenant,int limit) {
        require(limit>0,"OUTPUT_RECOVERY_LIMIT_INVALID");
        try (var tenantScope = DeliverableTenantScope.open(tenant)) {
            return completions.pendingRuns(tenant,limit).stream().map(id->{
                var reference=new RunRef(new com.skillforge.runtime.api.TenantId(tenant),id);
                var plan=completions.completionPlan(tenant,id,false);
                require(plan!=null,"OUTPUT_PLAN_MISSING");
                return persistedPlanIdentity(reference,plan);
            }).toList();
        }
    }

    /** 引用解析使用Store；快照只作不可变一致性校验，不为sealed接口猜测实现类型。 */
    private RunIdentity persistedPlanIdentity(RunRef reference,Map<String,Object> plan) {
        var actual=executions.load(reference).orElseThrow(()->rejected("RUN_FACT_MISSING"));
        var identity=actual.identity();
        require(identity.runRef().equals(reference),"OUTPUT_RUN_OWNER_MISMATCH");
        verifyPlanIdentity(identity,plan);
        return identity;
    }

    private void verifyPlanIdentity(RunIdentity identity,Map<String,Object> plan) {
        require(identity.runRef().tenantId().value().equals(Objects.toString(plan.get("tenant_id"),null))
                && identity.runRef().runId().equals(plan.get("run_id"))
                && identity.owner().sessionRef().sessionId().equals(plan.get("session_id"))
                && identity.owner().sessionRef().sessionId().equals(plan.get("owner_session_id")),"OUTPUT_RUN_OWNER_MISMATCH");
        try {
            // 两侧均按JSON文本解析，避免小long在内存是LongNode、入库后解析为IntNode的表示差异。
            // 整数仍按完整精度比较，不能经double或宽松数值转换抹去身份变化。
            require(json.readTree(write(identity)).equals(json.readTree(Objects.toString(plan.get("run_identity"),"null"))),"OUTPUT_RUN_OWNER_MISMATCH");
        } catch(java.io.IOException invalid) { throw new IllegalStateException("output_record_corrupt",invalid); }
    }
    private void failPublication(RunIdentity run,String reason) {
        completions.failResult(reason, tenant(run), run.runRef().runId());
        completions.abandonFailedPlan(reason, tenant(run), run.runRef().runId());
        release(run);
    }
    private void release(RunIdentity run) {
        completions.releaseHolds(run.runRef().tenantId().value(),run.runRef().runId(),false);
        completions.releaseReservations(tenant(run), run.runRef().runId());
    }
    private void active(RunIdentity run) {
        var actual=actualRun(run);
        require(actual.status()==RunStatus.RUNNING && actual.identity().equals(run),"RUN_NOT_ACTIVE");
        bindings.authorize(resourceBinding(run));
    }

    /** Program子动作保留实际Run归属；当前资源权限必须由已验证的原父binding授予。 */
    private AgentRunBindings.Binding resourceBinding(RunIdentity run) {
        var origin=programs.getObject().resourceOrigin(run);
        var binding=bindings.find(origin.runRef()).orElseThrow(()->new SecurityException("OUTPUT_RUN_BINDING_MISSING"));
        if(!binding.owner().equals(origin.owner())||!binding.actor().equals(origin.agentId()))
            throw new SecurityException("OUTPUT_RUN_OWNER_MISMATCH");
        return binding;
    }

    /** 新Store是唯一Run事实源；带租户的引用仍必须核对完整不可变归属与因果身份。 */
    private ExecutionStore.Run actualRun(RunIdentity run) {
        var actual=executions.load(run.runRef()).orElseThrow(()->rejected("RUN_FACT_MISSING"));
        require(actual.identity().equals(run),"OUTPUT_RUN_OWNER_MISMATCH");return actual;
    }

    /**
     * 完成证明只认与FINISHED版本同事务提交的独立成果引用。
     * MODEL结果引用、其他Run、过期完成事件和外部声明均不能替代该事实。
     */
    static boolean completedReference(ExecutionStore.Run run,ExecutionStore.SavedEvent saved,String expected,ObjectMapper json) {
        if(run.status()!=RunStatus.COMPLETED || run.phase()!=ExecutionStore.Phase.FINISHED
                ||!saved.run().equals(run.identity().runRef()) ||saved.revision()!=run.revision()
                ||!saved.event().id().equals(AgentExecutionProgram.finishedEventId(run.identity().runRef()))
                ||!AgentExecutionProgram.COMPLETED_EVENT.equals(saved.event().type())
                ||!AgentExecutionProgram.COMPLETED_SCHEMA.equals(saved.event().data().schemaId()))return false;
        try {
            var value=json.readTree(saved.event().data().canonicalJson());
            return value!=null && value.isObject() && value.path("status").isTextual()
                    && "COMPLETED".equals(value.path("status").textValue())
                    && value.path("taskResultRef").isTextual() && expected.equals(value.path("taskResultRef").textValue());
        }catch(java.io.IOException invalid) { throw new IllegalStateException("completed_result_proof_invalid",invalid); }
    }
    private String productSession(RunIdentity run) {
        var binding=resourceBinding(run);
        if(binding.parentRunRef().isPresent()) return bindings.find(binding.scope().primaryRun()).orElseThrow().owner().sessionRef().sessionId();
        return binding.owner().sessionRef().sessionId();
    }
    private Map<String,Object> planRow(RunIdentity run,boolean lock) {
        var rows=completions.planRows(tenant(run), run.runRef().runId(), lock);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();verifyPlanIdentity(run,row);return row;
    }
    private void validateFileContent(RunIdentity run,FrozenFile file,String format) throws java.io.IOException {
        if(!Set.of("md","txt","csv","json","yaml","yml","html","svg","pdf","docx","xlsx","pptx","zip").contains(format))return;
        byte[] bytes=storage.verifiedFile(storage.require(Long.toString(tenant(run)),file.artifactId()),file.path(),100*1024*1024).content();
        require(bytes!=null,"OUTPUT_FILE_TOO_LARGE");
        if("pdf".equals(format)) {
            require(bytes.length>=5 && "%PDF-".equals(new String(bytes,0,5,StandardCharsets.US_ASCII)),"OUTPUT_CONTENT_FORMAT_MISMATCH");return;
        }
        if("zip".equals(format)) { DeliverableBundlePolicy.validate(bytes); return; }
        if(Set.of("docx","xlsx","pptx").contains(format)) {
            String main=switch(format){case "docx"->"word/document.xml";case "xlsx"->"xl/workbook.xml";default->"ppt/presentation.xml";};
            boolean types=false,body=false;long total=0;int count=0;
            try(var archive=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                java.util.zip.ZipEntry entry; byte[] buffer=new byte[8192];
                while((entry=archive.getNextEntry())!=null) {
                    require(++count<=4096,"OFFICE_ARCHIVE_LIMIT");
                    ArtifactStorage.safePath(entry.isDirectory()?entry.getName().replaceAll("/$",""):entry.getName());
                    types|="[Content_Types].xml".equals(entry.getName());body|=main.equals(entry.getName());
                    int n;while((n=archive.read(buffer))!=-1){total+=n;require(total<=100L*1024*1024 && total<=Math.max(1,bytes.length)*100L,"OFFICE_ARCHIVE_LIMIT");}
                }
            }
            require(types&&body,"OUTPUT_CONTENT_FORMAT_MISMATCH");return;
        }
        String text;
        try {text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw rejected("OUTPUT_TEXT_ENCODING_INVALID");}
        require(text.indexOf('\0')<0,"OUTPUT_TEXT_ENCODING_INVALID");
        if("svg".equals(format))validateHtml(text,true);
    }
    private void validateWebsiteResources(RunIdentity run, String entryHtml, String entry, List<FrozenFile> files,
            Set<String> paths) throws java.io.IOException {
        for (var file : files) {
            String format = extension(file.path());
            if (!Set.of("html", "htm", "css").contains(format)) continue;
            String text;
            if (file.path().equals(entry)) text = entryHtml;
            else {
                byte[] bytes = storage.verifiedFile(storage.require(Long.toString(tenant(run)), file.artifactId()),
                        file.path(), 12 * 1024 * 1024).content();
                require(bytes != null, "WEBSITE_RESOURCE_TOO_LARGE");
                try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
                catch (java.nio.charset.CharacterCodingException invalid) { throw rejected("OUTPUT_TEXT_ENCODING_INVALID"); }
            }
            if ("css".equals(format)) {
                for (String reference : DeliverablePreviewPolicy.cssResourceReferences(text))
                    requireWebsiteResource(reference, file.path(), paths);
                continue;
            }
            var document = org.jsoup.Jsoup.parse(text);
            for (var element : document.select("script[src],img[src],audio[src],video[src],source[src],track[src],link[href]"))
                requireWebsiteResource(element.hasAttr("src") ? element.attr("src") : element.attr("href"), file.path(), paths);
            for (var style : document.select("style"))
                for (String reference : DeliverablePreviewPolicy.cssResourceReferences(style.data()))
                    requireWebsiteResource(reference, file.path(), paths);
            for (var element : document.select("[style]"))
                for (String reference : DeliverablePreviewPolicy.cssResourceReferences(element.attr("style")))
                    requireWebsiteResource(reference, file.path(), paths);
        }
    }

    /** 与预览请求一致：先解码，再按引用文件目录规范化；查询/片段不参与表示文件路径匹配。 */
    static void requireWebsiteResource(String reference, String source, Set<String> paths) {
        if (reference.isBlank() || reference.startsWith("#") || reference.regionMatches(true, 0, "data:", 0, 5)) return;
        try {
            var uri = java.net.URI.create(reference);
            require(!uri.isAbsolute() && uri.getRawAuthority() == null && !uri.getPath().startsWith("/"),
                    "WEBSITE_RELATIVE_RESOURCE_REQUIRED");
            var base = new java.net.URI(null, null, "/" + source, null);
            // URI.getPath解码百分号；随后再规范化，防止编码的点段绕过目录边界。
            var decoded = new java.net.URI(null, null, uri.getPath().isEmpty() ? base.getPath()
                    : base.getPath().substring(0, base.getPath().lastIndexOf('/') + 1) + uri.getPath(), null).normalize().getPath();
            String relative = decoded.substring(1);
            ArtifactStorage.safePath(relative);
            require(paths.contains(relative), "WEBSITE_RESOURCE_MISSING");
        } catch (java.net.URISyntaxException invalid) { throw rejected("WEBSITE_RESOURCE_REFERENCE_INVALID"); }
        catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith("WEBSITE_")) throw invalid;
            throw rejected("WEBSITE_RESOURCE_REFERENCE_INVALID");
        }
    }
    private void validateHtml(String html,boolean single) {
        var checked=single?DeliverablePreviewPolicy.validateSingleFileHtml(html,List.of(),List.of())
                :DeliverablePreviewPolicy.validateMultiFileHtml(html,List.of(),List.of());
        require(checked.accepted(),"HTML_PREVIEW_POLICY_REJECTED:"+checked.violations().stream().map(v->v.code()).distinct().toList());
    }
    private static long tenant(RunIdentity run) { return Long.parseLong(run.runRef().tenantId().value()); }
    private static String resultRef(RunIdentity run) { return "task-result:"+run.runRef().runId(); }
    private static void control(PlatformToolExecutionContext context) { require(!context.cancellationSignal().isCancellationRequested() && Instant.now().isBefore(context.deadlineAt()),"OUTPUT_SUBMISSION_CANCELLED"); }
    private String write(Object object) { try{return json.writeValueAsString(object);}catch(Exception e){throw new IllegalStateException("output_serialization_failed",e);} }
    private <T> T read(Object object,Class<T> type) { try{return json.readValue(object.toString(),type);}catch(Exception e){throw new IllegalStateException("output_record_corrupt",e);} }
    static String hash(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    static String extension(String path) { int dot=path.lastIndexOf('.'); require(dot>path.lastIndexOf('/'),"FILE_EXTENSION_REQUIRED"); return path.substring(dot+1).toLowerCase(Locale.ROOT); }
    private static String basename(String path) { return path.substring(path.lastIndexOf('/')+1); }
    private static void text(String text,int maximum) { require(text!=null && !text.isBlank() && text.length()<=maximum,"INVALID_OUTPUT_TEXT"); }
    static void require(boolean ok,String code) { if(!ok)throw rejected(code); }
    static IllegalArgumentException rejected(String code) { return new IllegalArgumentException(code); }
}
