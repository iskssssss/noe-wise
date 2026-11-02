package com.skillforge.core.foundation.jobs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** 管理任务公开命令与结果，身份归应用上下文，不接受客户端 tenant/member 字段。 */
public final class FoundationJobContracts {
    private FoundationJobContracts() { }
    /** @param handler 注册处理器 @param operation import/export @param fileId 导入文件，导出为空 @param options 处理器明确声明的选项 @param idempotencyKey 同键同请求返回既有任务 */
    public record Submit(String handler,String operation,@JsonProperty("file_id") String fileId,
            Map<String,String> options,@JsonProperty("idempotency_key") String idempotencyKey) { }
    /** @param handler 处理器 @param fileId 导入文件 @param options 受控领域选项 */
    public record Preflight(String handler,@JsonProperty("file_id") String fileId,Map<String,String> options) { }
    /** @param rowNumber 从第二行开始的 CSV 行号 @param field 表头字段或 row @param message 可公开纠正说明 */
    public record RowError(@JsonProperty("row_number") int rowNumber,String field,String message) { }
    /** @param rowCount 数据行数 @param valid 只有全部通过才为 true @param errors 全部逐行错误，不截断冒充成功 */
    public record PreflightResult(@JsonProperty("row_count") int rowCount,boolean valid,List<RowError> errors) {public PreflightResult{errors=List.copyOf(errors);}}
    /** @param code 稳定编码 @param title 标题 @param columns 精确 CSV 表头 @param canImport 当前可导入 @param canExport 当前可导出 */
    public record HandlerView(String code,String title,List<String> columns,@JsonProperty("option_fields") List<OptionField> optionFields,@JsonProperty("can_import") boolean canImport,@JsonProperty("can_export") boolean canExport) {public HandlerView{columns=List.copyOf(columns);optionFields=List.copyOf(optionFields);}}
    /** @param code 领域选项键 @param label 用户字段名称 @param required 是否必填 @param hint 可纠正的输入示例 */
    public record OptionField(String code,String label,boolean required,String hint) { }
    /** @param id 任务身份 @param handler 处理器 @param operation 操作 @param state 状态 @param totalRows 总行数 @param completedRows 已完成数 @param errorRows 错误数 @param errorMessage 公开失败原因 @param resultFileId 结果文件 @param createdAt 创建时间 @param expiresAt 终态保留时间 */
    public record JobView(String id,String handler,String operation,String state,@JsonProperty("total_rows") int totalRows,
            @JsonProperty("completed_rows") int completedRows,@JsonProperty("error_rows") int errorRows,
            @JsonProperty("error_message") String errorMessage,@JsonProperty("result_file_id") String resultFileId,
            @JsonProperty("created_at") OffsetDateTime createdAt,@JsonProperty("expires_at") OffsetDateTime expiresAt) { }
    /** @param items 当前页 @param total 总数 @param page 页号 @param pageSize 页大小 */
    public record Page(List<JobView> items,long total,int page,@JsonProperty("page_size") int pageSize) {public Page{items=List.copyOf(items);}}
}
