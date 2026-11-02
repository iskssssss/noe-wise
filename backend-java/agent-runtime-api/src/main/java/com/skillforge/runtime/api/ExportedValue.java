package com.skillforge.runtime.api;

/** 表示以稳定导出路径命名的 schema 化公开值。 */
public record ExportedValue(String exportPath, TypedPayload payload) {

    /** 拒绝空白导出路径和缺失 payload。 */
    public ExportedValue {
        exportPath = ApiContracts.requireText(exportPath, "exportPath");
        payload = ApiContracts.requireNonNull(payload, "payload");
    }
}
