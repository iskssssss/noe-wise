package com.skillforge.institution.attachment;

import com.skillforge.common.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.RunRef;

import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeProgramTools;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import com.skillforge.core.chat.contract.ChatAttachmentLimits;
import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.ExecutionInputSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 用户上传的会话输入；有限文本准备与沙箱读取均以已授权的原件为来源。 */
@Service
public class SessionAttachmentService {
    private static final Logger LOG = LoggerFactory.getLogger(SessionAttachmentService.class);
    private static final String INVALID_MESSAGE = "附件须为不超过50MiB的UTF-8文本、PDF、DOCX、PPTX、XLSX或PNG/JPEG；每条消息最多16个、合计不超过200MiB";
    public static final int MAX_BYTES = ChatAttachmentLimits.MAX_FILE_BYTES;
    static final int MAX_PREPARED_TEXT_BYTES = 32 * 1024;
    static final int MAX_TOTAL_PREPARED_TEXT_BYTES = 64 * 1024;
    private static final Set<String> EXTENSIONS = ChatAttachmentLimits.EXTENSIONS;
    private static final Set<String> TEXT_EXTENSIONS = Set.of("csv", "tsv", "txt", "md", "json");
    private final SessionAttachmentMapper mapper;
    private final ArtifactAccess access;
    private final AgentRunBindings bindings;
    private final SessionAttachmentBodyStore bodies;
    private final ExecutionStore executions;
    private final AgentExecutionCodec codec;
    private final ObjectProvider<AgentRuntimeProgramTools> programs;
    private final ObjectMapper json = new ObjectMapper();

    public SessionAttachmentService(SessionAttachmentMapper mapper, ArtifactAccess access, AgentRunBindings bindings,
            SessionAttachmentBodyStore bodies, ExecutionStore executions, AgentExecutionCodec codec,
            ObjectProvider<AgentRuntimeProgramTools> programs) {
        this.executions = Objects.requireNonNull(executions);
        this.codec = Objects.requireNonNull(codec);
        this.programs = Objects.requireNonNull(programs);
        this.mapper = mapper;
        this.access = access;
        this.bindings = Objects.requireNonNull(bindings);
        this.bodies = Objects.requireNonNull(bodies);
    }

