package com.skillforge.core.foundation.operations;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOptionService;
import com.skillforge.core.foundation.operations.OperationsContracts.*;
import com.skillforge.core.foundation.operations.persistence.OperationsMapper;
import com.skillforge.core.foundation.operations.persistence.OperationsRows;
import com.skillforge.core.foundation.operations.persistence.AuditExportRow;
import com.skillforge.core.foundation.application.AuditService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 审计检索和导出的公开应用入口；实时授权、作用域绑定、有限导出和访问审计在此统一执行。 */
@Service
public class FoundationAuditService {
    /** 安全事件查询权限。 */ public static final String READ = "foundation.operations.audit_read";
    /** 安全事件导出权限。 */ public static final String EXPORT = "foundation.operations.audit_export";
    /** 同步导出的最大行数，超限必须收窄过滤条件。 */ public static final int EXPORT_LIMIT = 10000;
    private final FoundationAccess access;
    private final OperationsMapper mapper;
    private final AuditService audit;

    /** 注入权威授权和审计持久化组件。 */
    public FoundationAuditService(FoundationAccess access, OperationsMapper mapper, AuditService audit) {
        this.access = access; this.mapper = mapper; this.audit = audit;
    }

    /** 筛选目录与审计列表使用相同权限和作用域，不借用旧的跨机构审计目录。 */
    @Transactional(readOnly = true)
    public AuditFilterOptions options() {
        access.require(READ);
        String scope = access.currentSubject().scope();
        return new AuditFilterOptions(
                mapper.auditFilterValues(scope, "action").stream().map(value -> new AuditFilterOption(value, label(value))).toList(),
                List.of("authentication", "authorization", "sensitive", "export", "operation").stream()
                        .map(value -> new AuditFilterOption(value, label(value))).toList(),
                List.of("success", "failure", "denied").stream().map(value -> new AuditFilterOption(value, label(value))).toList(),
                mapper.auditFilterValues(scope, "resource").stream().map(value -> new AuditFilterOption(value, label(value))).toList());
    }

    /** 只搜索本范围审计已出现的身份，保留非数字操作者与完整追踪标识。 */
    @Transactional(readOnly = true)
    public FoundationOptionService.Page identityOptions(String kind, String keyword, List<String> ids, int page) {
        access.require(READ);
        if (!Set.of("actors", "traces").contains(kind))
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.option_invalid", "未知审计选项类型");
        String text = keyword == null ? "" : keyword.trim();
        List<String> selected = ids == null ? List.of() : List.copyOf(ids);
        if (page < 1 || page > 1000000 || text.length() > 128 || selected.size() > 100)
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.option_invalid", "搜索词、页码或已选数量超出范围");
        selected.forEach(id -> check(id, "actors".equals(kind) ? 128 : 64));
        var query = new FoundationOptionService.Query(text, selected, selected.isEmpty() ? (page - 1) * 50 : 0,
                selected.isEmpty() ? 51 : 101);
        var rows = mapper.auditIdentityOptions(access.currentSubject().scope(), kind, query);
        boolean more = selected.isEmpty() && rows.size() > 50;
        return new FoundationOptionService.Page(more ? rows.subList(0, 50) : rows, more);
    }

    private static String label(String value) {
        return switch (value) {
            case "authentication" -> "认证"; case "authorization" -> "授权";
            case "sensitive" -> "敏感访问"; case "export" -> "导出"; case "operation" -> "业务操作";
            case "success" -> "成功"; case "failure" -> "失败"; case "denied" -> "拒绝";
            case "auth.login" -> "登录"; case "auth.logout" -> "退出登录"; case "auth.refresh" -> "刷新登录会话";
            case "auth.password_change" -> "修改密码"; case "sensitive.audit.read" -> "读取安全审计";
            case "platform.session.messages.read" -> "读取会话正文"; case "trajectory.content.read" -> "读取轨迹正文";
            case "admin.tenant.provision" -> "开通机构"; case "admin.tenant.update" -> "更新机构";
            case "admin.tenant.suspend" -> "停用机构"; case "admin.tenant.activate" -> "启用机构";
            case "admin.tenant_member.create" -> "新增成员"; case "admin.tenant_member.update" -> "更新成员";
            case "admin.tenant_member.reset_password" -> "重置成员密码";
            case "permission.department.create" -> "创建部门"; case "permission.department.update" -> "更新部门";
            case "permission.member.create" -> "创建成员"; case "permission.member.update" -> "更新成员";
            case "permission.position.create" -> "创建岗位"; case "permission.role.create" -> "创建角色";
            case "deliverable.ticket" -> "获取成果访问凭证"; case "deliverable.ticket.used" -> "使用成果访问凭证";
            case "security.session.retention.change" -> "修改登录记录保留期限";
            case "export.audit" -> "导出安全审计";
            case "platform.agent_definitions.create" -> "创建公共定义";
            case "platform.agent_definitions.edit" -> "编辑公共定义";
            case "platform.management_principals.create" -> "签发管理身份";
            case "security_session" -> "安全会话"; case "session" -> "会话";
            case "tenant" -> "机构"; case "tenant_member" -> "机构成员"; case "platform_member" -> "平台成员";
            case "agent" -> "智能体"; case "agent_definition" -> "公共定义";
            case "agent_definition_grant" -> "定义授权"; case "management_principal" -> "管理身份";
            case "knowledge_base" -> "知识库"; case "knowledge_document" -> "知识文档";
            case "browser_authorization" -> "浏览器授权"; case "audit_query" -> "审计查询";
            case "account" -> "账号"; case "member" -> "成员"; case "audit" -> "安全审计";
            case "department" -> "部门"; case "position" -> "岗位"; case "role" -> "角色";
            case "artifact" -> "运行产物"; case "deliverable" -> "成果";
            default -> value;
        };
    }

