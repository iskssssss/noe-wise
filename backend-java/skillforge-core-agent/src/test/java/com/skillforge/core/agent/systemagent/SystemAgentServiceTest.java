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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

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

    @Test void createsDraftWith128EmojiWithoutTruncatingTheName() throws Exception {
        String name = "😀".repeat(128);
        when(mapper.create(anyString(), eq("unicode-agent"), isNull(), eq("MODEL_TASK"), anyString()))
                .thenAnswer(invocation -> {
                    String id = invocation.getArgument(0);
                    Config written = json.readValue((String) invocation.getArgument(4), Config.class);
                    var created = new View(id, "unicode-agent", null, "MODEL_TASK", "draft", 1,
                            null, written, null, Instant.parse("2026-09-22T00:00:00Z"));
                    when(mapper.find(id)).thenReturn(json.writeValueAsString(created));
                    return 1;
                });
        when(mapper.audit(anyString(), eq(1L), eq("create"), eq("42"))).thenReturn(1);

        var created = service.create("unicode-agent", name, "", "MODEL_TASK", "42");

        assertEquals(name, created.draft().name());
        verify(mapper).create(eq(created.id()), eq("unicode-agent"), isNull(), eq("MODEL_TASK"), anyString());
        verify(mapper).audit(created.id(), 1L, "create", "42");
        verifyNoInteractions(models, resources);
    }

    @Test void updatesDraftWith128EmojiWithoutTruncatingTheName() throws Exception {
        current(view("draft", 1, null));
        String name = "😀".repeat(128);
        var replacement = new Config(name, "", "提示词", null, 30, 1, List.of());
        when(mapper.save(eq("1"), eq(1L), anyString())).thenAnswer(invocation -> {
            Config written = json.readValue((String) invocation.getArgument(2), Config.class);
            current(new View("1", "memory-extraction", "memory-extraction", "MODEL_TASK", "draft", 2,
                    null, written, null, Instant.parse("2026-09-22T00:00:00Z")));
            return 1;
        });
        when(mapper.audit("1", 2L, "update", "42")).thenReturn(1);

        var updated = service.update("1", 1, replacement, "42");

        assertEquals(name, updated.draft().name());
        assertEquals(2, updated.revision());
        verify(mapper).save("1", 1L, json.writeValueAsString(replacement));
        verify(mapper).audit("1", 2L, "update", "42");
        verifyNoInteractions(models, resources);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "😀"})
    void rejects129CodePointNamesBeforeCreatingOrUpdating(String unit) throws Exception {
        String name = unit.repeat(129);
        var createFailure = assertThrows(ApiException.class,
                () -> service.create("unicode-agent", name, "", "MODEL_TASK", "42"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, createFailure.getStatus());
        assertEquals("system_agent_config_invalid", createFailure.getDetail());
        verifyNoInteractions(mapper);

        current(view("draft", 1, null));
        var replacement = new Config(name, "", "提示词", null, 30, 1, List.of());
        var updateFailure = assertThrows(ApiException.class, () -> service.update("1", 1, replacement, "42"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, updateFailure.getStatus());
        assertEquals("system_agent_config_invalid", updateFailure.getDetail());
        verify(mapper).find("1");
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(models, resources);
    }

    @Test void savingPublishedDraftDoesNotSwitchProductionPointer() throws Exception {
        current(view("published",3,1L)); when(mapper.save(eq("1"),eq(3L),anyString())).thenReturn(1);
        when(mapper.audit(anyString(),anyLong(),anyString(),anyString())).thenReturn(1);
        service.update("1",3,config,"42");
        verify(mapper).save(eq("1"),eq(3L),anyString());
        verify(mapper,never()).publish(anyString(),anyLong(),anyLong(),anyString());
        verify(mapper,never()).state(anyString(),anyLong(),anyString(),any());
        verify(mapper,never()).append(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void savingThenPublishingUsesCurrentSkillModeForDraftAndVersion() throws Exception {
        var submittedMount = new Mount("SKILL", "101", 7, 512L, null, null);
        var savedMount = new Mount("SKILL", "101", 7, 512L, "ON_DEMAND", null);
        var publishedMount = new Mount("SKILL", "101", 7, 512L, "ALWAYS", null);
        var submitted = new Config("助手", "说明", "提示词", "7", 45, 3, List.of(submittedMount));
        var saved = new Config("助手", "说明", "提示词", "7", 45, 3, List.of(savedMount));
        var published = new Config("助手", "说明", "提示词", "7", 45, 3, List.of(publishedMount));
        current(new View("1", "skill-agent", null, "AGENT_RUN", "draft", 1, null,
                submitted, null, Instant.parse("2026-09-22T00:00:00Z")));
        when(resources.validateDraft(submitted.mounts())).thenReturn(saved.mounts());
        when(mapper.save("1", 1L, json.writeValueAsString(saved))).thenAnswer(invocation -> {
            current(new View("1", "skill-agent", null, "AGENT_RUN", "draft", 2, null,
                    saved, null, Instant.parse("2026-09-22T00:00:00Z")));
            return 1;
        });
        when(mapper.audit(anyString(), anyLong(), anyString(), anyString())).thenReturn(1);

        assertEquals(saved, service.update("1", 1, submitted, "42").draft());
        // 保存后技能切换当前发布模式，发布时重新解析并同步草稿和不可变版本。
        when(resources.validate(saved.mounts(), false)).thenReturn(published.mounts());
        String publishedJson = json.writeValueAsString(published);
        when(mapper.publish("1", 2L, 1L, publishedJson)).thenAnswer(invocation -> {
            current(new View("1", "skill-agent", null, "AGENT_RUN", "published", 3, 1L,
                    published, published, Instant.parse("2026-09-22T00:00:00Z")));
            return 1;
        });

        var result = service.transition("1", 2, "publish", "42");

        assertEquals(published, result.draft());
        assertEquals(published, result.published());
        assertEquals(3, result.revision());
        assertEquals(1L, result.publishedVersion());
        var ordered = inOrder(mapper);
        ordered.verify(mapper).publish("1", 2L, 1L, publishedJson);
        ordered.verify(mapper).append("1", 1L, publishedJson, "42");
        verify(mapper, times(1)).save(anyString(), anyLong(), anyString());
        verify(mapper, never()).state(anyString(), anyLong(), anyString(), any());
        verify(mapper).audit("1", 3L, "publish", "42");
        verify(resources).validateDraft(submitted.mounts());
        verify(resources).validate(saved.mounts(), false);
        verifyNoMoreInteractions(resources);
    }
    @Test void losingPublishCasDoesNotAppendVersion() throws Exception {
        current(view("published",3,1L));
        var failure = assertThrows(ApiException.class,()->service.transition("1",3,"publish","42"));
        assertEquals(HttpStatus.CONFLICT, failure.getStatus());
        verify(mapper).publish("1", 3L, 2L, json.writeValueAsString(config));
        verify(mapper,never()).append(anyString(),anyLong(),anyString(),anyString());
        verify(mapper,never()).save(anyString(),anyLong(),anyString());
        verify(mapper,never()).audit(anyString(),anyLong(),anyString(),anyString());
    }
    @Test void enablingValidatesCurrentSkillsWithoutRewritingPublishedConfiguration() throws Exception {
        var oldMount = new Mount("SKILL", "101", 7, 512L, "ON_DEMAND", null);
        var currentMount = new Mount("SKILL", "101", 7, 512L, "ALWAYS", null);
        var original = new Config("助手", "说明", "提示词", "7", 45, 3, List.of(oldMount));
        current(new View("1", "skill-agent", null, "AGENT_RUN", "disabled", 3, 1L,
                original, original, Instant.parse("2026-09-22T00:00:00Z")));
        when(resources.validate(original.mounts(), false)).thenReturn(List.of(currentMount));
        when(mapper.state("1", 3L, "published", 1L)).thenReturn(1);
        when(mapper.audit("1", 4L, "enable", "42")).thenReturn(1);

        service.transition("1", 3, "enable", "42");

        verify(resources).validate(original.mounts(), false);
        verify(mapper).state("1", 3L, "published", 1L);
        verify(mapper, never()).publish(anyString(), anyLong(), anyLong(), anyString());
        verify(mapper, never()).save(anyString(), anyLong(), anyString());
        verify(mapper, never()).append(anyString(), anyLong(), anyString(), anyString());
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
    @Test void acceptsExplicitModelRevisionAndReplaysFrozenSkillsWithoutCurrentCatalog() throws Exception {
        var skill = new Mount("SKILL", "101", 7, 512L, "ON_DEMAND", null);
        var skillConfig = new Config("助手", "说明", "提示词", "7", 45, 3, List.of(skill));
        var agent = new View("1", "skill-agent", "reporting", "AGENT_RUN", "published", 3, 1L,
                skillConfig, skillConfig, Instant.parse("2026-09-22T00:00:00Z"));
        when(mapper.findPurpose("reporting")).thenReturn(json.writeValueAsString(agent));
        var model=mock(PlatformModelConfig.class); when(model.revision()).thenReturn(5L);
        when(models.requireEnabled("7","LLM")).thenReturn(model);
        var frozenSkill = new PlatformAgentResources.PublishedMount(skill, "原技能正文", null, "101", "201",
                java.util.Map.of("activation_mode", "ON_DEMAND"));
        when(resources.resolveForExecution(skillConfig.mounts(),false)).thenReturn(List.of(frozenSkill));
        when(mapper.existingRun("2","1","event")).thenReturn(null);
        // 模拟插入完成后可见的权威行，指纹从 INSERT 参数捕获。
        doAnswer(invocation->{
            String fingerprint=invocation.getArgument(4);
            var node=json.createObjectNode().put("id","11").put("tenant_id","2").put("agent_id","1")
                .put("input_fingerprint",fingerprint).put("version",1).put("debug",false)
                .put("model_revision",5).put("input","输入").put("status","READY")
                .put("created_at","2026-09-22T00:00:00Z");
            node.set("config",json.valueToTree(skillConfig));
            node.set("mount_snapshot",json.valueToTree(List.of(frozenSkill)));
            when(mapper.existingRun("2","1","event")).thenReturn(node.toString());
            return 1;
        }).when(mapper).insertRun(anyString(),eq("2"),eq("1"),eq("event"),anyString(),eq(1L),eq(false),anyString(),eq(5L),eq("输入"),anyString());
        var run=service.acceptPurpose("reporting","2","event","输入");
        assertEquals(5,run.modelRevision()); assertEquals(1L,run.version());
        assertEquals(List.of(frozenSkill), run.mounts());
        clearInvocations(models, resources);
        var currentConfig = new Config("新版助手", "说明", "新提示词", "8", 60, 3,
                List.of(new Mount("SKILL", "101", 7, 512L, "ALWAYS", null)));
        when(mapper.findPurpose("reporting")).thenReturn(json.writeValueAsString(
                new View("1", "skill-agent", "reporting", "AGENT_RUN", "published", 4, 2L,
                        currentConfig, currentConfig, Instant.parse("2026-09-23T00:00:00Z"))));

        assertEquals(run,service.acceptPurpose("reporting","2","event","输入"));
        assertThrows(ApiException.class,()->service.acceptPurpose("reporting","2","event","不同输入"));
        verifyNoInteractions(models, resources);
        verify(mapper, times(1)).insertRun(anyString(), eq("2"), eq("1"), eq("event"), anyString(),
                eq(1L), eq(false), anyString(), eq(5L), eq("输入"), anyString());
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
