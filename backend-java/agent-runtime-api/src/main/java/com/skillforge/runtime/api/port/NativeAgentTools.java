package com.skillforge.runtime.api.port;

import java.util.Map;
import java.util.Optional;

/** 原生模型控制工具的唯一公开名称及内部封闭动作映射；匹配不授予执行权限。 */
public final class NativeAgentTools {
    private NativeAgentTools() { }
    /** 字段映射只描述改名；委派结构由协议适配器按 operation 形成规范命令。 */
    public record Control(String wireName, String actionType, Map<String, String> argumentNames,
                          Optional<String> delegationOperation) {
        public Control { argumentNames = Map.copyOf(argumentNames); }
    }
    private static Control action(String name, String type, Map<String, String> fields) {
        return new Control(name, type, fields, Optional.empty());
    }
    private static Control delegation(String name, String operation) {
        return new Control(name, "delegate", Map.of(), Optional.of(operation));
    }
    private static Control plan(String name,String operation) { return new Control(name,"plan",Map.of(),Optional.of(operation)); }
    private static final Map<String, Control> CONTROLS = Map.ofEntries(
            Map.entry("ask_user", action("ask_user", "ask_user", Map.of())),
            Map.entry("run_workflow", action("run_workflow", "dispatch", Map.of())),
            Map.entry("knowledge_query", action("knowledge_query", "knowledge_query", Map.of())),
            Map.entry("knowledge_list_documents", action("knowledge_list_documents", "knowledge_list_documents", Map.of())),
            Map.entry("knowledge_read_document", action("knowledge_read_document", "knowledge_read_document", Map.of())),
            Map.entry("use_skill", action("use_skill", "activate_skill", Map.of())),
            Map.entry("read_skill_resource", action("read_skill_resource", "read_skill_resource", Map.of())),
            Map.entry("delegate", delegation("delegate", "start")),
            Map.entry("wait_delegates", delegation("wait_delegates", "wait")),
            Map.entry("message_delegate", delegation("message_delegate", "send")),
            Map.entry("cancel_delegate", delegation("cancel_delegate", "cancel")),
            Map.entry("delegate_status", delegation("delegate_status", "get")),
            Map.entry("delegation_targets", delegation("delegation_targets", "targets")),
            Map.entry("plan_submit", plan("plan_submit", "submit")),
            Map.entry("plan_get", plan("plan_get", "get")),
            Map.entry("plan_start_step", plan("plan_start_step", "start_step")),
            Map.entry("plan_finish_step", plan("plan_finish_step", "finish_step")),
            Map.entry("plan_wait", plan("plan_wait", "wait")));
    public static Optional<Control> find(String wireName) { return Optional.ofNullable(CONTROLS.get(wireName)); }
    /** 仅用于冻结声明和历史重建；同一内部 GET/LIST 共享状态查询工具。 */
    public static String wireName(String actionType, Optional<String> operation) {
        if ("delegate".equals(actionType) || "plan".equals(actionType)) {
            String value = operation.orElseThrow();
            if ("list".equals(value)) return "delegate_status";
            return CONTROLS.values().stream().filter(c -> c.actionType().equals(actionType) && c.delegationOperation().equals(Optional.of(value)))
                    .map(Control::wireName).findFirst().orElseThrow();
        }
        return CONTROLS.values().stream().filter(c -> c.actionType().equals(actionType))
                .map(Control::wireName).findFirst().orElseThrow();
    }
}
