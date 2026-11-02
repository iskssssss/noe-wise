package com.skillforge.institution.media;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeProgramTools;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.MediaPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 图片元数据由会话附件拥有；租户内二进制正文只保存一次，冻结引用受持久使用标记保护。 */
@Service
public class SessionMediaService implements MediaPort {
    private final SessionMediaMapper mapper;
    private final ArtifactAccess access;
    private final AgentRunBindings bindings;
    private final SessionAttachmentBodyStore bodies;
    private final ExecutionStore executions;
    private final org.springframework.beans.factory.ObjectProvider<AgentRuntimeProgramTools> programs;
    public SessionMediaService(SessionMediaMapper mapper, ArtifactAccess access, AgentRunBindings bindings,
            SessionAttachmentBodyStore bodies,ExecutionStore executions,org.springframework.beans.factory.ObjectProvider<AgentRuntimeProgramTools> programs) {
        this.mapper = mapper; this.access = access; this.bindings = bindings;
        this.bodies = Objects.requireNonNull(bodies);
        this.executions=Objects.requireNonNull(executions);this.programs=Objects.requireNonNull(programs);
    }

    @Transactional
    public MediaView upload(String session, String uploadId, MultipartFile file) throws java.io.IOException {
        String tenant = access.tenant();
        access.authorizeWritableCurrent(tenant, session);
        requireId(uploadId);
        if (file == null || file.isEmpty() || file.getSize() > SessionMediaImages.MAX_BYTES) throw invalid();
        byte[] bytes;
        try (var input = file.getInputStream()) { bytes = input.readNBytes(SessionMediaImages.MAX_BYTES + 1); }
        return registerUpload(tenant, session, uploadId, bytes);
    }

    /** 服务身份上传原始图片字节；归属、幂等和像素解码继续由同一登记边界核验。 */
    @Transactional
    public MediaView upload(String session, String uploadId, byte[] bytes) {
        String tenant = access.tenant();
        access.authorizeWritableCurrent(tenant, session);
        requireId(uploadId);
        return registerUpload(tenant, session, uploadId, bytes);
    }