    /** 查询精确作用域；同主体、授权版本和过滤的一分钟批次与首次读取原子提交。 */
    @Transactional
    public AuditPage query(AuditQuery request) {
        var read = authorizeRead(request);
        AuditQuery query = read.query();
        FoundationAccess.Subject subject = read.subject();
        long total = mapper.auditCount(subject.scope(), query);
        List<AuditEvent> events = mapper.audits(subject.scope(), query, query.pageSize(),
                Math.multiplyExact((long) query.page() - 1, query.pageSize())).stream().map(FoundationAuditService::event).toList();
        recordRead(read);
        return new AuditPage(total, query.page(), query.pageSize(), events);
    }

    /** 与完整查询共享授权与读取批次，只加载目录九列。 */
    @Transactional
    public AuditSummaryPage summaries(AuditQuery request) {
        var read = authorizeRead(request);
        AuditQuery query = read.query();
        String scope = read.subject().scope();
        long total = mapper.auditCount(scope, query);
        List<AuditSummary> items = mapper.auditSummaries(scope, query, query.pageSize(),
                Math.multiplyExact((long) query.page() - 1, query.pageSize())).stream()
                .map(row -> new AuditSummary(row.id().toString(), row.actorId(), row.action(), row.category(),
                        row.outcome(), row.resourceType(), row.message(), row.traceId(), row.createdAt())).toList();
        recordRead(read);
        return new AuditSummaryPage(total, query.page(), query.pageSize(), items);
    }

    private record AuthorizedRead(FoundationAccess.Subject subject, AuditQuery query, long authorizationVersion) { }

    private AuthorizedRead authorizeRead(AuditQuery request) {
        access.require(READ);
        AuditQuery query = validate(request);
        FoundationAccess.Subject subject = access.currentSubject();
        FoundationAccess.Snapshot authorization = access.currentSnapshot();
        if (!authorization.permissions().contains(READ))
            throw failure(HttpStatus.FORBIDDEN, "foundation.permission.denied", "没有操作权限");
        long authorizationVersion = authorization.authorizationVersion();
        validateResource(subject.scope(), query);
        return new AuthorizedRead(subject, query, authorizationVersion);
    }

    private void recordRead(AuthorizedRead read) {
        var subject = read.subject();
        AuditReadBatch batch = AuditReadBatch.create(subject, read.authorizationVersion(), read.query(), Instant.now());
        // 唯一约束串行首次受理；目录和完整查询共享同一过滤范围的读取批次。
        int claimed = mapper.insertReadBatch(batch);
        if (claimed == 1) audit.recordReadBatch(batch.auditId(), subject.tenantId(), subject.memberId(), batch.queryDigest());
        else if (claimed != 0) throw new IllegalStateException("audit_read_batch_claim_invalid");
    }

