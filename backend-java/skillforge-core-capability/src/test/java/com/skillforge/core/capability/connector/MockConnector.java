package com.skillforge.core.capability.connector;

import java.util.List;
import java.util.Map;

/** 提供确定性示例响应的测试连接器。 */
public class MockConnector {

    /**
     * 执行 Mock 工具调用。
     *
     * @param tool 工具名，如 sample_query / sample_mutation
     * @param args 工具参数
     * @return 工具执行结果
     */
    public Map<String, Object> execute(String tool, Map<String, Object> args) {
        return switch (tool) {
            case "sample_query" -> Map.of(
                    "has_items", true,
                    "items", List.of(
                            Map.of("date", "2026-08-10", "time", "09:00-09:30", "owner", "示例负责人甲"),
                            Map.of("date", "2026-08-10", "time", "10:00-10:30", "owner", "示例负责人乙")),
                    "category", args.get("category"));
            case "sample_mutation" -> Map.of(
                    "record_id", "SAMPLE-20260810-001",
                    "success", true,
                    "message", "操作成功");
            case "sample_check" -> {
                // 示例用户：default-vip 为通过校验，其余按未通过处理
                if ("default-vip".equals(String.valueOf(args.get("user_id")))) {
                    yield Map.of(
                            "is_valid", true,
                            "profile", Map.of(
                                    "record_id", "SAMPLE-001",
                                    "name", "示例用户",
                                    "phone", "13900000000"));
                }
                yield Map.of("is_valid", false, "profile", null);
            }
            case "sample_upgrade" -> {
                String phone = String.valueOf(args.get("phone"));
                if ("13800000000".equals(phone)) {
                    // 示例失败路径：手机号已绑定其他账号
                    yield Map.of(
                            "success", false,
                            "profile", null,
                            "error_msg", "手机号已绑定其他账号，请更换手机号或联系人工客服");
                }
                // 示例 ID：name 为空时用缺省名，hashCode 取模用 floorMod 避免负号
                String name = args.get("name") == null ? "未命名" : String.valueOf(args.get("name"));
                yield Map.of(
                        "success", true,
                        "profile", Map.of(
                                "record_id", "SAMPLE-NEW-" + Math.floorMod(name.hashCode(), 1000),
                                "name", name,
                                "phone", phone),
                        "error_msg", "");
            }
            default -> Map.of("success", false, "error", "未知工具: " + tool);
        };
    }
}