    private MediaView registerUpload(String tenant, String session, String uploadId, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > SessionMediaImages.MAX_BYTES) throw invalid();
        return view(register(new SessionRef(new TenantId(tenant), session), uploadId, "图片", bytes));
    }

    /** 只按原上传标识和当前会话读取已完成的图片；未找到不代表上传失败。 */
    @Transactional(readOnly = true)
    public UploadReceipt uploadReceipt(String session, String uploadId) {
        var owner = current(session);
        requireId(uploadId);
        return inTenant(owner, () -> {
            var source = mapper.find(owner.tenantId().value(), session, sourceId(owner, uploadId));
            if (source == null || source.runId() != null || !"source-v1".equals(source.transform()) || !session.equals(source.productSessionId()))
                return new UploadReceipt("UNKNOWN", null);
            var normalized = mapper.find(owner.tenantId().value(), session, variantId(source.reference(), "normalize-v1"));
            if (normalized == null || !source.id().equals(normalized.sourceId()) || normalized.runId() != null || !"normalize-v1".equals(normalized.transform())
                    || !session.equals(normalized.productSessionId()))
                return new UploadReceipt("UNKNOWN", null);
            return new UploadReceipt("FOUND", view(normalized.reference()));
        });
    }

    /** 工具和浏览器以可信 Run 的 owner 注册，同一稳定标识只能绑定同一份原始字节。 */
    @Transactional
    public MediaRef register(SessionRef owner, String stableId, String name, byte[] bytes) {
        return register(owner, owner.sessionId(), null, stableId, name, bytes);
    }

    /** 二进制随产品会话保留，逻辑引用仍严格归属于实际执行工具的 Runtime 会话。 */
    @Transactional
    public MediaRef registerForRun(SessionRef owner, RunRef run, String stableId, String name, byte[] bytes) {
        if (!owner.tenantId().equals(run.tenantId())) throw new SecurityException("media_run_owner_mismatch");
        var binding = inTenant(owner, () -> binding(owner, run.runId()));
        return inTenant(owner, () -> pin(owner, register(owner,
                binding.scope().origin().sessionRef().sessionId(), run.runId(), stableId, name, bytes)));
    }

    @Transactional
    public Optional<MediaRef> findRegistered(SessionRef owner, String stableId) {
        return inTenant(owner, () -> {
            bodies.lockTenant(owner.tenantId().value());
            var source = mapper.find(owner.tenantId().value(), owner.sessionId(), sourceId(owner, stableId));
            if (source == null) return Optional.empty();
            return Optional.ofNullable(mapper.find(owner.tenantId().value(), owner.sessionId(),
                    variantId(source.reference(), "normalize-v1"))).map(row -> pin(owner, row.reference()));
        });
    }

    public String productSession(MediaRef ref) {
        return inTenant(ref.owner(), () -> require(ref.owner(), ref.id()).productSessionId());
    }

    /** 产品会话授权后报告共享正文计量，委派逻辑会话不构成跨产品授权。 */
    public SessionAttachmentBodyStore.SessionStorageBudget storageBudget(String session) {
        var owner = current(session);
        return inTenant(owner, () -> bodies.inspectSession(owner.tenantId().value(), session));
    }

    /** HTTP 展示必须先授权产品会话，再核对委派来源的当前可读权限。 */
    @Transactional
    public MediaRef visible(String session, String id) {
        var owner = current(session);
        requireId(id);
        return inTenant(owner, () -> {
            bodies.lockTenant(owner.tenantId().value());
            var row = mapper.findProduct(owner.tenantId().value(), session, id);
            if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "media_not_found");
            if (!row.sessionId().equals(session)) {
                var binding = binding(row.reference().owner(), row.runId());
                if (!binding.scope().origin().sessionRef().equals(owner)) throw new SecurityException("media_origin_mismatch");
                bindings.authorizeRead(binding, binding.actor().value());
            }
            return pin(row.reference().owner(), row.reference());
        });
    }

    private AgentRunBindings.Binding binding(SessionRef owner, String runId) {
        var actual=new RunRef(owner.tenantId(),Objects.requireNonNull(runId));
        var execution=executions.load(actual).orElseThrow(()->new SecurityException("media_run_missing"));
        if(!execution.identity().runRef().equals(actual)||!execution.identity().owner().sessionRef().equals(owner))throw new SecurityException("media_run_owner_mismatch");
        var origin=programs.getObject().resourceOrigin(execution.identity());
        if(!origin.owner().equals(execution.identity().owner())||!origin.agentId().equals(execution.identity().agentId()))throw new SecurityException("media_run_owner_mismatch");
        var binding = bindings.find(origin.runRef())
                .orElseThrow(() -> new SecurityException("media_run_missing"));
        if (!binding.owner().equals(origin.owner())||!binding.actor().equals(origin.agentId())||!binding.owner().sessionRef().equals(owner)) throw new SecurityException("media_run_owner_mismatch");
        bindings.authorizeRead(binding,binding.actor().value());
        return binding;
    }

    private MediaRef register(SessionRef owner, String productSession, String runId, String stableId, String name, byte[] bytes) {
        return inTenant(owner, () -> {
            var decoded = SessionMediaImages.decode(bytes);
            String sourceId = sourceId(owner, stableId);
            var source = store(owner, productSession, runId, sourceId, name, bytes, decoded.mime(), decoded.pixels().getWidth(),
                    decoded.pixels().getHeight(), null, "source-v1");
            var normalized = SessionMediaImages.encode(SessionMediaImages.normalize(decoded), "image/png");
            return store(owner, productSession, runId, variantId(source, "normalize-v1"), name, normalized.bytes(), normalized.mime(),
                    normalized.width(), normalized.height(), source.id(), "normalize-v1");
        });
    }

    @Transactional
    public MediaRef requireCurrent(String session, String id) {
        return reference(current(session), id);
    }

    @Transactional
    public MediaRef reference(SessionRef owner, String id) {
        return inTenant(owner, () -> {
            bodies.lockTenant(owner.tenantId().value());
            return pin(owner, require(owner, id).reference());
        });
    }

    @Override @Transactional public byte[] read(SessionRef owner, MediaRef ref) {
        requireOwner(owner, ref);
        return inTenant(owner, () -> {
            bodies.lockTenant(owner.tenantId().value());
            var row = require(owner, ref.id());
            var stored = mapper.readBytes(owner.tenantId().value(), owner.sessionId(), ref.id());
            byte[] bytes = stored == null ? null : stored.content();
            if (!ref.equals(row.reference()) || bytes == null || bytes.length != ref.byteLength()
                    || !SessionMediaImages.digest(bytes).equals(ref.sha256()))
                throw new IllegalStateException("media_content_changed");
            pin(owner, ref);
            return bytes;
        });
    }

    @Override @Transactional
    public MediaRef prepare(SessionRef owner, MediaRef ref, ModelImagePolicy policy) {
        requireOwner(owner, ref);
        if (!policy.enabled()) throw new IllegalArgumentException("model_images_unsupported");
        String transform = SessionMediaImages.TRANSFORM_VERSION + ":" + policy.version() + ":"
                + policy.maxDimension() + ":" + policy.maxImageBytes() + ":jpeg85-or-alpha-png";
        return inTenant(owner, () -> {
            bodies.lockTenant(owner.tenantId().value());
            var sourceRow = require(owner, ref.id());
            if (!sourceRow.reference().equals(ref)) throw new IllegalStateException("media_content_changed");
            pin(owner, ref);
            String id = variantId(ref, transform);
            var saved = mapper.find(owner.tenantId().value(), owner.sessionId(), id);
            if (saved != null) return pin(owner, saved.reference());
            var prepared = SessionMediaImages.variant(SessionMediaImages.decode(read(owner, ref)).pixels(), policy);
            return pin(owner, store(owner, sourceRow.productSessionId(), sourceRow.runId(), id, "模型请求图片", prepared.bytes(), prepared.mime(), prepared.width(),
                    prepared.height(), ref.id(), transform));
        });
    }

    /** 裁剪坐标相对于用户看到的方向规范化原图；新引用保持原图不变。 */
    @Transactional
    public CropResult crop(SessionRef owner, MediaRef source, Crop crop) {
        requireOwner(owner, source);
        if (crop == null) {
            var current = reference(owner, source.id());
            if (!current.equals(source)) throw new IllegalStateException("media_content_changed");
            return new CropResult(source, current, new Crop(0, 0, source.width(), source.height()));
        }
        if (crop.x() < 0 || crop.y() < 0 || crop.width() <= 0 || crop.height() <= 0
                || (long) crop.x() + crop.width() > source.width()
                || (long) crop.y() + crop.height() > source.height()) throw invalid();
        return inTenant(owner, () -> {
            bodies.lockTenant(owner.tenantId().value());
            var sourceRow = require(owner, source.id());
            if (!sourceRow.reference().equals(source)) throw new IllegalStateException("media_content_changed");
            pin(owner, source);
            String transform = "crop-v1:" + crop.x() + ":" + crop.y() + ":" + crop.width() + ":" + crop.height();
            String id = variantId(source, transform);
            var existing = mapper.find(owner.tenantId().value(), owner.sessionId(), id);
            if (existing != null) return new CropResult(source, pin(owner, existing.reference()), crop);
            var pixels = SessionMediaImages.decode(read(owner, source)).pixels();
            var image = SessionMediaImages.encode(pixels.getSubimage(crop.x(), crop.y(), crop.width(), crop.height()), "image/png");
            return new CropResult(source, pin(owner, store(owner, sourceRow.productSessionId(), sourceRow.runId(), id, "裁剪图片", image.bytes(), image.mime(),
                    image.width(), image.height(), source.id(), transform)), crop);
        });
    }

    /** 入站只接受媒体选择器；摘要、尺寸、存储位置均从服务端重新解析。 */
    @Transactional
    public List<ResolvedContent> resolveContent(String session, List<ChatContent> content) {
        return resolveContent(current(session), content);
    }

    /**
     * 已由群入口核验当前主体、群和作者后解析同一 Session 的媒体；不借个人访问入口推导群归属。
     * 显式 owner 仍与当前租户绑定，媒体引用和正文从原会话的权威存储重新解析。
     */
    @Transactional
    public List<ResolvedContent> resolveContent(SessionRef owner, List<ChatContent> content) {
        Objects.requireNonNull(owner);
        if (!owner.tenantId().value().equals(TenantContext.getTenantId()))
            throw new SecurityException("media_tenant_mismatch");
        var parts = new ArrayList<ResolvedContent>();
        for (var part : content) {
            switch (part) {
                case ChatContent.Text text -> parts.add(new TextContent("text", text.text()));
                case ChatContent.Image image -> parts.add(content(reference(owner, image.mediaId())));
            }
        }
        return List.copyOf(parts);
    }

    public static ImageContent content(MediaRef ref) {
        return new ImageContent("image", "HIGH", new MediaValue(
                new MediaOwner(ref.owner().tenantId().value(), ref.owner().sessionId()), ref.id(),
                ref.sha256(), ref.mimeType(), ref.byteLength(), ref.width(), ref.height()));
    }

    public static MediaView view(MediaRef ref) {
        return new MediaView(ref.id(), ref.mimeType(), ref.byteLength(), ref.width(), ref.height());
    }
    private MediaRef store(SessionRef owner, String productSession, String runId, String id, String name, byte[] bytes, String mime,
            int width, int height, String source, String transform) {
        if (bytes.length > SessionMediaImages.MAX_BYTES) throw invalid();
        var row = new SessionMediaRow(id, owner.tenantId().value(), owner.sessionId(), productSession, runId, name,
                bytes.length, SessionMediaImages.digest(bytes), mime, width, height, bytes, source, transform, Instant.now());
        bodies.register(owner.tenantId().value(), productSession, row.sha256(), bytes);
        var existing = mapper.find(owner.tenantId().value(), owner.sessionId(), id);
        if (existing == null && mapper.insert(row) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "media_operation_conflict");
        var stored = require(owner, id);
        if (!stored.reference().equals(row.reference()) || !Objects.equals(stored.sourceId(), source)
                || !stored.transform().equals(transform) || !stored.productSessionId().equals(productSession)
                || !Objects.equals(stored.runId(), runId))
            throw new ApiException(HttpStatus.CONFLICT, "media_operation_conflict");
        return stored.reference();
    }
    /** 第一份读取/冻结意图保护媒体到会话删除；后续读取不写无变化 UPDATE。 */
    private MediaRef pin(SessionRef owner, MediaRef ref) {
        mapper.pin(owner.tenantId().value(), owner.sessionId(), ref.id());
        return ref;
    }
    private SessionMediaRow require(SessionRef owner, String id) {
        requireId(id);
        var row = mapper.find(owner.tenantId().value(), owner.sessionId(), id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "media_not_found");
        return row;
    }
    private SessionRef current(String session) {
        String tenant = access.tenant();
        access.authorizeCurrent(tenant, session);
        return new SessionRef(new TenantId(tenant), session);
    }
    private static String sourceId(SessionRef owner, String stableId) {
        return UUID.nameUUIDFromBytes((owner + ":source:" + stableId).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static String variantId(MediaRef source, String transform) {
        return UUID.nameUUIDFromBytes((source.owner() + ":" + source.id() + ":" + source.sha256() + ":" + transform)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static void requireOwner(SessionRef owner, MediaRef ref) {
        if (!owner.equals(ref.owner())) throw new IllegalArgumentException("media_owner_mismatch");
    }
    private static void requireId(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw invalid();
    }
    private static <T> T inTenant(SessionRef owner, Supplier<T> action) {
        String previous = TenantContext.getTenantId();
        try { TenantContext.setTenantId(owner.tenantId().value()); return action.get(); }
        finally { if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("image_content_invalid"); }
    public record Crop(int x, int y, int width, int height) { }
    public record CropResult(MediaRef source, MediaRef image, Crop crop) { }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type", visible = true)
    @JsonSubTypes({@JsonSubTypes.Type(value = TextContent.class, name = "text"),
            @JsonSubTypes.Type(value = ImageContent.class, name = "image")})
    public sealed interface ResolvedContent permits TextContent, ImageContent { }
    @com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
    public record TextContent(String type, String text) implements ResolvedContent { }
    @com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
    public record ImageContent(String type, String detail, MediaValue media) implements ResolvedContent { }
    @com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
    public record MediaOwner(@com.fasterxml.jackson.annotation.JsonProperty("tenantId") String tenantId,
            @com.fasterxml.jackson.annotation.JsonProperty("sessionId") String sessionId) { }
    @com.fasterxml.jackson.annotation.JsonPropertyOrder(alphabetic = true)
    public record MediaValue(MediaOwner owner, String id, String sha256,
            @com.fasterxml.jackson.annotation.JsonProperty("mimeType") String mimeType,
            @com.fasterxml.jackson.annotation.JsonProperty("byteLength") long byteLength, int width, int height) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MediaView(String mediaId, String mimeType, long byteLength, int width, int height) { }
    public record UploadReceipt(String status, MediaView media) { }
}
