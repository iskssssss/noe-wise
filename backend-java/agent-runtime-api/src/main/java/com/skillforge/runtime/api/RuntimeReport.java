package com.skillforge.runtime.api;

import java.util.List;

/** 以最终文本、展示项和白名单导出值承载唯一公开运行结果投影。 */
public record RuntimeReport(
        String output,
        List<Presentation> presentations,
        List<ExportedValue> exportedValues) {

    /** 保留可为空字符串的输出文本，并防御性复制展示和导出集合。 */
    public RuntimeReport {
        output = ApiContracts.requireNonNull(output, "output");
        presentations = ApiContracts.immutableList(presentations, "presentations");
        exportedValues = ApiContracts.immutableList(exportedValues, "exportedValues");
    }
}
