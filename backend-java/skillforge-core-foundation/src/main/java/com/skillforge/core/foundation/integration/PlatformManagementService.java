package com.skillforge.core.foundation.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.skillforge.core.foundation.integration.ManagementContracts.*;

/**
 * 平台人员和独立管理机器共用的当前动作/目标授权边界，以及人员专属的机器身份生命周期。
 * 不取得机构执行身份，不持有全局 IAM 锁；每次管理边界从当前权威记录判断。
 */
@Service
public class PlatformManagementService {
    private static final Logger log = LoggerFactory.getLogger(PlatformManagementService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final FoundationAccess access;
    private final ManagementMapper mapper;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final AuditService audit;

    /** 使用现有 FoundationAccess、加密和事务审计，不创建第二套人员角色系统。 */
    public PlatformManagementService(FoundationAccess access, ManagementMapper mapper, CryptoService crypto,
            ObjectMapper json, AuditService audit) {
        this.access = access; this.mapper = mapper; this.crypto = crypto; this.json = json; this.audit = audit;
    }

    /** 只校验当前精确动作；调用方仍须按对象种类检查目标。 */
    public void requireAction(String action) { platform(() -> { current(action); return null; }); }

    /** 当前主体必须同时具备精确动作与明确机构目标；不能从正文设置机构身份。 */
    public void requireTenant(String action, long tenantId) { requireTarget(action, "tenant", tenantId); }

    /** 已有定义要求精确动作和定义目标，源维护权不能隐含部署目标机构权。 */
    public void requireDefinition(String action, long definitionId) { requireTarget(action, "agent_definition", definitionId); }

    /** 检查创建或关联的命名空间；空目标及通配符不获得任何权限。 */
    public void requireNamespace(String action, String namespace) {
        String target = namespace(namespace);
        platform(() -> {
            Current current = current(action);
            if (current.machine() != null) {
                if (!current.machine().getTargetNamespaces().contains(target)) throw forbidden("没有目标命名空间权限");
            } else if (!current.person().dataScope().all()
                    && !mapper.namespaceAllowed(target, action, current.person().dataScope())) throw forbidden("没有目标命名空间权限");
            return null;
        });
    }

    /** 精确命名空间候选与是否允许新建，直接来自同一当前管理授权。 */
    public record NamespaceScope(boolean allowCreate, Set<String> namespaces) {
        public NamespaceScope { namespaces = Set.copyOf(namespaces); }
    }

    /** 机器固定目标不可扩展；all 人员可声明新值，其余人员复用 requireNamespace 的授予谓词。 */
    public NamespaceScope authorizedNamespaceScope(String action) {
        return platform(() -> {
            Current current = current(action);
            if (current.machine() != null) return new NamespaceScope(false, Set.copyOf(current.machine().getTargetNamespaces()));
            if (current.person().dataScope().all()) return new NamespaceScope(true, Set.of());
            return new NamespaceScope(false, Set.copyOf(mapper.allowedNamespaces(action, current.person().dataScope())));
        });
    }

    /** 返回当前允许机构的真实 ID 集合，空集表示没有机构，绝不以 null 表示无限制。 */
    public Set<Long> allowedTenantIds(String action) { return allowedTargets(action, "tenant"); }

    /**
     * 同一当前授权投影形成的机构读取范围，供分页游标一次绑定而不拼接多次查询的身份/版本。
     * @param actorRef 可信稳定主体引用
     * @param authorizationVersion 本次当前授权版本
     * @param tenantIds 本次允许的明确机构集合，空集不放行任何机构
     */
    public record TenantScope(String actorRef, long authorizationVersion, Set<Long> tenantIds) {
        /** 调用方不能修改集合扩大本次授权。 */
        public TenantScope { tenantIds = Set.copyOf(tenantIds); }
    }

    /** 为一个用量读取边界共用一次当前人员/机器投影，不建立跨请求授权缓存。 */
    public TenantScope authorizedTenantScope(String action) {
        return platform(() -> {
            Current current = current(action);
            String actor = current.machine() == null ? personRef(current.person()) : "management_principal:" + current.machine().getPrincipalId();
            long version = current.machine() == null ? current.person().snapshot().authorizationVersion() : current.machine().getRevision();
            return new TenantScope(actor, version, allowedTargets(current, action, "tenant"));
        });
    }

    /** 定义影响仅读取同一当前 read 投影内的源与明确机构，维护源不获得机构正文访问权。 */
    public TenantScope authorizedDefinitionReadScope(long definitionId) {
        if (definitionId <= 0) throw invalid("目标 ID 必须为正数");
        return platform(() -> {
            String action = "platform.agent_definitions.read";
            Current current = current(action);
            requireTarget(current, action, "agent_definition", definitionId);
            String actor = current.machine() == null ? personRef(current.person()) : "management_principal:" + current.machine().getPrincipalId();
            long version = current.machine() == null ? current.person().snapshot().authorizationVersion() : current.machine().getRevision();
            return new TenantScope(actor, version, allowedTargets(current, action, "tenant"));
        });
    }

    /** 返回当前允许定义的真实 ID 集合，供先过滤再分页和计数。 */
    public Set<Long> allowedDefinitionIds(String action) { return allowedTargets(action, "agent_definition"); }

    /** 返回可信稳定操作者，不包含 credential_version，以便轮换后仍命中原业务幂等域。 */
    public String actorRef() { return platform(() -> {
        Current current = current(null);
        return current.machine() == null ? personRef(current.person()) : "management_principal:" + current.machine().getPrincipalId();
    }); }

    /** 读取当前授权版本，供游标作用域绑定；旧机器版本不能继续使用。 */
    public long authorizationVersion() { return platform(() -> {
        Current current = current(null);
        return current.machine() == null ? current.person().snapshot().authorizationVersion() : current.machine().getRevision();
    }); }

    /** 人员专用主体列表，当前数据范围在 SQL 中先过滤再分页。 */
    @Transactional(readOnly = true)
    public Page list(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 200) throw invalid("page/page_size 超出允许范围");
        return platform(() -> {
            var person = person("platform.management_principals.read");
            return new Page(mapper.principals(person.dataScope(), pageSize, ((long) page - 1) * pageSize)
                    .stream().map(this::view).toList(), mapper.count(person.dataScope()), page, pageSize);
        });
    }

