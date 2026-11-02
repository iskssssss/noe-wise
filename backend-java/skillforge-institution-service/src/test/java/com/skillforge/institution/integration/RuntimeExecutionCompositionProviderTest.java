package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 核验组合脚本的真实HTTP请求、精确native目录和问答时间尾部，不代替实际委派或Workflow产品验收。 */
class RuntimeExecutionCompositionProviderTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 等待本用例拥有的 HTTP 客户端结束，不遗留迟到请求。 */
    @AfterEach
    void closeHttpClient() { http.close(); }

    /** 子 Agent AskUser 后 MODEL 动作为 2，原问答后必须是预算观察和正式校验的时间样本。 */
    @Test
    void delegatedHumanReplyKeepsExactPairBeforeValidatedTimeSample() throws Exception {
        try (var provider = new RuntimeExecutionCompositionProvider(json,
                RuntimeExecutionCompositionProvider.Scenario.DELEGATION, "human-time-good")) {
            provider.start();
            var messages = firstMessages(provider);
            var first = post(provider, withFooter(messages, 1));
            assertEquals(200, first.statusCode(), first.body());
            var question=json.readTree(first.body()).path("choices").get(0).path("message");
            messages.add(json.convertValue(question,new TypeReference<Map<String,Object>>(){}));
            messages.add(Map.of("role","tool","tool_call_id",question.path("tool_calls").get(0).path("id").asText(),"content",RuntimeExecutionTimeFixture.HUMAN_RECEIPT));
            messages.add(Map.of("role", "user", "content", provider.reply()));
            var second = post(provider, withFooter(messages, 2));
            assertEquals(200, second.statusCode(), second.body());
            assertTrue(second.body().contains(provider.childAnswer()), second.body());
            assertEquals(5, messages.size(), "时间样本不进入原业务消息，原native调用与TOOL回执保持完整");
            assertEquals(2, provider.childCalls());
            assertEquals(0, provider.parentCalls());
            assertEquals(1, provider.snapshot().get("verifiedReplies"));
            assertEquals(List.of(), provider.snapshot().get("failures"));
        }
    }

    /** 合法首轮之后只改变目标条件，不能忽略任意 SYSTEM/USER 或以相同时间文本的错误角色放行。 */
    @Test
    void delegatedHumanReplyRejectsMissingOrChangedFooterAndBrokenQuestionPair() throws Exception {
        for (String variant : List.of("missing", "source", "ordinal", "time-role", "system-gap", "user-gap", "question-role", "reply-role", "tool-metadata", "wrong-call-id", "missing-answered")) {
            try (var provider = new RuntimeExecutionCompositionProvider(json,
                    RuntimeExecutionCompositionProvider.Scenario.DELEGATION, "human-time-" + variant)) {
                provider.start();
                var messages = firstMessages(provider);
                var first = post(provider, withFooter(messages, 1));
                assertEquals(200, first.statusCode(), variant + ":" + first.body());
                var question=(ObjectNode)json.readTree(first.body()).path("choices").get(0).path("message");
                if(variant.equals("question-role"))question.put("role","user");
                messages.add(json.convertValue(question,new TypeReference<Map<String,Object>>(){}));
                if(!variant.equals("missing-answered"))messages.add(Map.of("role","tool","tool_call_id",
                        variant.equals("wrong-call-id")?"different-question":question.path("tool_calls").get(0).path("id").asText(),"content",RuntimeExecutionTimeFixture.HUMAN_RECEIPT));
                var response = new LinkedHashMap<String, Object>();
                response.put("role", variant.equals("reply-role") ? "assistant" : "user"); response.put("content", provider.reply());
                if (variant.equals("tool-metadata")) response.put("tool_call_id", "not-a-human-response");
                messages.add(response);
                if (variant.equals("system-gap")) messages.add(Map.of("role", "system", "content", "unrelated system"));
                if (variant.equals("user-gap")) messages.add(Map.of("role", "user", "content", "unrelated user"));
                if (!variant.equals("missing")) {
                    var footer = new LinkedHashMap<>(RuntimeExecutionTimeFixture.footer(2));
                    if (variant.equals("time-role")) footer.put("role","system");
                    if (List.of("source", "ordinal").contains(variant)) {
                        String original = footer.get("content").toString();
                        String changed = variant.equals("source")
                                ? original.replace("来源为宿主时钟", "来源为供应商时钟")
                                : original + "；模型准备序号：3";
                        assertTrue(!original.equals(changed), variant + ":实际时间文本必须发生篡改");
                        footer.put("content", changed);
                    }
                    messages.add(RuntimeExecutionTimeFixture.budget());
                    messages.add(footer);
                }
                messages.addFirst(RuntimeExecutionTimeFixture.outputPrefix(2));
                var rejected = post(provider, messages);
                assertEquals(500, rejected.statusCode(), variant + ":" + rejected.body());
                assertEquals(0, provider.snapshot().get("verifiedReplies"), variant);
                String expected = List.of("missing", "source", "ordinal", "time-role").contains(variant)
                        ? "execution_time_context_missing_or_invalid" : "actual_child_human_reply_missing";
                assertEquals(List.of("IOException:" + expected), provider.snapshot().get("failures"), variant);
            }
        }
    }

    /** 消费匹配函数、匹配参数的完整唯一枚举，消息和其他函数的相似文本不能提供句柄。 */
    @Test void parentSelectsExactNativeEnumHandleAndIgnoresMessageDecoys() throws Exception {
        for (var scenario : RuntimeExecutionCompositionProvider.Scenario.values()) {
            String name = scenario == RuntimeExecutionCompositionProvider.Scenario.WORKFLOW ? "run_workflow" : "delegate";
            String parameter = scenario == RuntimeExecutionCompositionProvider.Scenario.WORKFLOW ? "handle" : "target_handle";
            String full = scenario == RuntimeExecutionCompositionProvider.Scenario.WORKFLOW
                    ? "workflow:862143854526469:runtime-e2e-exact" : "member:862143854526469:runtime-e2e-exact";
            try (var provider = new RuntimeExecutionCompositionProvider(json,scenario,"native-exact-" + name)) {
                provider.start();
                var tools = List.of(nativeTool("unrelated",parameter,Map.of("type","string","enum",List.of("decoy:wrong"))),
                        nativeTool(name,"unrelated_parameter",Map.of("type","string","enum",List.of("decoy:field")),
                                parameter,Map.of("type","string","enum",List.of(full))));
                var response = post(provider,List.of(Map.of("role","user","content","workflow:123 member:456 与截短的 " + full.split(":")[0] + ":862143854526469")),tools);
                assertEquals(200,response.statusCode(),response.body());
                var call = json.readTree(response.body()).path("choices").get(0).path("message").path("tool_calls").get(0);
                assertEquals(name,call.path("function").path("name").asText());
                var arguments = json.readTree(call.path("function").path("arguments").asText());
                assertEquals(full,arguments.path(parameter).asText());
                assertEquals(List.of(),provider.snapshot().get("failures"));
            }
        }
    }

    /** 缺失、空、多值、非字符串、错误参数或重复函数都拒绝，不从业务消息补出目标。 */
    @Test void parentRejectsMissingOrAmbiguousNativeEnumWithoutFabricatingTarget() throws Exception {
        for (var scenario : RuntimeExecutionCompositionProvider.Scenario.values()) {
            String name = scenario == RuntimeExecutionCompositionProvider.Scenario.WORKFLOW ? "run_workflow" : "delegate";
            String parameter = scenario == RuntimeExecutionCompositionProvider.Scenario.WORKFLOW ? "handle" : "target_handle";
            for (String variant : List.of("missing","empty","multiple","duplicate-values","number","blank","wrong-parameter","duplicate-tool")) {
                try (var provider = new RuntimeExecutionCompositionProvider(json,scenario,"native-reject-" + name + "-" + variant)) {
                    provider.start();
                    var field = new LinkedHashMap<String,Object>(); field.put("type","string");
                    if (!variant.equals("missing")) field.put("enum",switch (variant) {
                        case "empty" -> List.of();
                        case "multiple" -> List.of("actual:one","actual:two");
                        case "duplicate-values" -> List.of("actual:one","actual:one");
                        case "number" -> List.of(862143854526469L);
                        case "blank" -> List.of(" ");
                        default -> List.of("actual:one");
                    });
                    var tool = nativeTool(name,variant.equals("wrong-parameter") ? "other_parameter" : parameter,field);
                    var tools = variant.equals("duplicate-tool") ? List.of(tool,tool) : List.of(tool);
                    var response = post(provider,List.of(Map.of("role","user","content","workflow:123 member:456")),tools);
                    assertEquals(500,response.statusCode(),name + ":" + variant + ":" + response.body());
                    assertTrue(!json.readTree(response.body()).has("choices"),variant);
                    String reason = variant.equals("duplicate-tool") ? "frozen_composition_target_ambiguous" : "frozen_composition_target_missing_or_ambiguous";
                    assertEquals(List.of("IOException:" + reason),provider.snapshot().get("failures"),variant);
                }
            }
        }
    }

    /** Runtime规范ID可以不同于Provider首次响应ID，但唯一函数、原参数、结果ID和实际结果必须准确对应。 */
    @Test void parentConsumesExactlyPairedCanonicalWorkflowAndDelegationResults() throws Exception {
        for(var scenario:RuntimeExecutionCompositionProvider.Scenario.values()){
            try(var provider=new RuntimeExecutionCompositionProvider(json,scenario,"canonical-result-"+scenario)){
                provider.start();var tools=parentTools(scenario);var issued=firstParentCall(provider,tools);
                assertTrue(!"execution-call:1".equals(issued.path("id").asText()),"首轮Provider ID须保留为独立输入，不能伪造规范ID");
                var function=Map.<String,Object>of("name",issued.path("function").path("name").asText(),
                        "arguments",issued.path("function").path("arguments").asText());
                var messages=List.<Map<String,Object>>of(Map.of("role","user","content",provider.prompt()),
                        Map.of("role","assistant","tool_calls",List.of(Map.of("id","execution-call:1","type","function","function",function))),
                        Map.of("role","tool","tool_call_id","execution-call:1","content",json.writeValueAsString(compositionResult(provider,scenario,true,true))));
                var response=post(provider,messages,tools);
                assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains(provider.finalAnswer()));
                assertEquals(2,provider.parentCalls());assertEquals(1,provider.snapshot().get("verifiedResults"));
                assertEquals(List.of(),provider.snapshot().get("failures"));
            }
        }
    }

    /** 正确正文不能掩盖缺失、重复、错ID、错误函数或参数；完成状态和结果正文也必须来自同一准确TOOL结果。 */
    @Test void parentRejectsBrokenNativePairArgumentsAndCompletedResultEvenWithContentDecoys() throws Exception {
        for(var scenario:RuntimeExecutionCompositionProvider.Scenario.values())for(String variant:List.of(
                "missing-call","duplicate-call","duplicate-id","missing-result","duplicate-result","wrong-result-id",
                "blank-id","number-id","wrong-function","wrong-role","result-before-call","arguments-object",
                "wrong-target","joint-target","wrong-instruction","wrong-background","extra-argument","wrong-state","wrong-value","content-decoy")){
            try(var provider=new RuntimeExecutionCompositionProvider(json,scenario,"native-pair-"+scenario+"-"+variant)){
                provider.start();var tools=parentTools(scenario);var issued=firstParentCall(provider,tools);
                var arguments=(ObjectNode)json.readTree(issued.path("function").path("arguments").asText());
                if(List.of("wrong-target","joint-target").contains(variant))arguments.put(scenario==RuntimeExecutionCompositionProvider.Scenario.WORKFLOW?"handle":"target_handle","not-the-frozen-target");
                if(variant.equals("wrong-instruction"))arguments.put("instruction","不是首轮原任务");
                if(variant.equals("wrong-background"))arguments.put("background",true);
                if(variant.equals("extra-argument"))arguments.put("invented","extra");
                var function=new LinkedHashMap<String,Object>();function.put("name",variant.equals("wrong-function")?"unrelated":issued.path("function").path("name").asText());
                function.put("arguments",variant.equals("arguments-object")?arguments:json.writeValueAsString(arguments));
                var call=new LinkedHashMap<String,Object>();call.put("id",variant.equals("blank-id")?" ":variant.equals("number-id")?42:"execution-call:1");
                call.put("type","function");call.put("function",function);
                var assistant=Map.<String,Object>of("role","assistant","tool_calls",List.of(call));
                var result=Map.<String,Object>of("role",variant.equals("wrong-role")?"user":"tool",
                        "tool_call_id",variant.equals("wrong-result-id")?"another-call":"execution-call:1",
                        "content",json.writeValueAsString(compositionResult(provider,scenario,
                                !List.of("wrong-state","content-decoy").contains(variant),!variant.equals("wrong-value"))));
                var messages=new ArrayList<Map<String,Object>>();messages.add(Map.of("role","user","content",provider.prompt()));
                if(variant.equals("result-before-call"))messages.add(result);
                if(!variant.equals("missing-call"))messages.add(assistant);
                if(variant.equals("duplicate-call"))messages.add(assistant);
                if(variant.equals("duplicate-id"))messages.add(Map.of("role","assistant","tool_calls",List.of(
                        Map.of("id","execution-call:1","type","function","function",Map.of("name","unrelated","arguments","{}")))));
                if(!List.of("missing-result","result-before-call").contains(variant))messages.add(result);
                if(variant.equals("duplicate-result"))messages.add(result);
                if(variant.equals("content-decoy"))messages.add(Map.of("role","user","content",json.writeValueAsString(compositionResult(provider,scenario,true,true))));
                var rejected=post(provider,messages,variant.equals("joint-target")?parentTools(scenario,"not-the-frozen-target"):tools);
                assertEquals(500,rejected.statusCode(),scenario+":"+variant+":"+rejected.body());
                assertEquals(0,provider.snapshot().get("verifiedResults"),variant);
                String reason=variant.equals("joint-target")?"composition_frozen_target_changed"
                        :List.of("arguments-object","wrong-target","wrong-instruction","wrong-background","extra-argument").contains(variant)
                        ?"composition_tool_call_arguments_mismatch":List.of("wrong-state","wrong-value","content-decoy").contains(variant)
                        ?"actual_composition_result_missing":"composition_tool_result_pair_missing_or_duplicate";
                assertEquals(List.of("IOException:"+reason),provider.snapshot().get("failures"),scenario+":"+variant);
            }
        }
    }

    /** 首次Provider真实响应保留原call ID和原参数，后续测试只变更目标条件。 */
    private JsonNode firstParentCall(RuntimeExecutionCompositionProvider provider,List<?> tools) throws Exception {
        var response=post(provider,List.of(Map.of("role","user","content",provider.prompt())),tools);
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body()).path("choices").get(0).path("message").path("tool_calls").get(0);
    }

    /** 父调用保留唯一完整目标枚举，最终响应直接使用正文，参数真值来自第一次真实响应。 */
    private List<?> parentTools(RuntimeExecutionCompositionProvider.Scenario scenario){
        return parentTools(scenario,"frozen-composition-target");
    }

    /** 可单独变更第二轮目录，验证目录与参数一起漂移也不能改写首次发出的目标。 */
    private List<?> parentTools(RuntimeExecutionCompositionProvider.Scenario scenario,String target){
        String wire=scenario==RuntimeExecutionCompositionProvider.Scenario.WORKFLOW?"run_workflow":"delegate";
        String parameter=scenario==RuntimeExecutionCompositionProvider.Scenario.WORKFLOW?"handle":"target_handle";
        return List.of(nativeTool(wire,parameter,Map.of("type","string","enum",List.of(target))));
    }

    /** 只有已完成且准确包含本场景实际结果才可进入父最终答复。 */
    private Map<String,Object> compositionResult(RuntimeExecutionCompositionProvider provider,
            RuntimeExecutionCompositionProvider.Scenario scenario,boolean completed,boolean actual){
        if(scenario==RuntimeExecutionCompositionProvider.Scenario.WORKFLOW)return Map.of("success",completed,"rendered",actual?provider.rendered():"other-result");
        return Map.of("results",List.of(Map.of("state",completed?"completed":"failed","summary",actual?provider.childAnswer():"other-result")));
    }

    /** 函数参数由真实schema结构组成，可显式放置无关字段作为负控。 */
    private Map<String,Object> nativeTool(String name,String parameter,Object field) {
        return Map.of("type","function","function",Map.of("name",name,"parameters",Map.of("type","object","properties",Map.of(parameter,field))));
    }

    /** 同一函数保留无关参数，确保Provider按参数名定位而不是取首个enum。 */
    private Map<String,Object> nativeTool(String name,String decoyParameter,Object decoyField,String parameter,Object field) {
        return Map.of("type","function","function",Map.of("name",name,"parameters",Map.of("type","object","properties",Map.of(decoyParameter,decoyField,parameter,field))));
    }

    /** 子脚本由实际 SYSTEM 标记路由，业务输入不伪装已完成问答。 */
    private ArrayList<Map<String, Object>> firstMessages(RuntimeExecutionCompositionProvider provider) {
        return new ArrayList<>(List.of(Map.of("role", "system", "content", provider.childSystem()),
                Map.of("role", "user", "content", "请先询问必要信息")));
    }

    /** 末条属于本次 HTTP 请求，原消息列表始终保持完整。 */
    private List<Map<String, Object>> withFooter(List<Map<String, Object>> messages, long ordinal) {
        var actual = new ArrayList<Map<String, Object>>();
        actual.add(RuntimeExecutionTimeFixture.outputPrefix(ordinal)); actual.addAll(messages);
        if (ordinal == 2) actual.add(RuntimeExecutionTimeFixture.budget());
        actual.add(RuntimeExecutionTimeFixture.footer(ordinal)); return actual;
    }

    /** 只提供脚本本来就要求的原生提问函数，经真实 HTTP 观察拒绝状态和响应。 */
    private HttpResponse<String> post(RuntimeExecutionCompositionProvider provider, List<Map<String, Object>> messages) throws Exception {
        var tools = List.of("ask_user").stream().map(name -> Map.of("type", "function", "function",
                Map.of("name", name, "parameters", Map.of("type", "object")))).toList();
        return post(provider,messages,tools);
    }

    /** 原始工具声明经HTTP传给脚本，断言实际响应与拒绝，不直接调用私有选路助手。 */
    private HttpResponse<String> post(RuntimeExecutionCompositionProvider provider,List<Map<String,Object>> messages,List<?> tools) throws Exception {
        var body = Map.of("model", "composition-fixture", "stream", false, "messages", messages, "tools", tools);
        var request = HttpRequest.newBuilder(URI.create(provider.endpoint() + "/chat/completions"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
