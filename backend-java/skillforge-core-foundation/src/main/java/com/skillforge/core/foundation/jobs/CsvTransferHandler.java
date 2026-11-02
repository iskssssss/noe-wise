package com.skillforge.core.foundation.jobs;

import java.util.List;
import java.util.Map;

/** 代码注册的领域 CSV 消费者；各实现沿用本领域公开应用服务，不允许 SQL/Shell 或任意类名执行。 */
public interface CsvTransferHandler {
    /** 稳定处理器代码，只接受代码注册的值。 */ String code();
    /** 管理页面显示名称。 */ String title();
    /** 精确 CSV 表头及列顺序。 */ List<String> columns();
    /** 表单展示所属领域真实选项，不向后台用户暴露实现 JSON。 */ default List<FoundationJobContracts.OptionField> optionFields(){return List.of();}
    /** 导入权限；不支持导入返回 NULL。 */ String importPermission();
    /** 导出权限；不支持导出返回 NULL。 */ String exportPermission();
    /** 校验有限的领域选项，拒绝未知键和越界值，不执行写入。 */ void validateOptions(Map<String,String> options);
    /** 只读预检；错误字段为表头代码，说明不可回显秘密。 */ List<FoundationJobContracts.RowError> validateRow(Map<String,String> options,int rowNumber,List<String> cells);
    /** 单行领域写入；工作者提供同库事务，写入与行回执一同提交，重启仅重做未提交行。 */ void importRow(Map<String,String> options,String jobId,int rowNumber,List<String> cells);
    /** 返回当前授权范围内的导出材料，最大一万行；敏感凭据不得导出。 */ List<List<String>> exportRows(Map<String,String> options);
}