    /**
     * 平台人员签发独立身份；主体、固定动作/目标、安全原回执和审计同事务完成。
     * 同键重读只返回安全回执，结果未知不得重新签发原秘密。
     */
    @Transactional(rollbackFor = Exception.class)
    public Issued create(Create request, String idempotencyKey) {
        return platform(() -> {
            var person = person("platform.management_principals.create");
            Create command = normalize(request);
            // 签发者只能授予自身当前动作和目标；已验证 JWT 的旧角色不能充当授权事实。
            if (!person.snapshot().permissions().containsAll(command.scopes())) throw forbidden("不能授予当前人员没有的管理动作");
            validateTargets(person, command);
            String key = text(idempotencyKey, 128, "Idempotency-Key");
            String actor = personRef(person);
            String fingerprint = fingerprint(command);
            Issued original = claim(actor, "create", "new", key, fingerprint);
            if (original != null) return original;
            Credentials credentials = credentials();
            ManagementRows.Principal row = new ManagementRows.Principal();
            row.setPrincipalId(SnowflakeIds.nextId()); row.setName(command.name()); row.setStatus("active");
            row.setRevision(1L); row.setCredentialVersion(1L); row.setKeyHash(TokenHash.sha256Hex(credentials.key()));
            row.setKeyPrefix(credentials.key().substring(0, 12)); row.setSigningSecretEncrypted(crypto.encrypt(credentials.secret()));
            row.setCreatedByRef(actor); row.setCreatedAt(Instant.now()); row.setUpdatedAt(row.getCreatedAt());
            mapper.insertPrincipal(row); mapper.insertScopes(row.getPrincipalId(), sorted(command.scopes()));
            insertTargets(row.getPrincipalId(), "tenant", command.targetTenantIds());
            insertTargets(row.getPrincipalId(), "agent_definition", command.targetDefinitionIds());
            insertTargets(row.getPrincipalId(), "namespace", command.targetNamespaces());
            Principal result = view(requiredPrincipal(row.getPrincipalId()));
            complete(actor, "create", "new", key, result);
            audit.record(null, person.subject().memberId(), "platform.management_principals.create", "management_principal",
                    result.principalId(), "签发受限平台管理身份", Map.of("revision", result.revision(), "scope_count", result.scopes().size()));
            log.info("平台管理身份已签发 principalId={} revision={}", result.principalId(), result.revision());
            return new Issued(result, credentials.key(), credentials.secret(), false);
        });
    }

