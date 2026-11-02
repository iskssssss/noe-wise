package com.skillforge.core.agent.systemagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 发布指针、冻结受理及生命周期的领域回归。 */
class SystemAgentServiceTest {
    @Test void configNamesBusinessCallsAndRejectsOldStepKeysEvenWithLenientGlobalJson() throws Exception {
        var codec = new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String current = "{\"name\":\"系统\",\"description\":\"\",\"system_prompt\":\"正文\",\"model_config_id\":\"7\",\"timeout_seconds\":30,\"max_business_model_calls\":5,\"mounts\":[]}";
        var parsed = codec.readValue(current, Config.class);
        assertEquals(5, parsed.maxBusinessModelCalls());
        var written = codec.readTree(codec.writeValueAsString(parsed));
        assertTrue(written.has("max_business_model_calls")); assertFalse(written.has("max_steps"));
        assertThrows(Exception.class, () -> codec.readValue(current.replace("max_business_model_calls", "max_steps"), Config.class));
        assertThrows(Exception.class, () -> codec.readValue(current.replace("\"mounts\":[]", "\"mounts\":[],\"max_steps\":5"), Config.class));
        assertThrows(Exception.class, () -> codec.readValue(current.replace("\"max_business_model_calls\":5,", ""), Config.class));
    }
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final SystemAgentMapper mapper=mock(SystemAgentMapper.class);
    final PlatformModelCatalogService models=mock(PlatformModelCatalogService.class);
    final PlatformAgentResources resources=mock(PlatformAgentResources.class);
    final SystemAgentService service=new SystemAgentService(mapper,json,models,resources);
    final Config config=new Config("提取","说明","提取候选","7",30,1,List.of());

    View view(String status,long revision,Long version) {
        return new View("1","memory-extraction","memory-extraction","MODEL_TASK",status,revision,version,
                config,version==null?null:config,Instant.parse("2026-09-22T00:00:00Z"));
    }
    void current(View value) throws Exception {when(mapper.find("1")).thenReturn(json.writeValueAsString(value));}