    /** 客户端稳定 upload_id 支持未知上传结果查询和显式重试，不能覆盖已保存的内容。 */
    @Transactional
    public Metadata upload(String session, String uploadId, MultipartFile file) throws IOException {
        String tenant = access.tenant();
        access.authorizeWritableCurrent(tenant, session);
        requireId(uploadId);
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw uploadRejected(INVALID_MESSAGE);
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(MAX_BYTES + 1); }
        return registerUpload(tenant, session, uploadId, file.getOriginalFilename(), bytes);
    }

    /** 服务身份以完整原始字节上传；文件名由已签名的请求目标提供，复用原附件验证与事务。 */
    @Transactional
    public Metadata upload(String session, String uploadId, String name, byte[] bytes) throws IOException {
        String tenant = access.tenant();
        access.authorizeWritableCurrent(tenant, session);
        requireId(uploadId);
        return registerUpload(tenant, session, uploadId, name, bytes);
    }

    private Metadata registerUpload(String tenant, String session, String uploadId, String name, byte[] bytes) throws IOException {
        if (name == null || name.isBlank() || name.length() > 128
                || name.chars().anyMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\')) throw uploadRejected(INVALID_MESSAGE);
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) throw uploadRejected(INVALID_MESSAGE);
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw uploadRejected(INVALID_MESSAGE);
        try {
            SessionAttachmentContentValidator.validate(extension, bytes);
        } catch (ApiException rejected) {
            throw uploadRejected(rejected.getMessage());
        }
        Instant now = Instant.now();
        var row = new SessionAttachmentRow(uploadId, tenant, session, name,
                "attachments/" + uploadId + "/input." + extension, bytes.length, digest(bytes), bytes,
                now, now.plus(7, ChronoUnit.DAYS));
        bodies.register(tenant, session, row.sha256(), bytes);
        mapper.insert(row);
        var stored = require(tenant, session, uploadId);
        if (!stored.name().equals(name) || stored.size() != bytes.length || !stored.sha256().equals(row.sha256()))
            throw new ApiException(HttpStatus.CONFLICT, "附件上传标识已绑定其他内容");
        return metadata(stored);
    }

    public List<Metadata> list(String session) {
        String tenant = access.tenant();
        access.authorizeCurrent(tenant, session);
        return mapper.list(tenant, session).stream().filter(row -> row.expiresAt().isAfter(Instant.now()))
                .map(SessionAttachmentService::metadata).toList();
    }

    /** 当前会话授权后观察正文容量；未设置的累积预算以 configured=false 返回。 */
    public SessionAttachmentBodyStore.SessionStorageBudget storageBudget(String session) {
        String tenant = access.tenant();
        access.authorizeCurrent(tenant, session);
        return bodies.inspectSession(tenant, session);
    }

    /** 消息只冻结服务端查得的清单；拒绝客户端伪造路径、摘要和附件正文。 */
    public Map<String, Object> inputContext(String tenant, String session, Map<String, Object> context) {
        return inputContext(tenant, session, context, false);
    }

    /** 纯附件输入首次受理前读取有界文本；带文字目标及重放只校验选择器，不重复准备正文。 */
    public Map<String, Object> inputContext(String tenant, String session, Map<String, Object> context,
            boolean prepareText) {
        LOG.debug("会话附件输入冻结进入 prepareText={}", prepareText);
        var result = new LinkedHashMap<String, Object>(context == null ? Map.of() : context);
        result.remove("attachments");
        result.remove("attachment_readings");
        List<String> ids = ids(result.get("attachment_ids"));
        if (ids.isEmpty()) {
            result.remove("attachment_ids");
            LOG.debug("会话附件输入冻结退出 attachmentCount=0");
            return result;
        }
        access.authorizeCurrent(tenant, session);
        result.put("attachment_ids", ids);
        var rows = metadataById(tenant, session, ids);
        var selected = ids.stream().map(id -> requireRow(tenant, session, rows.get(id))).toList();
        requireTotalBytes(selected.stream().mapToLong(SessionAttachmentRow::size).sum());
        result.put("attachments", selected.stream().map(row -> metadata(row).publicValue()).toList());
        if (prepareText) result.put("attachment_readings", prepareText(tenant, session, selected));
        LOG.debug("会话附件输入冻结退出 attachmentCount={} prepareText={}", ids.size(), prepareText);
        return result;
    }

    /** 每件小文本独立复验当前授权与冻结摘要，总预算之外和非文本原件交给正式读取能力。 */
    private List<TextReading> prepareText(String tenant, String session, List<SessionAttachmentRow> selected) {
        LOG.debug("会话附件文本准备进入 attachmentCount={}", selected.size());
        var readings = new ArrayList<TextReading>();
        long preparedBytes = 0;
        for (var frozen : selected) {
            String extension = frozen.inputPath().substring(frozen.inputPath().lastIndexOf('.') + 1);
            String reason = !TEXT_EXTENSIONS.contains(extension) ? "requires_document_parser"
                    : frozen.size() > MAX_PREPARED_TEXT_BYTES ? "file_byte_limit"
                    : frozen.size() > MAX_TOTAL_PREPARED_TEXT_BYTES - preparedBytes ? "message_byte_limit" : null;
            if (reason != null) {
                readings.add(new TextReading(frozen.id(), "not_read", false, null, reason));
                continue;
            }
            access.authorizeCurrent(tenant, session);
            var row = require(tenant, session, frozen.id());
            var bytes = row.content();
            if (!metadata(frozen).equals(metadata(row)) || bytes == null || bytes.length != frozen.size()
                    || !digest(bytes).equals(frozen.sha256()))
                throw new IllegalStateException("attachment_digest_mismatch");
            try {
                SessionAttachmentContentValidator.validate(extension, bytes);
            } catch (IOException invalidText) {
                throw new IllegalStateException("attachment_text_invalid", invalidText);
            }
            readings.add(new TextReading(frozen.id(), "complete", false,
                    new String(bytes, StandardCharsets.UTF_8), null));
            preparedBytes += bytes.length;
        }
        LOG.debug("会话附件文本准备退出 attachmentCount={} preparedBytes={}", readings.size(), preparedBytes);
        return List.copyOf(readings);
    }

    /** 正文只在传输当前文件时加载，不能把整批附件转换为常驻字符串。 */
    public Resolved resolve(PlatformToolExecutionContext context, List<String> attachmentIds,
                            Map<String, String> inlineFiles) {
        var ids = ids(attachmentIds);
        var files = new ArrayList<ExecutionInputSource>();
        var metadata = new ArrayList<Metadata>();
        var paths = new HashSet<String>();
        long total = 0;
        for (var inline : new TreeMap<>(inlineFiles).entrySet()) {
            String path = "data/" + inline.getKey();
            byte[] bytes = inline.getValue().getBytes(StandardCharsets.UTF_8);
            paths.add(path);
            files.add(new ExecutionInputSource(new ArtifactFile(path, bytes.length, digest(bytes)),
                    () -> new ByteArrayInputStream(bytes)));
        }
        String tenant = context.run().owner().sessionRef().tenantId().value();
        String session = context.run().owner().sessionRef().sessionId();
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenant);
            if (!ids.isEmpty()) {
                checkControl(context);
                var binding = authorizeExecution(context);
                session = attachmentSession(context, ids, binding);
            }
            var rows = metadataById(tenant, session, ids);
            for (String id : ids) {
                checkControl(context);
                var row = requireRow(tenant, session, rows.get(id));
                String path = "data/" + row.inputPath();
                if (!paths.add(path)) throw new IllegalArgumentException("attachment_input_path_collision");
                total = Math.addExact(total, row.size());
                requireTotalBytes(total);
                var frozen = metadata(row);
                files.add(new ExecutionInputSource(new ArtifactFile(path, row.size(), row.sha256()),
                        () -> open(context, frozen)));
                metadata.add(frozen);
            }
            return new Resolved(List.copyOf(files), List.copyOf(metadata));
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    /** 每次传输重新读取准确附件，核验冻结事实、实际文件类型与当前授权。 */
    private InputStream open(PlatformToolExecutionContext context, Metadata frozen) throws IOException {
        checkControl(context);
        String tenant = context.run().owner().sessionRef().tenantId().value();
        String session = context.run().owner().sessionRef().sessionId();
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenant);
            var binding = authorizeExecution(context);
            session = attachmentSession(context, List.of(frozen.id()), binding);
            var row = require(tenant, session, frozen.id());
            byte[] bytes = row.content();
            if (!frozen.equals(metadata(row)) || bytes == null || bytes.length != frozen.size()
                    || !digest(bytes).equals(frozen.sha256())) throw new IllegalStateException("attachment_digest_mismatch");
            SessionAttachmentContentValidator.validate(
                    row.inputPath().substring(row.inputPath().lastIndexOf('.') + 1), bytes);
            checkControl(context);
            return new ByteArrayInputStream(bytes);
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    private static void requireTotalBytes(long total) {
        if (total > ChatAttachmentLimits.MAX_MESSAGE_BYTES)
            throw new IllegalArgumentException("attachment_input_total_size_exceeded");
    }

    private static void checkControl(PlatformToolExecutionContext context) {
        if (context.cancellationSignal().isCancellationRequested() || !Instant.now().isBefore(context.deadlineAt()))
            throw new IllegalStateException("attachment_execution_cancelled");
    }

    private String attachmentSession(PlatformToolExecutionContext context, List<String> requested,
            ResourceAuthorization authorization) {
        var selected = selectedInput(authorization.origin(), new HashSet<>(), authorization.binding());
        if (!selected.ids().containsAll(requested)) throw new SecurityException("attachment_selection_not_delegated");
        return selected.session();
    }

    /** 委派只传原受理输入已选择的原件元数据；不复制正文或信任模型指定的源会话。 */
    public Map<String, Object> delegationContext(RunIdentity parent) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(parent.runRef().tenantId().value());
            var selected = selected(parent, new HashSet<>());
            if (selected.ids().isEmpty()) return Map.of();
            var rows = metadataById(parent.runRef().tenantId().value(), selected.session(), selected.ids());
            var metadata = selected.ids().stream().map(id -> metadata(requireRow(parent.runRef().tenantId().value(), selected.session(), rows.get(id))).publicValue()).toList();
            return Map.of("attachment_ids", selected.ids(), "attachments", metadata);
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    /** 原始受理输入限定的选择器；session 仅由可信父链推导，不能由模型指定。 */
    private record Selected(String session, List<String> ids) { }

    private Selected selected(RunIdentity run, Set<RunRef> visited) {
        run = programs.getObject().resourceOrigin(run);
        var binding = bindings.find(run.runRef()).orElseThrow(() -> new SecurityException("attachment_run_binding_missing"));
        if (!binding.owner().equals(run.owner()) || !binding.actor().equals(run.agentId())) throw new SecurityException("attachment_run_owner_mismatch");
        bindings.authorizeRead(binding, run.agentId().value());
        return selectedInput(run, visited, binding);
    }

    /** binding 已在当前读取或传输边界授权；只在本次调用内复用，不缓存授权。 */
    private Selected selectedInput(RunIdentity run, Set<RunRef> visited, AgentRunBindings.Binding binding) {
        if (visited.size() >= 32 || !visited.add(run.runRef())) throw new SecurityException("attachment_parent_chain_invalid");
        var actual = executions.load(run.runRef()).orElseThrow(() -> new SecurityException("attachment_input_missing"));
        if (!actual.identity().equals(run)) throw new SecurityException("attachment_input_owner_mismatch");
        var input = executions.originalInput(run.runRef()).orElseThrow(() -> new SecurityException("attachment_input_missing"));
        List<String> selected;
        try {
            var root = json.readTree(codec.frozenInput(input.content()).canonicalJson());
            if (root == null || !root.isObject()) throw new SecurityException("attachment_input_invalid");
            var value = root.path("extraParameters").path("attachment_ids");
            selected = value.isMissingNode() ? List.of() : ids(json.convertValue(value, Object.class));
        } catch (IOException invalid) { throw new SecurityException("attachment_input_invalid"); }
        if (binding.parentRunRef().isEmpty()) return new Selected(run.owner().sessionRef().sessionId(), selected);
        // 只沿宿主持久化的父关系取原件，并逐层核验租户、终端用户与当前授权。
        var parentRef = binding.parentRunRef().orElseThrow();
        var parentBinding = bindings.find(parentRef).orElseThrow(() -> new SecurityException("attachment_parent_missing"));
        if (!parentRef.tenantId().equals(run.runRef().tenantId())
                || !parentBinding.owner().sessionRef().tenantId().equals(parentRef.tenantId())
                || !parentBinding.owner().requireUser().equals(run.owner().requireUser()))
            throw new SecurityException("attachment_parent_owner_mismatch");
        var parent = executions.load(parentRef).orElseThrow(() -> new SecurityException("attachment_parent_missing"));
        if (!parent.identity().runRef().equals(parentRef) || !parent.identity().owner().equals(parentBinding.owner()))
            throw new SecurityException("attachment_parent_owner_mismatch");
        var origin = selected(parent.identity(), visited);
        if (!origin.ids().containsAll(selected)) throw new SecurityException("attachment_selection_not_delegated");
        return new Selected(origin.session(), selected);
    }

    /** 后台执行复验可信 Run 的不可变归属与当前请求者范围，不借用 HTTP 登录线程。 */
    private record ResourceAuthorization(RunIdentity origin, AgentRunBindings.Binding binding) { }

    private ResourceAuthorization authorizeExecution(PlatformToolExecutionContext context) {
        var run = context.run();
        var actual = executions.load(run.runRef()).orElseThrow(() -> new SecurityException("attachment_run_missing"));
        if (!actual.identity().equals(run)) throw new SecurityException("attachment_run_owner_mismatch");
        var origin = programs.getObject().resourceOrigin(run);
        var binding = bindings.find(origin.runRef())
                .orElseThrow(() -> new SecurityException("attachment_run_binding_missing"));
        if (!binding.owner().equals(origin.owner()) || !binding.actor().equals(origin.agentId()))
            throw new SecurityException("attachment_run_owner_mismatch");
        bindings.authorizeRead(binding, origin.agentId().value());
        return new ResourceAuthorization(origin, binding);
    }

    /** 批量只读取元数据；调用方按原输入顺序校验，保持路径与大小错误的优先级。 */
    private Map<String, SessionAttachmentRow> metadataById(String tenant, String session, List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        var rows = new HashMap<String, SessionAttachmentRow>();
        for (var row : mapper.findMetadataBatch(tenant, session, ids)) rows.put(row.id(), row);
        return rows;
    }

    private SessionAttachmentRow require(String tenant, String session, String id) {
        requireId(id);
        return requireRow(tenant, session, mapper.find(tenant, session, id));
    }
    private static SessionAttachmentRow requireRow(String tenant, String session, SessionAttachmentRow row) {
        if (row == null || !tenant.equals(row.tenantId()) || !session.equals(row.sessionId())
                || !row.expiresAt().isAfter(Instant.now()))
            throw new ApiException(HttpStatus.NOT_FOUND, "附件不存在或已过期");
        return row;
    }

    private static List<String> ids(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > ChatAttachmentLimits.MAX_FILES_PER_MESSAGE) throw invalid();
        var ids = new ArrayList<String>();
        for (Object value : list) {
            if (!(value instanceof String id)) throw invalid();
            requireId(id);
            if (ids.contains(id)) throw invalid();
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static void requireId(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ApiException uploadRejected(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SESSION_ATTACHMENT_UPLOAD_REJECTED", message, false);
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, INVALID_MESSAGE);
    }
    private static Metadata metadata(SessionAttachmentRow row) {
        return new Metadata(row.id(), row.name(), row.inputPath(), row.size(), row.sha256(), row.expiresAt());
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Metadata(String id, String name, String inputPath, long size, String sha256, Instant expiresAt) {
        public FrozenMetadata publicValue() {
            return new FrozenMetadata(id, name, inputPath, size, sha256, expiresAt.toString());
        }
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FrozenMetadata(String id, String name, String inputPath, long size, String sha256, String expiresAt) { }
    /** complete 只代表此附件全文已核验；not_read 不携带正文，原因明确且不冒充截断全文。 */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record TextReading(String id, String status, boolean truncated, String text, String reason) {
        @Override public String toString() { return "AttachmentTextReading[REDACTED]"; }
    }
    public record Resolved(List<ExecutionInputSource> files, List<Metadata> attachments) { }
}