    /** 原子 CAS 轮换；同一请求重读原安全版本而不再返回秘密，不修改 scope/目标。 */
    @Transactional(rollbackFor = Exception.class)
    public Issued rotate(Command command, String idempotencyKey) { return mutate(command, idempotencyKey, false); }

    /** CAS 撤销为终态，失效旧管理 Key 并保留安全原操作回执。 */
    @Transactional(rollbackFor = Exception.class)
    public Issued revoke(Command command, String idempotencyKey) { return mutate(command, idempotencyKey, true); }

    /**
     * 验签完整 requestTarget 含原始 query，成功只返回独立身份，不设置 Tenant/执行身份。
     * 固定路由、当前主体状态、scope 和时间窗全部允许后才能建立管理认证。
     */
    public ManagementContext.Identity authenticate(String key, String timestamp, String signature,
            String method, String requestTarget, String body) {
        return platform(() -> {
            String path = requestTarget == null ? "" : requestTarget.split("\\?", 2)[0];
            String action = ManagementActions.action(method, path);
            if (action == null) throw forbidden("管理凭据不能进入该路径");
            if (key == null || key.isBlank() || timestamp == null || !timestamp.matches("[0-9]{1,12}")) throw unauthorized("缺少有效管理凭据");
            long epoch = Long.parseLong(timestamp), now = Instant.now().getEpochSecond();
            if (epoch < now - 300 || epoch > now + 300) throw unauthorized("请求时间戳超出允许偏差");
            var row = mapper.forAuthentication(TokenHash.sha256Hex(key));
            if (row == null || !"active".equals(row.getStatus())) throw unauthorized("管理凭据无效或已撤销");
            if (!row.getScopes().contains(action)) throw forbidden("管理凭据没有当前动作");
            String canonical = timestamp + "\n" + method + "\n" + requestTarget + "\n" + TokenHash.sha256Hex(body == null ? "" : body);
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(crypto.decrypt(row.getSigningSecretEncrypted()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] expected = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
                if (signature == null || !MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) throw unauthorized("管理签名校验失败");
            } catch (ApiException failure) { throw failure; }
            catch (Exception failure) { throw unauthorized("管理凭据不可用"); }
            return new ManagementContext.Identity(row.getPrincipalId().toString(), row.getCredentialVersion(), row.getRevision());
        });
    }

    /** 复用一个当前投影完成同一对象授权，所有拒绝先于业务副作用。 */
    private void requireTarget(String action, String kind, long id) {
        if (id <= 0) throw invalid("目标 ID 必须为正数");
        platform(() -> {
            Current current = current(action);
            requireTarget(current, action, kind, id);
            return null;
        });
    }

    private void requireTarget(Current current, String action, String kind, long id) {
        if (current.machine() != null) {
            List<String> targets = "tenant".equals(kind) ? current.machine().getTargetTenantIds() : current.machine().getTargetDefinitionIds();
            if (!targets.contains(Long.toString(id))) throw forbidden("没有目标对象权限");
            if (!mapper.targetExists(kind, id)) throw missing("目标对象不存在");
        } else if (!mapper.targetAllowed(kind, id, objectAction(action), current.person().dataScope())) throw forbidden("没有目标对象权限");
    }

