package com.skillforge.core.foundation.operations;

import com.skillforge.core.foundation.jobs.CsvTransferHandler;
import com.skillforge.core.foundation.jobs.FoundationJobContracts;
import com.skillforge.core.foundation.operations.OperationsContracts.AuditQuery;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 管理任务的安全审计导出消费者，后台主体和撤权检查继续由公开审计服务执行。 */
@Component
public class AuditCsvTransferHandler implements CsvTransferHandler {
    private final FoundationAuditService audits;
    /** 注入受控审计出口，不依赖Mapper或自行选择租户。 */
    public AuditCsvTransferHandler(FoundationAuditService audits) { this.audits = audits; }
    /** {@inheritDoc} */ @Override public String code() { return "security_audit"; }
    /** {@inheritDoc} */ @Override public String title() { return "安全审计导出"; }
    /** {@inheritDoc} */ @Override public List<String> columns() { return FoundationAuditService.columns(); }
    /** {@inheritDoc} */ @Override public String importPermission() { return null; }
    /** {@inheritDoc} */ @Override public String exportPermission() { return FoundationAuditService.EXPORT; }
    /** 给管理任务表单提供领域过滤字段，不暴露原始JSON配置框。 */
    @Override public List<FoundationJobContracts.OptionField> optionFields() {
        return List.of(new FoundationJobContracts.OptionField("action", "动作编码", false, "例如 auth.login"),
                new FoundationJobContracts.OptionField("actor_id", "操作者ID", false, "完整成员ID"),
                new FoundationJobContracts.OptionField("category", "安全分类", false, "authentication/authorization/sensitive/export/operation"),
                new FoundationJobContracts.OptionField("outcome", "操作结果", false, "success/failure/denied"),
                new FoundationJobContracts.OptionField("resource_type", "资源类型", false, "例如 tenant_member"),
                new FoundationJobContracts.OptionField("trace_id", "追踪ID", false, "完整请求链路标识"),
                new FoundationJobContracts.OptionField("from", "开始时间", false, "2026-10-11T00:00:00+08:00"),
                new FoundationJobContracts.OptionField("to", "截止时间", false, "2026-10-12T00:00:00+08:00"));
    }
    /** {@inheritDoc} */ @Override public void validateOptions(Map<String, String> options) { query(options); }
    /** 审计事实只追加，禁止通用任务导入伪造事实。 */
    @Override public List<FoundationJobContracts.RowError> validateRow(Map<String,String> options, int rowNumber, List<String> cells) { throw new UnsupportedOperationException("audit_import_forbidden"); }
    /** 审计事实不接受管理导入。 */
    @Override public void importRow(Map<String,String> options, String jobId, int rowNumber, List<String> cells) { throw new UnsupportedOperationException("audit_import_forbidden"); }
    /** {@inheritDoc} */ @Override public List<List<String>> exportRows(Map<String,String> options) { return audits.exportRows(query(options)); }
    /** 仅接纳固定无租户参数，避免持久后台任务扩大数据范围。 */
    private static AuditQuery query(Map<String,String> options) {
        Set<String> allowed = Set.of("action", "actor_id", "category", "outcome", "resource_type", "trace_id", "from", "to");
        if (options == null || options.keySet().stream().anyMatch(key -> !allowed.contains(key)))
            throw FoundationAuditService.failure(org.springframework.http.HttpStatus.BAD_REQUEST, "operations.audit.options_invalid", "审计导出选项无效");
        try {
            return FoundationAuditService.validate(new AuditQuery(options.get("action"), options.get("actor_id"), options.get("category"),
                    options.get("outcome"), options.get("resource_type"), options.get("trace_id"), time(options.get("from")), time(options.get("to")), 1, 100));
        } catch (java.time.DateTimeException failure) {
            throw FoundationAuditService.failure(org.springframework.http.HttpStatus.BAD_REQUEST, "operations.audit.time_invalid", "审计时间格式无效");
        }
    }
    /** ISO8601时间必须带时区，不猜测本地时区。 */
    private static Instant time(String value) { return value == null ? null : Instant.parse(value); }
}
