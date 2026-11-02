package com.skillforge.core.workflow.execution.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流变量表达式帮手函数（纯静态，无状态）。
 *
 * <p>从状态机引擎迁出的公共工具：占位符插值（${...}）、变量路径解析、JSON 提取、
 * 卡片构建等。引擎与各节点执行器共用；LLM 引用材料解析保留 JSON 类型并独立于文本插值。
 */
public final class WorkflowExpressions {

    /** 变量占位符匹配模式，如 ${config.clinic_name}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(.+?)}");

    /** JSON 解析器（llm 节点 extract 提取用）。 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private WorkflowExpressions() {
    }

    /** 显式节点参数递归插值；完整占位符保留 JSON 值类型，不隐式注入其他上下文。 */
    public static Object interpolateValue(Object value, NodeExecutionContext node) {
        if(value instanceof String text) {
            if(text.matches("\\$\\{[^{}]+}"))return resolve(text.substring(2,text.length()-1),
                    node.config(),node.state(),node.extracted(),node.extraVars());
            return interpolate(text,node.config(),node.state(),node.extracted(),node.extraVars());
        }
        if(value instanceof Map<?,?> map) {
            var result=new LinkedHashMap<String,Object>();
            map.forEach((key,entry)->result.put((String)key,interpolateValue(entry,node)));
            return result;
        }
        if(value instanceof List<?> list)return list.stream().map(entry->interpolateValue(entry,node)).toList();
        return value;
    }

    /**
     * 将执行结果写入指定变量集合。
     *
     * @param resultTo  目标集合（state / extracted）
     * @param key       变量名
     * @param value     变量值
     * @param state     状态变量
     * @param extracted 提取变量
     */
    public static void writeResult(
            String resultTo,
            String key,
            Object value,
            Map<String, Object> state,
            Map<String, Object> extracted) {
        if ("extracted".equals(resultTo)) {
            extracted.put(key, value);
        } else {
            state.put(key, value);
        }
    }

    /**
     * 插值模板中的占位符。
     *
     * @param template  模板文本
     * @param config    工作流配置
     * @param state     状态变量
     * @param extracted 提取变量
     * @return 插值后的文本
     */
    public static String interpolate(
            String template,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted) {
        return interpolate(template, config, state, extracted, null);
    }

    public static String interpolate(
            String template,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Object value = resolve(matcher.group(1), config, state, extracted, extraVars);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 按模板出现顺序解析引用变量，保留原始 JSON 值和 NULL；不对材料内容递归插值。
     * 供 LLM 节点将配置模板与运行材料分开交付，其他节点仍使用普通文本插值。
     */
    public static Map<String, Object> promptVariables(String template, NodeExecutionContext node) {
        var values = new LinkedHashMap<String, Object>();
        var matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (!values.containsKey(path)) {
                values.put(path, resolve(path, node.config(), node.state(), node.extracted(), node.extraVars()));
            }
        }
        return values;
    }

    /**
     * 列表插值：递归处理卡片 fields / options / actions 内的字符串、Map 与 List，非文本值原样保留。
     */
    public static List<Object> interpolateList(
            List<?> list,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        List<Object> out = new ArrayList<>();
        for (Object item : list) {
            out.add(interpolateValue(item, config, state, extracted, extraVars));
        }
        return out;
    }

    /**
     * 展示值插值：字符串使用安装配置与运行变量做文本替换；Map / List 递归，其余值原样。
     */
    public static Object interpolateValue(
            Object value,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        if (value instanceof String text) {
            return interpolate(text, config, state, extracted, extraVars);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> interpolated = new LinkedHashMap<>();
            map.forEach((key, inner) -> interpolated.put(String.valueOf(key),
                    interpolateValue(inner, config, state, extracted, extraVars)));
            return interpolated;
        }
        if (value instanceof List<?> list) {
            return interpolateList(list, config, state, extracted, extraVars);
        }
        return value;
    }

