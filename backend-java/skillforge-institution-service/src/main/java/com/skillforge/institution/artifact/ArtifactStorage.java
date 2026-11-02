package com.skillforge.institution.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.execution.ExecuteRequest;
import com.skillforge.execution.ExecutionInputSource;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecutionManifest;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 文件先不可变落盘、数据库后发布；任何半成品均不获得可读元数据。 */
@Service
public class ArtifactStorage {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ArtifactStorage.class);
    private final ArtifactMapper mapper;
    private final ObjectMapper json;
    private final Path root;
    private final RunSettingsService settings;
    private final StudioArtifactProjection projection;
    private final ArtifactPublicInspection inspection;
    private final TransactionTemplate transactions;

    public ArtifactStorage(ArtifactMapper mapper, ObjectMapper json, PlatformTransactionManager manager,
            @Value("${skillforge.code-execution.artifact-root:./data/artifacts}") String root,
            RunSettingsService settings,StudioArtifactProjection projection, ArtifactPublicInspection inspection) {
        this.mapper=mapper; this.json=json; this.transactions=new TransactionTemplate(manager);
        this.root=Path.of(root).toAbsolutePath().normalize(); this.settings=settings;this.projection=projection;
        this.inspection=inspection;
    }

    /** 仅接收已停止写入的Runner快照；摘要与清单全值匹配后一次性公开整个目录。 */
    public ArtifactMetadata publish(PlatformToolExecutionContext context, List<ArtifactMetadata.File> expected,
            InputStream archive) throws IOException {
        return publish(context, expected, List.of(), archive);
    }

    /** 来源关系与产物元数据在同一事务发布，调用重放必须同时匹配文件清单和多父来源图。 */
    public ArtifactMetadata publish(PlatformToolExecutionContext context, List<ArtifactMetadata.File> expected,
            List<ArtifactProvenance> inputs, InputStream archive) throws IOException {
        var operation=settings.captureTool(context.run().runRef().tenantId().value(),context.run().runRef().runId(),context.invocationId());
        var policy=settings.read(operation,"artifacts");
        int maxFiles=policy.integer("max_files");
        Duration retention=Duration.ofDays(policy.longValue("retention_days"));
        if(expected.isEmpty()||expected.size()>maxFiles) throw new IOException("artifact_file_count_rejected");
        TreeMap<String,ArtifactMetadata.File> manifest=new TreeMap<>();
        for(var file:expected) {
            safePath(file.path());
            if(file.size()<0||!file.sha256().matches("[0-9a-f]{64}")||manifest.put(file.path(),file)!=null)
                throw new IOException("artifact_manifest_rejected");
        }
        if(manifest.values().stream().mapToLong(ArtifactMetadata.File::size).reduce(0,Math::addExact)>ArtifactPublicInspection.MAX_BATCH_BYTES)
            throw new IOException("artifact_total_size_exceeded");
        String tenant=context.run().owner().sessionRef().tenantId().value();
        String manifestJson=canonicalManifest(manifest.values());
        String digest=hex(hash().digest(manifestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        List<ArtifactProvenance> provenance=canonicalProvenance(inputs);
        String inputsDigest=provenanceDigest(provenance);
        var existing=transactions.execute(status->mapper.invocation(tenant,context.invocationId()));
        if(existing!=null) {
            requireSame(existing,context,digest,inputsDigest);
            if(!Boolean.TRUE.equals(transactions.execute(status->publiclyInspected(existing))))
                throw ArtifactPublicInspection.rejected();
            return metadata(existing);
        }
        if(!tenant.matches("[1-9][0-9]*"))throw new IOException("artifact_tenant_rejected");
        Files.createDirectories(root.resolve("staging")); Files.createDirectories(root.resolve("ready").resolve(tenant));
        Path staging=Files.createTempDirectory(root.resolve("staging"),"collect-");
        String id=UUID.randomUUID().toString(); Path ready=root.resolve("ready").resolve(tenant).resolve(id);
        try {
            Set<String> received=new HashSet<>();
            try(var zip=new ZipInputStream(archive)) {
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    checkControl(context);
                    String name=safePath(entry.getName());
                    var file=manifest.get(name);
                    if(entry.isDirectory()||file==null||!received.add(name)) throw new IOException("artifact_archive_rejected");
                    Path target=staging.resolve(name); Files.createDirectories(target.getParent());
                    var sha=hash(); long size=0;
                    try(var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[64*1024]; int read;
                        while((read=zip.read(buffer))!=-1) {
                            checkControl(context); size=Math.addExact(size,read);
                            if(size>file.size()) throw new IOException("artifact_size_mismatch");
                            output.write(buffer,0,read); sha.update(buffer,0,read);
                        }
                    }
                    if(size!=file.size()||!hex(sha.digest()).equals(file.sha256())) throw new IOException("artifact_digest_mismatch");
                    try(var channel=FileChannel.open(target,StandardOpenOption.WRITE)){channel.force(true);}
                }
            }
            if(!received.equals(manifest.keySet())) throw new IOException("artifact_incomplete");
            checkControl(context);
            var scope=inspection.scope(context.run());
            var checked=new ArrayList<ArtifactInspectionRow>();
            inspection.inspectBatch(manifest.values().stream().map(file ->
                    new ArtifactPublicInspection.Candidate(staging.resolve(file.path()), file.path())).toList(),
                    scope, context.deadlineAt(), () -> checkControl(context));
            for(var file:manifest.values()) {
                checked.add(new ArtifactInspectionRow(file.path(),file.size(),file.sha256(),scope.digest(),ArtifactPublicInspection.POLICY_VERSION));
            }
            checkControl(context);
            Files.move(staging,ready,StandardCopyOption.ATOMIC_MOVE);
            Instant at=Instant.now().truncatedTo(ChronoUnit.MICROS);
            boolean website=manifest.containsKey("index.html");
            var row=new ArtifactRow(id,tenant,context.run().owner().sessionRef().sessionId(),
                    context.run().runRef().runId(),context.invocationId(),website?"website":"file",
                    website?"网站":manifest.size()==1?manifest.firstEntry().getKey():"生成文件",
                    manifest.values().stream().mapToLong(ArtifactMetadata.File::size).reduce(0,Math::addExact),
                    digest,manifestJson,inputsDigest,at,at.plus(retention),"ready");
            ArtifactRow published=transactions.execute(status->{
                checkControl(context);
                if(mapper.insert(row)==1) {
                    if(mapper.insertFiles(row,List.copyOf(manifest.values()))!=manifest.size())
                        throw new IllegalStateException("artifact_files_registration_failed");
                    if(mapper.insertInspections(tenant,id,checked)!=checked.size())
                        throw new IllegalStateException("artifact_inspections_registration_failed");
                    if(!provenance.isEmpty()&&mapper.insertInputs(tenant,id,provenance)!=provenance.size())
                        throw new IllegalStateException("artifact_inputs_registration_failed");
                    return row;
                }
                var previous=mapper.invocation(tenant,context.invocationId());
                requireSame(previous,context,digest,inputsDigest);
                return previous;
            });
            if(!published.id().equals(id)) deleteTree(ready);
            if(!Boolean.TRUE.equals(transactions.execute(status->publiclyInspected(published))))
                throw ArtifactPublicInspection.rejected();
            return metadata(published);
        } finally { deleteTree(staging); }
        // ready移动后提交失败的目录保留为不可见孤儿，由清理器回收。
    }

    public List<ArtifactMetadata> list(String tenant,String session) {
        return transactions.execute(status->{
            var rows=mapper.list(tenant,session,ArtifactPublicInspection.POLICY_VERSION);
            if(rows.isEmpty())return List.of();
            var inputsByArtifact=new HashMap<String,List<ArtifactProvenance>>();
            List<ArtifactInputRow> inputRows=mapper.sessionInputs(tenant,session);
            if(inputRows!=null)for(var input:inputRows)
                inputsByArtifact.computeIfAbsent(input.artifactId(),ignored->new ArrayList<>()).add(input.provenance());
            return listedMetadata(tenant,rows,inputsByArtifact);
        });
    }

    record ToolPage(long total,List<ArtifactMetadata> artifacts) {
        ToolPage { artifacts=List.copyOf(artifacts); }
    }

    /** 工具只校验本页材料；普通全量列表与单项读取继续执行各自完整校验。 */
    ToolPage listForTool(String tenant,String session,int offset,int limit) {
        return transactions.execute(status->{
            long total=mapper.countToolAvailable(tenant,session,ArtifactPublicInspection.POLICY_VERSION);
            if(total==0 || offset>=total)return new ToolPage(total,List.of());
            var rows=mapper.listToolPage(tenant,session,ArtifactPublicInspection.POLICY_VERSION,offset,limit);
            if(rows.isEmpty())return new ToolPage(total,List.of());
            var inputsByArtifact=new HashMap<String,List<ArtifactProvenance>>();
            for(var input:mapper.inputsForArtifacts(tenant,rows.stream().map(ArtifactRow::id).toList()))
                inputsByArtifact.computeIfAbsent(input.artifactId(),ignored->new ArrayList<>()).add(input.provenance());
            var artifacts=rows.stream().map(row->metadata(row,inputsByArtifact.getOrDefault(row.id(),List.of()),true)).toList();
            return new ToolPage(total,artifacts);
        });
    }

    /** 用户展示入口可批量读取已经过宿主投影授权的 Session；工具仍调用单 Session 版本。 */
    List<ArtifactMetadata> listSessions(String tenant, Collection<String> sessions) {
        var selected = List.copyOf(new LinkedHashSet<>(sessions));
        if (selected.isEmpty() || selected.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("artifact_sessions_rejected");
        }
        return transactions.execute(status -> {
            var rows=mapper.listSessions(tenant,selected,ArtifactPublicInspection.POLICY_VERSION);
            if(rows.isEmpty())return List.of();
            var inputsByArtifact = new HashMap<String,List<ArtifactProvenance>>();
            List<ArtifactInputRow> inputRows = mapper.sessionInputsForSessions(tenant, selected);
            if (inputRows != null) {
                for (var input : inputRows) {
                    inputsByArtifact.computeIfAbsent(input.artifactId(), ignored -> new ArrayList<>())
                            .add(input.provenance());
                }
            }
            return listedMetadata(tenant,rows,inputsByArtifact);
        });
    }

    /** 可信执行身份决定可读范围；文件流式打包，正文不进入模型或 JSON。 */
    public ExecutionArchive executionArchive(PlatformToolExecutionContext context, List<ArtifactInput> inputs,
            ExecuteRequest execution, List<ExecutionInputSource> sources) throws IOException {
        if(inputs.size()+sources.size()>ExecutionLimits.MAX_FILES||new HashSet<>(inputs).size()!=inputs.size())
            throw new IllegalArgumentException("artifact_inputs_rejected");
        String tenant=context.run().owner().sessionRef().tenantId().value();
        String previous=TenantContext.getTenantId();
        TenantContext.setTenantId(tenant);
        Path archive=null;
        try {
            var rows=new HashMap<String,ArtifactRow>();
            var metadataByArtifact=new HashMap<String,ArtifactMetadata>();
            var manifest=new ArrayList<ArtifactMetadata.File>();
            var provenance=new ArrayList<ArtifactProvenance>();
            for(var input:inputs) {
                checkControl(context);
                var row=rows.computeIfAbsent(input.artifactId(),id->require(tenant,id));
                if(!tenant.equals(row.tenantId()))throw unavailable();
                if(!context.run().owner().sessionRef().sessionId().equals(row.sessionId())
                        && projection.route(tenant,row.sessionId(),row.runId()).filter(route->route.productSession().equals(context.run().owner().sessionRef().sessionId())).isEmpty())
                    throw unavailable();
                var storedFiles=metadataByArtifact.computeIfAbsent(input.artifactId(),ignored->metadata(row)).files();
                var selected=storedFiles.stream().filter(f->f.path().equals(input.path())).findFirst().orElseThrow(ArtifactStorage::unavailable);
                if(selected.size()<0||!selected.sha256().matches("[0-9a-f]{64}"))throw new IllegalStateException("artifact_manifest_corrupt");
                manifest.add(new ArtifactMetadata.File(input.inputPath(),selected.size(),selected.sha256()));
                provenance.add(new ArtifactProvenance(input.artifactId(),input.path(),selected.size(),selected.sha256()));
            }
            var transferManifest=new ArrayList<com.skillforge.execution.ArtifactFile>();
            manifest.forEach(file->transferManifest.add(new com.skillforge.execution.ArtifactFile(file.path(),file.size(),file.sha256())));
            sources.forEach(source->transferManifest.add(source.file()));
            long totalBytes=0;var paths=new HashSet<String>();
            for(var file:transferManifest) {
                safePath(file.path());
                if(file.size()<0||file.sha256()==null||!file.sha256().matches("[0-9a-f]{64}")||!paths.add(file.path()))
                    throw new IllegalArgumentException("execution_input_manifest_invalid");
                totalBytes=Math.addExact(totalBytes,file.size());
                if(totalBytes>ExecutionLimits.TRANSFER_BYTES)throw new IllegalArgumentException("execution_input_transfer_limit");
            }
            byte[] header=json.writeValueAsBytes(new ExecutionManifest(execution,transferManifest));
            if(header.length>ExecutionLimits.CONTROL_RESPONSE_BYTES)throw new IllegalArgumentException("artifact_input_metadata_too_large");
            Files.createDirectories(root.resolve("staging"));
            archive=Files.createTempFile(root.resolve("staging"),"execution-input-",".zip");
            try(var zip=new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry(ExecutionLimits.REQUEST_ENTRY));zip.write(header);zip.closeEntry();
                for(int i=0;i<inputs.size();i++) {
                    var input=inputs.get(i);var expected=manifest.get(i);var row=rows.get(input.artifactId());
                    requireFileReadable(row,input.path());
                    zip.putNextEntry(new ZipEntry(input.inputPath()));
                    var digest=hash();long[] size={0};
                    OutputStream verified=new OutputStream(){
                        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
                        @Override public void write(byte[] bytes,int offset,int length)throws IOException{
                            checkControl(context);size[0]=Math.addExact(size[0],length);
                            if(size[0]>expected.size())throw new IOException("artifact_input_size_mismatch");
                            digest.update(bytes,offset,length);zip.write(bytes,offset,length);
                        }
                    };
                    streamFile(row,input.path(),verified);
                    if(size[0]!=expected.size()||!hex(digest.digest()).equals(expected.sha256()))throw new IOException("artifact_input_digest_mismatch");
                    zip.closeEntry();
                }
                for(var source:sources) {
                    checkControl(context);var expected=source.file();
                    zip.putNextEntry(new ZipEntry(expected.path()));
                    var digest=hash();long size=0;
                    try(var input=source.open()) {
                        byte[] buffer=new byte[ExecutionLimits.STREAM_BUFFER_BYTES];int read;
                        while((read=input.read(buffer))!=-1) {
                            checkControl(context);size=Math.addExact(size,read);
                            if(size>expected.size())throw new IOException("execution_input_size_mismatch");
                            zip.write(buffer,0,read);digest.update(buffer,0,read);
                        }
                    }
                    if(size!=expected.size()||!hex(digest.digest()).equals(expected.sha256()))
                        throw new IOException("execution_input_digest_mismatch");
                    zip.closeEntry();
                }
            }
            checkControl(context);
            return new ExecutionArchive(archive,canonicalProvenance(provenance));
        } catch(IOException|RuntimeException failure) {
            if(archive!=null)Files.deleteIfExists(archive);
            throw failure;
        } finally { if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous); }
    }

    /** 请求结束即回收临时传输文件，不复用任务工作目录。 */
    public record ExecutionArchive(Path path,List<ArtifactProvenance> inputs) implements AutoCloseable {
        public ExecutionArchive {
            inputs=List.copyOf(inputs);
        }
        @Override public void close() throws IOException { Files.deleteIfExists(path); }
    }

    /** JSONB 不保留文本空白和字段顺序；发布与读取统一对类型化、按路径排序的清单计算摘要。 */
    private String canonicalManifest(Collection<ArtifactMetadata.File> files) throws IOException {
        var sorted=new TreeMap<String,ArtifactMetadata.File>();
        for(var file:files) {
            safePath(file.path());
            if(file.size()<0||file.sha256()==null||!file.sha256().matches("[0-9a-f]{64}")
                    ||sorted.put(file.path(),file)!=null)throw new IOException("artifact_manifest_rejected");
        }
        return json.writeValueAsString(sorted.values());
    }
    private List<ArtifactProvenance> canonicalProvenance(Collection<ArtifactProvenance> inputs) throws IOException {
        var sorted=new TreeMap<String,ArtifactProvenance>();
        for(var input:inputs) {
            if(input==null||input.artifactId()==null
                    ||!input.artifactId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IOException("artifact_inputs_rejected");
            safePath(input.path());
            if(input.size()<0||input.sha256()==null||!input.sha256().matches("[0-9a-f]{64}")
                    ||sorted.put(input.artifactId()+"\u0000"+input.path(),input)!=null)
                throw new IOException("artifact_inputs_rejected");
        }
        return List.copyOf(sorted.values());
    }
    private String provenanceDigest(List<ArtifactProvenance> inputs) throws IOException {
        return hex(hash().digest(json.writeValueAsBytes(inputs)));
    }
    ArtifactRow require(String tenant,String id) {
        if(id==null||!id.matches("[0-9a-f-]{36}"))throw unavailable();
        var row=transactions.execute(status->mapper.find(tenant,id));
        if(row==null||!"ready".equals(row.status())||!Boolean.TRUE.equals(transactions.execute(s->mapper.anyFileReadable(tenant,id,ArtifactPublicInspection.POLICY_VERSION))))throw unavailable();
        return row;
    }
    /** 开放产物访问每次重新检查渠道、会话归属和已发布智能体绑定。 */
    String sessionEndUser(String tenant,String session) {
        return transactions.execute(status -> mapper.sessionEndUser(tenant, session));
    }
    boolean canChannelAccess(String tenant,String channel,String session) {
        if(channel==null||!channel.matches("[1-9][0-9]*"))return false;
        return Boolean.TRUE.equals(transactions.execute(status->mapper.canChannelAccess(tenant,channel,session)));
    }
    ArtifactMetadata metadata(ArtifactRow row) {
        List<ArtifactInputRow> inputRows=mapper.inputs(row.tenantId(),row.id());
        return metadata(row,inputRows==null?List.of():inputRows.stream().map(ArtifactInputRow::provenance).toList(),null);
    }
    /** 用户清单与访问票据必须由真实检查凭据背书，未检查的历史文件不视为通过。 */
    boolean publiclyInspected(ArtifactRow row) {
        return mapper.publiclyInspected(row.tenantId(),row.id(),ArtifactPublicInspection.POLICY_VERSION);
    }
    void requirePublicInspection(ArtifactRow row) {
        if(!Boolean.TRUE.equals(transactions.execute(status->publiclyInspected(row))))throw unavailable();
    }

    public PublicOutputGuard.Scope publicScope(RunIdentity run) {
        return inspection.scope(run);
    }

    /** 冻结前在当前私有值范围复核；范围扩大时新增不可变凭据，不覆盖首次公开检查结论。 */
    void inspectPublicFile(PlatformToolExecutionContext context,ArtifactRow row,String path,PublicOutputGuard.Scope original) throws IOException {
        checkControl(context);
        var file=verifiedFile(row,path,0);
        var source=file.provenance();
        var scope=original;
        boolean checked=Boolean.TRUE.equals(transactions.execute(status->mapper.inspected(row.tenantId(),row.id(),path,
                source.sha256(),source.size(),scope.digest(),ArtifactPublicInspection.POLICY_VERSION)));
        if(checked)return;
        inspection.inspect(readablePath(row,path),path,scope,context.deadlineAt(),()->checkControl(context));
        // 扫描和凭据之间再次核验实际不可变字节，防止文件被替换后沿用检查结论。
        if(!source.equals(verifiedFile(row,path,0).provenance()))throw ArtifactPublicInspection.rejected();
        transactions.executeWithoutResult(status->mapper.insertInspections(row.tenantId(),row.id(),List.of(
                new ArtifactInspectionRow(path,source.size(),source.sha256(),scope.digest(),ArtifactPublicInspection.POLICY_VERSION))));
    }
    /** 两列表入口已确认rows非空；只对本次真实行一次读取可读集合，每行仍校验摘要后投影。 */
    private List<ArtifactMetadata> listedMetadata(String tenant,List<ArtifactRow> rows,
            Map<String,List<ArtifactProvenance>> inputsByArtifact) {
        var readable=new java.util.HashSet<>(mapper.readableArtifactIds(tenant,rows.stream().map(ArtifactRow::id).toList(),
                ArtifactPublicInspection.POLICY_VERSION));
        return rows.stream().map(row->metadata(row,inputsByArtifact.getOrDefault(row.id(),List.of()),
                readable.contains(row.id()))).toList();
    }
    /** readable为null只表示普通入口即时查询；列表传入同事务的明确结果，不形成持久缓存。 */
    private ArtifactMetadata metadata(ArtifactRow row,List<ArtifactProvenance> inputs,Boolean readable) {
        try {
            if(!provenanceDigest(canonicalProvenance(inputs)).equals(row.inputsSha256()))
                throw new IllegalStateException("artifact_inputs_digest_mismatch");
            boolean available=readable!=null?readable:Boolean.TRUE.equals(transactions.execute(s->
                    mapper.anyFileReadable(row.tenantId(),row.id(),ArtifactPublicInspection.POLICY_VERSION)));
            return new ArtifactMetadata(row.id(),row.sessionId(),row.runId(),row.invocationId(),row.type(),row.name(),
                    row.size(),row.sha256(),row.expiresAt(),available?row.status():"expired",
                    manifestFiles(row),inputs);
        } catch(IOException corrupt){throw new IllegalStateException("artifact_manifest_corrupt",corrupt);}
    }
    private List<ArtifactMetadata.File> manifestFiles(ArtifactRow row) {
        try {
            List<ArtifactMetadata.File> files=json.readValue(row.manifest(),new TypeReference<>(){});
            if(!hex(hash().digest(canonicalManifest(files).getBytes(java.nio.charset.StandardCharsets.UTF_8))).equals(row.sha256()))
                throw new IllegalStateException("artifact_manifest_digest_mismatch");
            return files;
        } catch(IOException corrupt) {
            throw new IllegalStateException("artifact_manifest_corrupt",corrupt);
        }
    }
    /** 重新读取完整文件并核验发布时的字节数与摘要；仅在大小不超过 captureLimit 时保留正文。 */
    VerifiedFile verifiedFile(ArtifactRow row,String path,int captureLimit) throws IOException {
        requireFileReadable(row,path);
        var artifact=metadata(row);
        var expected=artifact.files().stream().filter(file->file.path().equals(path)).findFirst()
                .orElseThrow(ArtifactStorage::unavailable);
        ByteArrayOutputStream captured=expected.size()<=captureLimit?new ByteArrayOutputStream((int)expected.size()):null;
        var digest=hash();long[] size={0};
        OutputStream verified=new OutputStream() {
            @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
            @Override public void write(byte[] bytes,int offset,int length)throws IOException{
                size[0]=Math.addExact(size[0],length);
                if(size[0]>expected.size())throw new IOException("artifact_input_size_mismatch");
                digest.update(bytes,offset,length);
                if(captured!=null)captured.write(bytes,offset,length);
            }
        };
        streamFile(row,path,verified);
        if(size[0]!=expected.size()||!hex(digest.digest()).equals(expected.sha256()))
            throw new IOException("artifact_input_digest_mismatch");
        requireFileReadable(row,path);
        return new VerifiedFile(new ArtifactProvenance(row.id(),expected.path(),expected.size(),expected.sha256()),
                captured==null?null:captured.toByteArray());
    }

    List<ArtifactProvenance> validateInputs(String tenant,String session,List<ArtifactInput> inputs) throws IOException {
        if(inputs==null||inputs.isEmpty()||inputs.size()>ExecutionLimits.MAX_FILES||new HashSet<>(inputs).size()!=inputs.size())
            throw new IllegalArgumentException("artifact_inputs_rejected");
        var verified=new ArrayList<ArtifactProvenance>(inputs.size());
        for(var input:inputs) {
            var row=require(tenant,input.artifactId());
            if(!session.equals(row.sessionId()))throw unavailable();
            verified.add(verifiedFile(row,input.path(),0).provenance());
        }
        return List.copyOf(verified);
    }

    record VerifiedFile(ArtifactProvenance provenance,byte[] content) {
        VerifiedFile {
            content=content==null?null:content.clone();
        }
        @Override public byte[] content(){return content==null?null:content.clone();}
    }
    /** 当前文件完整性通过后才向消费者写入；传输使用同一次读取产生的私有快照。 */
    void file(ArtifactRow row,String path,OutputStream output) throws IOException {
        String safe=safePath(path);
        var selected=manifestFiles(row).stream().filter(file->file.path().equals(safe)).findFirst()
                .orElseThrow(ArtifactStorage::unavailable);
        transferVerified(row,List.of(selected),false,output);
    }
    private void streamFile(ArtifactRow row,String path,OutputStream output) throws IOException {
        Path file=readablePath(row,path);
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){input.transferTo(output);}
    }
    private Path readablePath(ArtifactRow row,String path) throws IOException {
        requireFileReadable(row,path);
        String safe=safePath(path);
        Path directory=root.resolve("ready").resolve(row.tenantId()).resolve(row.id()); Path file=directory.resolve(safe);
        Path rootCursor=root;
        if(Files.isSymbolicLink(rootCursor))throw new IOException("artifact_link_rejected");
        for(Path component:root.relativize(directory)){rootCursor=rootCursor.resolve(component);if(Files.isSymbolicLink(rootCursor))throw new IOException("artifact_link_rejected");}
        Path cursor=directory;
        for(Path component:directory.relativize(file)) {
            cursor=cursor.resolve(component); if(Files.isSymbolicLink(cursor))throw new IOException("artifact_link_rejected");
        }
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw unavailable();
        return file;
    }
    /** 整个清单均校验成功才释放 ZIP；后续条目损坏也不能泄露此前条目或 ZIP 头。 */
    void zip(ArtifactRow row,OutputStream output) throws IOException {
        transferVerified(row,manifestFiles(row),true,output);
    }

    /** 请求拥有临时文件和句柄；成功、拒绝及客户端断开均回收，不重新打开已校验的源文件。 */
    private void transferVerified(ArtifactRow row,List<ArtifactMetadata.File> files,boolean archive,
            OutputStream output) throws IOException {
        // 先复核可读状态，再占用暂存空间；正文始终以固定缓冲区读写，不随文件大小驻留内存。
        for(var file:files)requireFileReadable(row,file.path());
        Path staging=root.resolve("staging");Files.createDirectories(staging);
        Path snapshot=Files.createTempFile(staging,"download-",archive?".zip":".tmp");
        try {
            try(var channel=FileChannel.open(snapshot,StandardOpenOption.READ,StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE)) {
                var sink=Channels.newOutputStream(channel);
                if(archive) {
                    // ZIP 关闭必须释放压缩器，但同一快照句柄仍由外层持有用于随后传输。
                    try(var zip=new ZipOutputStream(new FilterOutputStream(sink) {
                        @Override public void close() throws IOException { flush(); }
                    })) {
                        for(var file:files) {
                            zip.putNextEntry(new ZipEntry(file.path()));
                            copyVerified(row,file,zip);zip.closeEntry();
                        }
                    }
                } else copyVerified(row,files.getFirst(),sink);
                // 准备期间到期或撤销文件可读性时仍在响应正文之前拒绝。
                for(var file:files)requireFileReadable(row,file.path());
                channel.position(0);
                Channels.newInputStream(channel).transferTo(output);
            }
        } finally { Files.deleteIfExists(snapshot); }
    }

    /** 同一读取同时计算摘要并写入尚不可见的快照；不把任何未验证字节写入响应。 */
    private void copyVerified(ArtifactRow row,ArtifactMetadata.File expected,OutputStream snapshot) throws IOException {
        var digest=hash();long size=0;
        try(var input=Files.newInputStream(readablePath(row,expected.path()),LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[ExecutionLimits.STREAM_BUFFER_BYTES];int read;
            while((read=input.read(buffer))!=-1) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("artifact_download_interrupted");
                size=Math.addExact(size,read);
                if(size>expected.size())throw integrityMismatch("size");
                digest.update(buffer,0,read);snapshot.write(buffer,0,read);
            }
        }
        if(size!=expected.size())throw integrityMismatch("size");
        if(!hex(digest.digest()).equals(expected.sha256()))throw integrityMismatch("sha256");
    }

    /** 持久正文偏离已发布清单属于服务端完整性故障，不暗示重新生成或自动重试。 */
    private static ApiException integrityMismatch(String reason) {
        LOG.error("产物下载完整性校验失败 event=artifact_download_integrity result=rejected reason={}",reason);
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"ARTIFACT_INTEGRITY_MISMATCH",
                "文件完整性校验失败，无法下载",false);
    }
    public void cleanup(String tenant) throws IOException {
        var expired=transactions.execute(status->mapper.expiredFiles(tenant));
        for(var file:expired) {
            if(!Integer.valueOf(1).equals(transactions.execute(status->{ mapper.lockFile(tenant,file.id()); return mapper.markFileExpired(tenant,file.id()); })))continue;
            Path base=root.resolve("ready").resolve(tenant).resolve(file.artifactId());
            Path target=base.resolve(safePath(file.path())).normalize();
            if(!target.startsWith(base)||Files.isSymbolicLink(target))throw new IOException("artifact_cleanup_path_rejected");
            Files.deleteIfExists(target);
            transactions.executeWithoutResult(status->mapper.markFileDeleted(tenant,file.id()));
            io.micrometer.core.instrument.Metrics.counter("skillforge.deliverable.cleanup.files","status","deleted").increment();
            LOG.info("artifact_file_deleted tenant={} file={} reason=expired_unreferenced at={}",tenant,file.id(),Instant.now());
        }
    }

    void requireFileReadable(ArtifactRow row,String path) {
        if(row==null||!"ready".equals(row.status())||!Boolean.TRUE.equals(transactions.execute(s->mapper.fileReadable(row.tenantId(),row.id(),path,ArtifactPublicInspection.POLICY_VERSION))))throw unavailable();
    }
    /** 启动/周期清理只回收超过一天且没有发布记录的暂存目录；保留正在传输的目录。 */
    public void cleanupOrphans(String tenant) throws IOException {
        if(!tenant.matches("[1-9][0-9]*"))throw new IOException("artifact_tenant_rejected");
        Path published=root.resolve("ready").resolve(tenant);
        if(Files.isDirectory(published))try(var entries=Files.list(published)) {
            for(Path path:entries.toList())if(Files.getLastModifiedTime(path).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1)))
                    &&transactions.execute(status->mapper.find(tenant,path.getFileName().toString()))==null)deleteTree(path);
        }
        Path staging=root.resolve("staging");
        if(!Files.isDirectory(staging))return;
        try(var entries=Files.list(staging)) {
            for(Path path:entries.toList())if(Files.getLastModifiedTime(path).toInstant().isBefore(Instant.now().minus(Duration.ofDays(1))))deleteTree(path);
        }
    }
    public static String safePath(String path) {
        if(path==null||path.isBlank()||path.startsWith("/")||path.contains("\\")||path.contains(":")
                ||path.chars().anyMatch(c->c<32||c==127)||Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))
            throw new IllegalArgumentException("artifact_path_rejected");
        return path;
    }
    static void deleteTree(Path directory) throws IOException {
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(directory,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure)throws IOException{if(failure!=null)throw failure;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
    private static void requireSame(ArtifactRow row,PlatformToolExecutionContext context,String digest,String inputsDigest) {
        if(row==null||!row.sha256().equals(digest)||!row.runId().equals(context.run().runRef().runId())
                ||!row.inputsSha256().equals(inputsDigest)
                ||!row.sessionId().equals(context.run().owner().sessionRef().sessionId()))throw new IllegalStateException("artifact_registration_conflict");
    }
    static void checkControl(PlatformToolExecutionContext context) {
        if(Thread.currentThread().isInterrupted()||context.cancellationSignal().isCancellationRequested()||!Instant.now().isBefore(context.deadlineAt()))
            throw new IllegalStateException("artifact_transfer_cancelled");
    }
    static MessageDigest hash(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    static String hex(byte[] bytes){return HexFormat.of().formatHex(bytes);}
    static ApiException unavailable(){return new ApiException(HttpStatus.GONE,"产物不可用、无权限或已过期");}
}