    /** 列表范围使用当前权限和明确目标，不把后续分页结果当成授权过滤。 */
    private Set<Long> allowedTargets(String action, String kind) {
        return platform(() -> allowedTargets(current(action), action, kind));
    }

    /** 同一步已经加载的当前投影不重复访问 IAM，机器目标取同一条权威聚合结果。 */
    private Set<Long> allowedTargets(Current current, String action, String kind) {
        if (current.machine() != null) {
            List<String> targets = "tenant".equals(kind) ? current.machine().getTargetTenantIds() : current.machine().getTargetDefinitionIds();
            return targets.stream().map(Long::parseLong).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return Set.copyOf(mapper.allowedTargets(kind, objectAction(action), current.person().dataScope()));
    }

    /** 当前主体只可为人员或独立管理机器，机构 ServicePrincipal 不能反向进入平台。 */
    private Current current(String action) {
        if (ServicePrincipalContext.get() != null) throw forbidden("机构执行身份不能进入平台管理");
        var identity = ManagementContext.get();
        if (identity == null) {
            var person = access.currentPlatformAuthorization();
            if (action != null && !person.snapshot().permissions().contains(action)) throw forbidden("没有当前平台动作");
            return new Current(person, null);
        }
        var row = mapper.principal(id(identity.principalId(), "principal_id"));
        if (row == null || !"active".equals(row.getStatus()) || row.getCredentialVersion() != identity.credentialVersion()
                || row.getRevision() != identity.authorizationVersion()) throw unauthorized("管理身份已失效或授权版本变化");
        if (action != null && (!ManagementActions.SCOPES.contains(action) || !row.getScopes().contains(action))) throw forbidden("没有当前平台动作");
        return new Current(null, row);
    }

    /** 生命周期入口只接受当前真实平台人员，机器不能签发或放大自身权限。 */
    private FoundationAccess.PlatformAuthorization person(String action) {
        if (ManagementContext.get() != null || ServicePrincipalContext.get() != null) throw forbidden("仅平台人员可以管理机器身份");
        return current(action).person();
    }

    /** 变更按原操作回执重读后才核验 CAS，原成功不能因后续轮换变成新操作。 */
    private Issued mutate(Command command, String idempotencyKey, boolean revoke) {
        return platform(() -> {
            String operation = revoke ? "revoke" : "rotate";
            var person = person("platform.management_principals." + operation);
            if (command == null || command.expectedRevision() == null || command.expectedRevision() < 1) throw invalid("expected_revision 必填且必须为正数");
            long id = id(command.principalId(), "principal_id");
            if (!mapper.principalAllowed(id, person.dataScope())) throw forbidden("没有目标管理身份权限");
            String key = text(idempotencyKey, 128, "Idempotency-Key"), actor = personRef(person), scope = Long.toString(id);
            Issued original = claim(actor, operation, scope, key, fingerprint(command));
            if (original != null) return original;
            ManagementRows.Principal row = requiredPrincipal(id);
            if (!"active".equals(row.getStatus()) || !row.getRevision().equals(command.expectedRevision())) throw conflict("管理主体修订变化或已撤销");
            Credentials credentials = revoke ? null : credentials();
            int changed = revoke ? mapper.revoke(id, command.expectedRevision())
                    : mapper.rotate(id, command.expectedRevision(), TokenHash.sha256Hex(credentials.key()), credentials.key().substring(0, 12), crypto.encrypt(credentials.secret()));
            if (changed != 1) throw conflict("管理主体修订竞争");
            Principal result = view(requiredPrincipal(id));
            complete(actor, operation, scope, key, result);
            audit.record(null, person.subject().memberId(), "platform.management_principals." + operation, "management_principal",
                    scope, revoke ? "撤销平台管理身份" : "轮换平台管理凭据", Map.of("revision", result.revision()));
            log.info("平台管理身份变更 principalId={} operation={} revision={}", id, operation, result.revision());
            return new Issued(result, credentials == null ? null : credentials.key(), credentials == null ? null : credentials.secret(), false);
        });
    }

    /** 不扩大人员当前目标范围，规范化后的集合已限制数量。 */
    private void validateTargets(FoundationAccess.PlatformAuthorization person, Create command) {
        for (String target : command.targetTenantIds()) checkTarget(person, "tenant", id(target, "target_tenant_ids"));
        for (String target : command.targetDefinitionIds()) checkTarget(person, "agent_definition", id(target, "target_definition_ids"));
        for (String target : command.targetNamespaces()) {
            for (String action : command.scopes().stream().filter(s -> s.equals("platform.institutions.provision") || s.equals("platform.institutions.associate")).toList()) {
                if (!person.dataScope().all() && !mapper.namespaceAllowed(target, action, person.dataScope())) throw forbidden("不能授予当前人员没有的命名空间");
            }
            if (!person.dataScope().all() && command.scopes().stream().noneMatch(s -> s.equals("platform.institutions.provision") || s.equals("platform.institutions.associate"))) throw forbidden("当前人员没有该命名空间授权");
        }
    }

    /** 管理凭据只能绑定真实存在且当前人员有 manage 目标权的对象。 */
    private void checkTarget(FoundationAccess.PlatformAuthorization person, String kind, long id) {
        if (!mapper.targetAllowed(kind, id, "manage", person.dataScope())) throw forbidden("不能授予当前人员没有的目标范围");
    }

    /** 插入非空目标集合，避免空 VALUES 及空集合默认放大。 */
    private void insertTargets(long id, String kind, Set<String> targets) {
        if (!targets.isEmpty()) mapper.insertTargets(id, kind, sorted(targets));
    }

    /** 原请求占有唯一行；完整安全结果与业务事实同事务提交，不持有全局锁。 */
    private Issued claim(String actor, String operation, String scope, String key, String fingerprint) {
        mapper.claimReceipt(actor, operation, scope, key, fingerprint);
        var row = mapper.receipt(actor, operation, scope, key);
        if (row == null) throw new IllegalStateException("management_receipt_missing");
        if (!fingerprint.equals(row.getFingerprint())) throw conflict("同一幂等键对应不同参数");
        if (row.getResultJson() == null) return null;
        try { return new Issued(json.readValue(row.getResultJson(), Principal.class), null, null, true); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("management_receipt_invalid", failure); }
    }

    /** 仅序列化安全 Principal，不存在秘密进入回执的通道。 */
    private void complete(String actor, String operation, String scope, String key, Principal result) {
        try {
            if (mapper.completeReceipt(actor, operation, scope, key, json.writeValueAsString(result)) != 1) throw new IllegalStateException("management_receipt_not_completed");
        } catch (JsonProcessingException failure) { throw new IllegalStateException("management_receipt_invalid", failure); }
    }

    /** 载入当前持久记录，非法身份不得由展示字段补建。 */
    private ManagementRows.Principal requiredPrincipal(long id) {
        var row = mapper.principal(id);
        if (row == null) throw missing("管理主体不存在");
        return row;
    }

    /** 安全映射显式排除摘要和加密秘密。 */
    private Principal view(ManagementRows.Principal row) {
        return new Principal(row.getPrincipalId().toString(), row.getName(), row.getStatus(), row.getRevision(), row.getCredentialVersion(),
                row.getScopes(), row.getTargetTenantIds(), row.getTargetDefinitionIds(), row.getTargetNamespaces(), row.getCreatedAt(), row.getUpdatedAt());
    }

    /** 严格完整创建协议，不将 null 数组理解成无限目标或默认 scope。 */
    private Create normalize(Create command) {
        if (command == null) throw invalid("缺少管理身份创建参数");
        Set<String> scopes = values(command.scopes(), "scopes");
        if (scopes.isEmpty() || !ManagementActions.SCOPES.containsAll(scopes)) throw invalid("scope 为空或未开放");
        Set<String> tenants = values(command.targetTenantIds(), "target_tenant_ids");
        Set<String> definitions = values(command.targetDefinitionIds(), "target_definition_ids");
        Set<String> namespaces = values(command.targetNamespaces(), "target_namespaces");
        tenants.forEach(target -> id(target, "target_tenant_ids")); definitions.forEach(target -> id(target, "target_definition_ids"));
        namespaces.forEach(PlatformManagementService::namespace);
        if (tenants.isEmpty() && definitions.isEmpty() && namespaces.isEmpty()) throw invalid("至少需要一个明确管理目标");
        return new Create(text(command.name(), 200, "name"), scopes, tenants, definitions, namespaces);
    }

    /** 固定集合不允许 null、重复归一化或通配符；输入保持原字符事实。 */
    private static Set<String> values(Set<String> source, String field) {
        if (source == null || source.size() > 200 || source.stream().anyMatch(v -> v == null || v.isBlank() || v.contains("*"))) throw invalid(field + " 必填且最多包含 200 个明确值");
        return Set.copyOf(source);
    }

    /** 稳定排序参与安全回执和摘要，Set 的迭代顺序不改变幂等语义。 */
    private static List<String> sorted(Set<String> values) { return values.stream().sorted().toList(); }

    /** 完整字段的长度定界摘要，集合排序后不依赖请求 JSON 字段顺序。 */
    private String fingerprint(Object request) {
        Object canonical = request instanceof Create c ? List.of(c.name(), sorted(c.scopes()), sorted(c.targetTenantIds()), sorted(c.targetDefinitionIds()), sorted(c.targetNamespaces())) : request;
        try { return TokenHash.sha256Hex(json.writeValueAsString(canonical)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("management_fingerprint_failed", failure); }
    }

    /** 平台可信账号引用不依赖 JWT 角色或凭据版本。 */
    private static String personRef(FoundationAccess.PlatformAuthorization person) { return "platform_member:" + person.subject().memberId(); }

    /** 对象共享 read/manage 与精确业务动作分别授权，execute 不用于平台管理。 */
    private static String objectAction(String action) { return action.endsWith(".read") ? "read" : "manage"; }

    /** 命名空间保持精确匹配；禁止空白变化和通配符。 */
    private static String namespace(String value) {
        String result = text(value, 128, "namespace");
        if (result.contains("*") || !result.equals(result.strip())) throw invalid("namespace 必须为明确值");
        return result;
    }

    /** 主体/目标 ID 使用十进制字符串，严格检查溢出。 */
    private static long id(String value, String field) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid(field + " 必须为十进制 ID 字符串");
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(field + " 超出 ID 范围"); }
    }