    /** 给后台管理任务返回尚未编码的固定CSV列，权限在执行时再次核对。 */
    @Transactional
    public List<List<String>> exportRows(AuditQuery request) {
        access.require(EXPORT);
        AuditQuery query = validate(request);
        FoundationAccess.Subject subject = access.currentSubject();
        validateResource(subject.scope(), query);
        long count = mapper.auditCount(subject.scope(), query);
        if (count > EXPORT_LIMIT) throw failure(HttpStatus.BAD_REQUEST, "operations.audit.export_limit", "请缩小查询范围，最多导出10000条");
        List<AuditExportRow> rows = mapper.auditExportRows(subject.scope(), query, EXPORT_LIMIT + 1);
        if (rows.size() > EXPORT_LIMIT) throw failure(HttpStatus.CONFLICT, "operations.audit.export_changed", "导出范围发生变化，请缩小范围后重试");
        List<List<String>> result = rows.stream().map(row -> List.of(row.id().toString(),
                text(row.tenantId()), text(row.actorId()), row.action(), row.category(), row.outcome(),
                text(row.resourceType()), text(row.resourceId()), text(row.message()), text(row.traceId()),
                row.createdAt().toString())).toList();
        audit.record(subject.tenantId(), subject.memberId(), "export.audit", "audit", null,
                "导出安全审计", Map.of("exported_rows", result.size()));
        return result;
    }

    /** 同步受控CSV导出；单元格转义阻止打开表格时执行公式。 */
    @Transactional
    public AuditExport export(AuditQuery request) {
        List<List<String>> rows = exportRows(request);
        StringBuilder csv = new StringBuilder("\ufeff");
        append(csv, columns());
        rows.forEach(row -> append(csv, row));
        return new AuditExport(csv.toString().getBytes(StandardCharsets.UTF_8), "security-audit.csv", rows.size());
    }

    /** 固定导出列，不包含安全payload或凭据。 */
    public static List<String> columns() {
        return List.of("id", "tenant_id", "actor_id", "action", "category", "outcome", "resource_type",
                "resource_id", "message", "trace_id", "created_at");
    }

    /** 入口和后台任务共用严格过滤校验，不把非法值夹紧成有效值。 */
    static AuditQuery validate(AuditQuery value) {
        if (value == null) value = new AuditQuery(null, null, null, null, null, null, null, null, null, null);
        int page = value.page() == null ? 1 : value.page();
        int size = value.pageSize() == null ? 50 : value.pageSize();
        if (page < 1 || page > 1000000 || size < 1 || size > 100)
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.pagination_invalid", "page须为1至1000000，page_size须为1至100");
        if (value.from() != null && value.to() != null && !value.from().isBefore(value.to()))
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.time_invalid", "截止时间必须晚于开始时间");
        if (value.category() != null && !Set.of("authentication", "authorization", "sensitive", "export", "operation").contains(value.category()))
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.category_invalid", "未知审计分类");
        if (value.outcome() != null && !Set.of("success", "failure", "denied").contains(value.outcome()))
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.outcome_invalid", "未知审计结果");
        check(value.action(), 64); check(value.actorId(), 128); check(value.resourceType(), 64); check(value.traceId(), 64);
        return new AuditQuery(value.action(), value.actorId(), value.category(), value.outcome(), value.resourceType(),
                value.traceId(), value.from(), value.to(), page, size);
    }

    private void validateResource(String scope, AuditQuery query) {
        if (query.resourceType() != null && !mapper.auditFilterValues(scope, "resource").contains(query.resourceType()))
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.resource_invalid", "资源类型不在当前审计范围，请重新选择");
    }

    /** 把内部查询行显式投影为公开不可变事实。 */
    private static AuditEvent event(OperationsRows.Audit row) {
        return new AuditEvent(row.getId().toString(), row.getTenantId() == null ? null : row.getTenantId().toString(),
                row.getActorId(), row.getAction(), row.getCategory(), row.getOutcome(), row.getResourceType(),
                row.getResourceId(), row.getMessage(), row.getTraceId(), row.getPayload(), row.getCreatedAt());
    }

    /** 非空条件必须有实际含义。 */
    private static void check(String value, int max) {
        if (value != null && (value.isBlank() || value.length() > max))
            throw failure(HttpStatus.BAD_REQUEST, "operations.audit.filter_invalid", "审计过滤条件无效");
    }

    /** CSV包含控制字符或公式前缀时增加受控文本前缀，随后执行RFC4180引号转义。 */
    private static void append(StringBuilder output, List<String> row) {
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) output.append(',');
            String value = row.get(i);
            String start = value.stripLeading();
            if (!start.isEmpty() && "=+-@".indexOf(start.charAt(0)) >= 0) value = "'" + value;
            output.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        output.append("\r\n");
    }

    /** 空值在表格中保持空列。 */
    private static String text(Object value) { return value == null ? "" : value.toString(); }

    /** 构造所属领域错误，调用方无须解析提示文本。 */
    static ApiException failure(HttpStatus status, String code, String message) { return new ApiException(status, code, message, false); }
}
