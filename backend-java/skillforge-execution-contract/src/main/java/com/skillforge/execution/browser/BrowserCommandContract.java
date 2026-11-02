package com.skillforge.execution.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;

/** 固定上游的封闭参数契约；宿主身份、任务、文件路径从不由模型提供。 */
public final class BrowserCommandContract {
    public static final String COMMIT = "3f10983c09fcd443c7420830ab984c9d409954bf";
    private static final JsonNode ROOT;
    static {
        try (var in = BrowserCommandContract.class.getResourceAsStream("/browser-cli-contract.json")) {
            ROOT = new ObjectMapper().readTree(Objects.requireNonNull(in));
            if (!COMMIT.equals(ROOT.path("upstream_commit").asText())) throw new IllegalStateException("browser_contract_version");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private BrowserCommandContract() {}
    public static List<String> operations() {
        var names = new ArrayList<String>(); ROOT.path("operations").fieldNames().forEachRemaining(names::add); return List.copyOf(names);
    }
    /** 共同对象约束只声明一次；联合分支保留判别值与该操作的全部封闭参数约束。 */
    public static Map<String,Object> schema() {
        var alternatives = new ArrayList<Map<String,Object>>();
        for (String name : operations()) {
            var properties = new LinkedHashMap<String,Object>();
            var required = new ArrayList<String>();
            for (JsonNode f : operation(name).path("fields")) {
                var property = new LinkedHashMap<String,Object>(); property.put("type", f.path("type").asText());
                if (!f.path("description").asText().isBlank()) property.put("description",f.path("description").asText());
                if ("array".equals(f.path("type").asText())) property.put("items",Map.of("type","string"));
                if (!f.path("enum").isEmpty()) property.put("enum",new ObjectMapper().convertValue(f.path("enum"),List.class));
                properties.put(f.path("name").asText(),property);
                if (f.path("required").asBoolean()) required.add(f.path("name").asText());
            }
            alternatives.add(Map.of("properties",Map.of("operation",Map.of("const",name),
                "parameters",Map.of("properties",properties,"required",required,"additionalProperties",false))));
        }
        return Map.of("type","object","properties",Map.of("operation",Map.of("type","string"),
                "parameters",Map.of("type","object")),"required",List.of("operation","parameters"),
                "additionalProperties",false,"oneOf",alternatives);
    }

    private static JsonNode operation(String name) {
        JsonNode op = ROOT.path("operations").get(name);
        if (op == null) throw new IllegalArgumentException("browser_operation_not_supported"); return op;
    }
    public static List<String> arguments(String name, JsonNode values, String session, String browser, Path output) {
        JsonNode op = operation(name); var allowed = new HashSet<String>();
        for (JsonNode f : op.path("fields")) allowed.add(f.path("name").asText());
        if(!values.isObject())throw new IllegalArgumentException("browser_parameters_rejected");
        var supplied=new HashSet<String>();values.fieldNames().forEachRemaining(supplied::add);
        if (!allowed.containsAll(supplied)) throw new IllegalArgumentException("browser_parameters_rejected");
        var args = new ArrayList<String>(); args.add("--json"); op.path("command").forEach(c -> args.add(c.asText()));
        if (op.path("session").asBoolean()) { args.add("--session"); args.add(Objects.requireNonNull(session)); }
        for (JsonNode f : op.path("fields")) {
            String key=f.path("name").asText(); JsonNode value=values.get(key);
            if (value==null||value.isNull()) { if(f.path("required").asBoolean()) throw new IllegalArgumentException("browser_parameter_required:"+key); continue; }
            String type=f.path("type").asText();
            boolean valid=switch(type) {case "boolean"->value.isBoolean();case "integer"->value.isIntegralNumber();case "number"->value.isNumber();
                case "array"->value.isArray()&&java.util.stream.StreamSupport.stream(value.spliterator(),false).allMatch(JsonNode::isTextual);default->value.isTextual();};
            if(!valid) throw new IllegalArgumentException("browser_parameter_type:"+key);
            if(!f.path("enum").isEmpty() && java.util.stream.StreamSupport.stream(f.path("enum").spliterator(),false).noneMatch(e->e.asText().equals(value.asText())))
                throw new IllegalArgumentException("browser_parameter_enum:"+key);
            String flag=f.path("flag").asText();
            if(value.isBoolean()&&!f.path("valueBoolean").asBoolean()){if(value.asBoolean())args.add(flag);continue;}
            if (name.equals("select") && key.equals("values")) {
                // 官方多选使用重复 --value；每个原值保持独立 argv，顺序、重复和逗号字符都不改写。
                value.forEach(item -> args.add(flag + "=" + item.asText()));
                continue;
            }
            String rendered=value.isArray()?String.join(",",java.util.stream.StreamSupport.stream(value.spliterator(),false).map(JsonNode::asText).toList()):value.asText();
            if(!flag.isEmpty()) args.add(flag+"="+rendered);
            else { if(rendered.startsWith("-")) throw new IllegalArgumentException("browser_positional_flag_rejected"); args.add(rendered); }
        }
        if(name.equals("screenshot")) { args.add("--out");args.add(output.resolve("screenshot.png").toString()); }
        if(name.equals("debug") && "export".equals(values.path("action").asText())) { args.add("--output");args.add(output.resolve("debug.json").toString()); }
        if(name.startsWith("record-")) { args.add("--output");args.add(output.toString()); if(name.equals("record-start")){args.add("--browser");args.add(browser);} }
        return List.copyOf(args);
    }
}