    /**
     * 构造人工确认卡片。
     *
     * <p>卡片内容读工作流节点 card 配置（通用渲染，平台不内嵌业务文案）；
     * 未配置时使用通用默认确认卡。
     *
     * @param node      节点
     * @param config    实例安装配置
     * @param state     状态变量
     * @param extracted 提取变量
     * @return 卡片 JSON
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildCard(
            WorkflowNode node,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        Map<String, Object> cardConfig = node.card() == null
                ? Map.of() : node.card();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("card_id", String.valueOf(cardConfig.getOrDefault("card_id", "hitl_confirm")));
        card.put("type", String.valueOf(cardConfig.getOrDefault("type", "confirm")));
        // 无默认值：title 未配置则不显示
        if (cardConfig.get("title") != null) {
            card.put("title", interpolate(String.valueOf(cardConfig.get("title")),
                    config, state, extracted, extraVars));
        }
        card.put("body", interpolate(String.valueOf(cardConfig.getOrDefault("body", "")),
                config, state, extracted, extraVars));
        // style 受控样式透传（渲染端白名单应用，禁 HTML 注入）
        Object style = cardConfig.get("style");
        if (style != null) {
            card.put("style", style);
        }
        // 全字段插值：fields / options 与 body / actions（含 link 跳转 url）同源体系
        Object fieldsRaw = cardConfig.get("fields");
        if (fieldsRaw instanceof List<?> fields) {
            card.put("fields", interpolateList(fields, config, state, extracted, extraVars));
        }
        Object optionsRaw = cardConfig.get("options");
        if (optionsRaw instanceof List<?> options) {
            card.put("options", interpolateList(options, config, state, extracted, extraVars));
        }
        Object actionsRaw = cardConfig.get("actions");
        if (actionsRaw instanceof List<?> actions) {
            card.put("actions", interpolateList(actions, config, state, extracted, extraVars));
        }
        // 节点未配置 actions 时不自动追加确认/取消按钮（交互由节点配置决定）
        Object metaRaw = cardConfig.get("meta");
        if (metaRaw instanceof Map<?, ?> meta) {
            Map<String, Object> interpolated = new LinkedHashMap<>();
            meta.forEach((key, value) -> interpolated.put(String.valueOf(key),
                    interpolateValue(value, config, state, extracted, extraVars)));
            card.put("meta", interpolated);
        }
        return card;
    }

    /**
     * 构造 menu 行动点卡片（type=select）：行动点由节点 actions 生成，
     * 点选后按 action id 路由（恢复逻辑见引擎 run 主循环）。
     *
     * <p>menu 不再接受 card 配置（外观覆盖已删除，展示语义统一由 card 节点承担），
     * 卡片固定默认外观，内容仅由 actions 决定。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildMenuCard(
            WorkflowNode node,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("card_id", "hitl_menu");
        card.put("type", "select");
        // 行动点统一为 actions 字段（与节点配置语义一致，不再转换为 options）
        List<Map<String, Object>> actions = new ArrayList<>();
        if (node.actions() != null) {
            for (Map<String, Object> action : node.actions()) {
                Map<String, Object> item = new LinkedHashMap<>();
                Object id = action.get("id");
                Object label = action.getOrDefault("label", id);
                item.put("id", id);
                item.put("label", interpolate(String.valueOf(label), config,
                        state, extracted, extraVars));
                actions.add(item);
            }
        }
        card.put("actions", actions);
        // 不自动追加取消按钮：行动点即全部交互（点选后按 action 路由）
        return card;
    }

    /**
     * 构造展示卡片（card 节点，type=info，不挂起）：title / body / fields / actions
     * （含 link 跳转 url）递归插值，style 保持原样；未配置时用通用默认展示卡。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildInfoCard(
            WorkflowNode node,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        Map<String, Object> cardConfig = node.card() == null
                ? Map.of() : node.card();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("card_id", String.valueOf(cardConfig.getOrDefault("card_id", "hitl_info")));
        card.put("type", "info");
        card.put("title", interpolate(String.valueOf(cardConfig.getOrDefault("title", "")),
                config, state, extracted, extraVars));
        card.put("body", interpolate(String.valueOf(cardConfig.getOrDefault("body", "")),
                config, state, extracted, extraVars));
        Object style = cardConfig.get("style");
        if (style != null) {
            card.put("style", style);
        }
        Object fieldsRaw = cardConfig.get("fields");
        if (fieldsRaw instanceof List<?> fields) {
            card.put("fields", interpolateList(fields, config, state, extracted, extraVars));
        }
        Object actionsRaw = cardConfig.get("actions");
        if (actionsRaw instanceof List<?> actions) {
            card.put("actions", interpolateList(actions, config, state, extracted, extraVars));
        }
        return card;
    }

    /**
     * 构造带卡的助手消息（挂起节点 human_input / menu 的消息随卡下发）。
     *
     * @param prompt      提示文案
     * @param config      实例配置
     * @param state       状态变量
     * @param extracted   抽取变量
     * @param extraVars   附加变量
     * @param card        卡片数据
     * @return 消息 Map（含 card）
     */
    public static Map<String, Object> cardMessage(
            String prompt,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars,
            Map<String, Object> card) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", interpolate(prompt, config, state, extracted, extraVars));
        message.put("card", card);
        return message;
    }

    /**
     * 构建节点自定义渲染数据（render）：全值递归插值，平台原样透传不参与渲染。
     *
     * @param node      节点
     * @param config    实例安装配置
     * @param state     状态变量
     * @param extracted 提取变量
     * @param extraVars 附加变量（user. / runtime.）
     * @return 插值后的渲染数据；节点未配置 render 时返回 null
     */
    public static Map<String, Object> buildRender(
            WorkflowNode node,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        if (node.render() == null || node.render().isEmpty()) {
            return null;
        }
        Map<String, Object> render = new LinkedHashMap<>();
        node.render().forEach((key, value) -> render.put(key,
                interpolateValue(value, config, state, extracted, extraVars)));
        return render;
    }

    /**
     * 解析节点卡片同 id merge 目标消息 ID（merge_message_id 变量插值）。
     *
     * @param node      节点
     * @param config    工作流配置
     * @param state     状态变量
     * @param extracted 提取变量
     * @param extraVars 附加变量
     * @return 解析后的消息 ID；未配置或插值结果为空返回 null
     */
    public static String mergeMessageId(
            WorkflowNode node,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        if (node.mergeMessageId() == null || node.mergeMessageId().isBlank()) {
            return null;
        }
        String resolved = interpolate(node.mergeMessageId(), config, state, extracted, extraVars);
        return resolved == null || resolved.isBlank() ? null : resolved;
    }

    /**
     * 将对象安全转换为 Map。
     *
     * @param value 原始对象
     * @return Map；null 时返回空 Map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /**
     * 安全转换对象为字符串列表。
     *
     * @param value 原始对象
     * @return 字符串列表
     */
    @SuppressWarnings("unchecked")
    public static List<String> castStringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * 尽力解析 JSON 对象文本；非 JSON 或解析失败返回 null。
     *
     * @param raw 文本
     * @return 解析后的 Map；失败返回 null
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseJsonObject(String raw) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            Object parsed = JSON_MAPPER.readValue(raw.substring(start, end + 1), Map.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析变量路径并取值。
     *
     * <p>支持 ${...} 包裹（assign 节点 source 与模板插值均可能携带占位符）
     * 与点路径嵌套（如 state.ctx_order_info.name）；无前缀的裸键查不到时
     * 回退字面量解析（数字/布尔/null/引号字符串），使 assign 节点可直接写字面量。
     *
     * @param path      变量路径，如 config.clinic_name
     * @param config    工作流配置
     * @param state     状态变量
     * @param extracted 提取变量
     * @return 变量值
     */
    public static Object resolve(
            String path,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted) {
        return resolve(path, config, state, extracted, null);
    }

    public static Object resolve(
            String path,
            Map<String, Object> config,
            Map<String, Object> state,
            Map<String, Object> extracted,
            Map<String, Object> extraVars) {
        String key = path.trim();
        if (key.startsWith("${") && key.endsWith("}")) {
            key = key.substring(2, key.length() - 1);
        }
        boolean prefixed = false;
        Map<String, Object> root = state;
        String rest = key;
        if (key.startsWith("config.")) {
            root = config;
            rest = key.substring(7);
            prefixed = true;
        } else if (key.startsWith("extracted.")) {
            root = extracted;
            rest = key.substring(10);
            prefixed = true;
        } else if (key.startsWith("state.")) {
            rest = key.substring(6);
            prefixed = true;
        } else if (key.startsWith("user.") && extraVars != null) {
            // 用户画像只读投影：白名单外引用解析为 null（不注入未声明字段）
            root = asMap(extraVars.get("user"));
            rest = key.substring(5);
            prefixed = true;
        } else if (key.startsWith("runtime.") && extraVars != null) {
            // 引擎注入运行标识：session_id / execution_id / run_id / workflow_instance_id
            root = asMap(extraVars.get("runtime"));
            rest = key.substring(8);
            prefixed = true;
        }
        // 支持点路径嵌套取值：如 state.ctx_order_info.name
        Object value = root;
        for (String part : rest.split("\\.")) {
            if (value instanceof Map<?, ?> map) {
                value = map.get(part);
                if (value == null) {
                    break;
                }
            } else {
                value = null;
                break;
            }
        }
        // 显式指名（带前缀）的引用不存在时保持 null 语义；裸键才回退字面量
        if (value == null && !prefixed) {
            return parseLiteral(key);
        }
        return value;
    }

    /**
     * 解析条件字面量：字符串（引号包裹，支持 \\ 转义）、数字、布尔、
     * null、数组（`[a, b, c]`，元素逐个按字面量解析）。
     *
     * @param raw 原始文本
     * @return 解析后的值
     */
    public static Object parseLiteral(String raw) {
        String text = raw.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            String inner = text.substring(1, text.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (text.startsWith("'") && text.endsWith("'") && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text)) {
            return Boolean.FALSE;
        }
        if ("null".equals(text)) {
            return null;
        }
        if (text.startsWith("[") && text.endsWith("]")) {
            String[] items = text.substring(1, text.length() - 1).split(",");
            List<Object> list = new ArrayList<>();
            for (String item : items) {
                if (!item.isBlank()) {
                    list.add(parseLiteral(item.trim()));
                }
            }
            return list;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            // 非整数，尝试浮点
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            // 非数字，按字符串返回
        }
        return text;
    }
}