    @Test void savingPublishedDraftDoesNotSwitchProductionPointer() throws Exception {
        current(view("published",3,1L)); when(mapper.save(eq("1"),eq(3L),anyString())).thenReturn(1);
        when(mapper.audit(anyString(),anyLong(),anyString(),anyString())).thenReturn(1);
        service.update("1",3,config,"42");
        verify(mapper).save(eq("1"),eq(3L),anyString());
        verify(mapper,never()).state(anyString(),anyLong(),anyString(),any());
        verify(mapper,never()).append(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void losingPublishCasDoesNotAppendVersion() throws Exception {
        current(view("published",3,1L));
        assertThrows(ApiException.class,()->service.transition("1",3,"publish","42"));
        verify(mapper,never()).append(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void purposeBoundAgentCannotBeArchivedOrDeleted() throws Exception {
        current(view("disabled",3,1L));
        assertThrows(ApiException.class,()->service.transition("1",3,"archive","42"));
        assertThrows(ApiException.class,()->service.delete("1",3,"42"));
        verify(mapper,never()).state(anyString(),anyLong(),anyString(),any());
    }
    @Test void modelTaskCannotMountTools() throws Exception {
        current(view("draft",1,null));
        var invalid=new Config("提取","","Prompt","7",30,1,
                List.of(new Mount("TOOL","8",1,null,null,"9")));
        assertThrows(ApiException.class,()->service.update("1",1,invalid,"42"));
        verify(mapper,never()).save(anyString(),anyLong(),anyString());
    }
    @Test void acceptsAndPersistsExplicitModelRevision() throws Exception {
        var agent=view("published",3,1L);
        when(mapper.findPurpose("memory-extraction")).thenReturn(json.writeValueAsString(agent));
        var model=mock(PlatformModelConfig.class); when(model.revision()).thenReturn(5L);
        when(models.requireEnabled("7","LLM")).thenReturn(model);
        when(resources.resolveForExecution(List.of(),false)).thenReturn(List.of());
        when(mapper.existingRun("2","1","event")).thenReturn(null);
        // 模拟插入完成后可见的权威行，指纹从 INSERT 参数捕获。
        doAnswer(invocation->{
            String fingerprint=invocation.getArgument(4);
            var node=json.createObjectNode().put("id","11").put("tenant_id","2").put("agent_id","1")
                .put("input_fingerprint",fingerprint).put("version",1).put("debug",false)
                .put("model_revision",5).put("input","输入").put("status","READY")
                .put("created_at","2026-09-22T00:00:00Z");
            node.set("config",json.valueToTree(config)); node.putArray("mount_snapshot");
            when(mapper.existingRun("2","1","event")).thenReturn(node.toString());
            return 1;
        }).when(mapper).insertRun(anyString(),eq("2"),eq("1"),eq("event"),anyString(),eq(1L),eq(false),anyString(),eq(5L),eq("输入"),anyString());
        var run=service.acceptPurpose("memory-extraction","2","event","输入");
        assertEquals(5,run.modelRevision()); assertEquals(1L,run.version());
        reset(models);
        assertEquals(run,service.acceptPurpose("memory-extraction","2","event","输入"));
        verifyNoInteractions(models);
        assertThrows(ApiException.class,()->service.acceptPurpose("memory-extraction","2","event","不同输入"));
    }

    @Test void contextSummaryReplayKeepsFrozenLimitsWhenCallerRemainingChanges() throws Exception {
        var context = new Config("摘要", "", "摘要提示", "7", 30, 1, List.of());
        var agent = new View("6", "context-summary", "context-summary", "MODEL_TASK", "published", 1,
                1L, context, context, Instant.parse("2026-09-22T00:00:00Z"));
        when(mapper.findPurpose("context-summary")).thenReturn(json.writeValueAsString(agent));
        var input = "{\"sourceDigest\":\"digest\",\"maxOutputTokens\":100,\"remainingMillis\":5000,\"messages\":[]}";
        var node = json.createObjectNode().put("id", "12").put("tenant_id", "2").put("agent_id", "6")
                .put("input_fingerprint", "frozen").put("version", 1).put("debug", false)
                .put("model_revision", 5).put("input", input).put("status", "READY")
                .put("created_at", "2026-09-22T00:00:00Z");
        node.set("config", json.valueToTree(context)); node.putArray("mount_snapshot");
        when(mapper.existingRun("2", "6", "same-key")).thenReturn(node.toString());

        var replay = service.acceptPurpose("context-summary", "2", "same-key",
                "{\"sourceDigest\":\"digest\",\"maxOutputTokens\":200,\"remainingMillis\":1000,\"messages\":[]}");

        assertEquals(input, replay.input());
        assertEquals(5000, json.readTree(replay.input()).path("remainingMillis").asInt());
        var replacement = new Config("新版摘要", "", "新版提示", "8", 60, 1, List.of());
        var disabled = new View("6", "context-summary", "context-summary", "MODEL_TASK", "disabled", 2,
                2L, replacement, replacement, Instant.parse("2026-09-23T00:00:00Z"));
        when(mapper.findPurpose("context-summary")).thenReturn(json.writeValueAsString(disabled));
        var resumed = service.acceptPurpose("context-summary", "2", "same-key",
                "{\"sourceDigest\":\"digest\",\"maxOutputTokens\":300,\"remainingMillis\":500,\"messages\":[]}");
        assertEquals(replay, resumed);
        assertEquals(context, resumed.config());
        assertEquals(1L, resumed.version());
        assertEquals(5L, resumed.modelRevision());
        assertThrows(ApiException.class, () -> service.acceptPurpose("context-summary", "2", "same-key",
                "{\"sourceDigest\":\"changed\",\"maxOutputTokens\":100,\"remainingMillis\":500,\"messages\":[]}"));
        assertThrows(ApiException.class, () -> service.acceptPurpose("context-summary", "2", "same-key",
                "{\"sourceDigest\":\"digest\",\"maxOutputTokens\":100,\"remainingMillis\":500,\"messages\":[{\"text\":\"changed\"}]}"));
        verify(mapper, never()).insertRun(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), anyString(), anyLong(), anyString(), anyString());
        verifyNoInteractions(models, resources);
    }

    @Test void runViewKeepsProviderTokensBeyondIntegerRange() throws Exception {
        var row = json.createObjectNode().put("id", "11").put("agent_id", "1").put("version", 1)
                .put("debug", true).put("status", "COMPLETED").put("input_tokens", 2_147_483_648L)
                .put("output_tokens", 9_007_199_254_740_993L).put("duration_ms", 1)
                .put("created_at", "2026-09-22T00:00:00Z");
        when(mapper.run("11", "2")).thenReturn(row.toString());

        var result = service.run("11", "2");

        assertEquals(2_147_483_648L, result.inputTokens());
        assertEquals(9_007_199_254_740_993L, result.outputTokens());
        var response = json.readTree(json.writeValueAsString(result));
        assertEquals("2147483648", response.path("input_tokens").asText());
        assertEquals("9007199254740993", response.path("output_tokens").asText());
    }
}