    /** 输入文本按码点计数，不截断或静默裁剪幂等身份。 */
    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) throw invalid(field + " 不能为空或超出长度限制");
        return value;
    }

    /** 管理 Key 和 HMAC secret 独立随机生成，持久化只使用摘要和加密密钥。 */
    private static Credentials credentials() {
        byte[] key = new byte[24], secret = new byte[32]; RANDOM.nextBytes(key); RANDOM.nextBytes(secret);
        return new Credentials("sfmg_" + HexFormat.of().formatHex(key), HexFormat.of().formatHex(secret));
    }

    /** 临时平台数据库上下文在异常及成功路径精确恢复，绝不将治理目标变成执行身份。 */
    private static <T> T platform(Supplier<T> operation) {
        String previous = TenantContext.getTenantId();
        try { TenantContext.clear(); return operation.get(); }
        finally { if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
    }

    /** 输入错误在任何秘密生成或写入前拒绝。 */
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    /** 当前对象范围不足不能以签名正确放行。 */
    private static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, message); }
    /** 当前凭据失效需要重新认证。 */
    private static ApiException unauthorized(String message) { return new ApiException(HttpStatus.UNAUTHORIZED, message); }
    /** 已授权闭包内对象不存在。 */
    private static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, message); }
    /** 幂等/CAS 竞争保留原结果。 */
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }

    /** 单次管理边界的授权投影，两种主体互斥。 */
    private record Current(FoundationAccess.PlatformAuthorization person, ManagementRows.Principal machine) { }
    /** 一次性秘密只在本地调用栈传递，不写入安全回执。 */
    private record Credentials(String key, String secret) { }
}
