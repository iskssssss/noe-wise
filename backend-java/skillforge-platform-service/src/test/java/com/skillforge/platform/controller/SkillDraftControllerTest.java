package com.skillforge.platform.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.capability.SkillDraftContent;
import com.skillforge.core.capability.SkillDraftPublication;
import com.skillforge.core.capability.SkillDraftValidation;
import com.skillforge.core.capability.SkillDraftService;
import com.skillforge.core.capability.SkillDraftView;
import com.skillforge.core.foundation.constant.RoleConstants;
import com.skillforge.platform.exception.GlobalExceptionHandler;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.RequestMapping;

/** 平台 Skill 草稿 API 的公共 scope、JSON/multipart 绑定和并发头契约。 */
class SkillDraftControllerTest {

    private static final String PATH = "/api/v1/admin/capabilities/skill-drafts";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private SkillDraftService drafts;
    private MockMvc mvc;
    private FoundationAccess access;

    @BeforeEach
    void setUp() {
        drafts = mock(SkillDraftService.class);
        access = mock(FoundationAccess.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new SkillDraftController(drafts, access))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .setValidator(validator)
                .build();
    }

    @Test
    void exactHistoricalVersionExposesRequiresPlanWhileEditingDraftKeepsNull() throws Exception {
        var secured = MockMvcBuilders.standaloneSetup(new SkillDraftController(drafts, access))
                .addInterceptors(new com.skillforge.core.foundation.access.FoundationPermissionInterceptor(access))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        for (boolean requiresPlan : List.of(true, false)) {
            when(drafts.version(CapabilityCatalogScope.platform(), 99L)).thenReturn(new SkillDraftView(
                    99L, 11L, 99L, 0L, "v1", Map.of(), true, false,
                    SkillActivationMode.ALWAYS, null, List.of(), 99L, null, requiresPlan));
            secured.perform(get(PATH + "/version").param("versionId", "99"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("99"))
                    .andExpect(jsonPath("$.data.requires_plan").value(requiresPlan));
        }
        when(drafts.get(CapabilityCatalogScope.platform(), 12L)).thenReturn(view(12L));
        String body = secured.perform(get(PATH + "/detail").param("draftId", "12"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var draft = json.readTree(body).path("data");
        assertTrue(draft.has("requires_plan"));
        assertTrue(draft.path("requires_plan").isNull());
        org.mockito.Mockito.verify(access, org.mockito.Mockito.times(3)).require("foundation.skills.read");
    }

    @Test
    void newSkillUsesPlatformScopeAndReturnsExistingDraftShape() throws Exception {
        when(drafts.createSkill(CapabilityCatalogScope.platform(), "new-skill", "技能", "说明"))
                .thenReturn(view(12L));
        mvc.perform(post(PATH + "/new").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capability_key\":\"new-skill\",\"name\":\"技能\",\"description\":\"说明\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.definition_id").value(11))
                .andExpect(jsonPath("$.data.id").value(12));
        verify(access).require("foundation.capabilities.create");
        verify(drafts).createSkill(CapabilityCatalogScope.platform(), "new-skill", "技能", "说明");
        assertEquals("foundation.skills.create", SkillDraftController.class
                .getMethod("createSkill", SkillDraftController.NewSkillCommand.class)
                .getAnnotation(RequirePermission.class).value());
    }

    @Test
    void newSkillWithoutCatalogCreationPermissionNeverCallsDraftService() throws Exception {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "缺少目录创建权限"))
                .when(access).require("foundation.capabilities.create");
        mvc.perform(post(PATH + "/new").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capability_key\":\"new-skill\",\"name\":\"技能\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(drafts);
    }

    @Test
    void newSkillRejectsInvalidMetadataBeforeAccessOrService() throws Exception {
        for (String body : List.of("{\"capability_key\":\"\",\"name\":\"技能\"}",
                "{\"capability_key\":\"new-skill\",\"name\":\"\"}",
                json.writeValueAsString(Map.of("capability_key", "x".repeat(129), "name", "技能")),
                json.writeValueAsString(Map.of("capability_key", "new-skill", "name", "x".repeat(256))),
                json.writeValueAsString(Map.of("capability_key", "new-skill", "name", "技能", "description", "x".repeat(4001))))) {
            mvc.perform(post(PATH + "/new").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(access, drafts);
    }

    @Test
    void activeDraftUsesPlatformScopeAndReturnsNoContentWhenAbsent() throws Exception {
        when(drafts.active(CapabilityCatalogScope.platform(), 11L)).thenReturn(Optional.empty());

        mvc.perform(get(PATH).param("definitionId", "11"))
                .andExpect(status().isNoContent());

        verify(drafts).active(CapabilityCatalogScope.platform(), 11L);
    }

    @Test
    void putBindsCompleteSnakeCaseRequestAndAllowsEmptyVersionKeyAndFiles() throws Exception {
        when(drafts.saveSelected(eq(CapabilityCatalogScope.platform()), eq(12L), eq(4L), any(), any()))
                .thenReturn(view(12L));

        mvc.perform(put(PATH).header("If-Match", "W/\"4\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft_id":12,"version_key":"","files":{},
                                 "model_invocable":true,"user_invocable":false,
                                 "activation_mode":"ALWAYS",
                                 "conflict_group":null,"required_capabilities":[{"type":"TOOL","capability_id":"21"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(12))
                .andExpect(jsonPath("$.data.version_key").value(""));

        ArgumentCaptor<SkillDraftContent> content = ArgumentCaptor.forClass(SkillDraftContent.class);
        verify(drafts).saveSelected(eq(CapabilityCatalogScope.platform()), eq(12L), eq(4L), content.capture(),
                eq(java.util.List.of(new com.skillforge.core.capability.SkillDependencySelection(com.skillforge.core.capability.CapabilityType.TOOL, "21"))));
        assertEquals("", content.getValue().versionKey());
        assertEquals(Map.of(), content.getValue().files());
        assertEquals(SkillActivationMode.ALWAYS, content.getValue().activationMode());
    }

    @Test
    void putWithoutIfMatchIsRejectedBeforeTheService() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft_id":12,"version_key":"","files":{},
                                 "model_invocable":false,"user_invocable":false,
                                 "required_capabilities":[]}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(drafts);
    }

    @Test
    void putRejectsNullDependencyBeforeTheService() throws Exception {
        mvc.perform(put(PATH).header("If-Match", "\"4\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"draft_id":12,"version_key":"","files":{},
                                 "model_invocable":false,"user_invocable":false,
                                 "required_capabilities":[null]}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(drafts);
    }

    @Test
    void importZipBindsDraftIdFromFormDataTextField() throws Exception {
        byte[] archive = {1, 2, 3};
        when(drafts.importZip(CapabilityCatalogScope.platform(), 12L, 4L, archive))
                .thenReturn(view(12L));

        mvc.perform(multipart(PATH + "/import-zip")
                        .file(new MockMultipartFile("archive", "skill.zip", "application/zip", archive))
                        .param("draft_id", "12")
                        .header("If-Match", "\"4\""))
                .andExpect(status().isOk());

        verify(drafts).importZip(CapabilityCatalogScope.platform(), 12L, 4L, archive);
    }

    @Test
    void publishBindsSnakeCaseRequestAndSerializesPublicationResponse() throws Exception {
        when(drafts.publishChecked(CapabilityCatalogScope.platform(), 12L, 4L, 8L))
                .thenReturn(new SkillDraftService.PublicationOutcome(new SkillDraftPublication(99L, 9L), new SkillDraftValidation(true, java.util.List.of())));

        mvc.perform(post(PATH + "/publish").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft_id\":12,\"revision\":4,\"definition_revision\":8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version_id").value(99))
                .andExpect(jsonPath("$.data.definition_revision").value(9))
                .andExpect(jsonPath("$.data.validation.valid").value(true));

        verify(drafts).publishChecked(CapabilityCatalogScope.platform(), 12L, 4L, 8L);
    }

    @Test
    void exposesPlatformRolePathAndSnakeCaseRequestFields() {
        RequestMapping mapping = SkillDraftController.class.getAnnotation(RequestMapping.class);

        assertArrayEquals(new String[]{PATH}, mapping.value());
        assertTrue(java.util.Arrays.stream(SkillDraftController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(RequirePermission.class)).filter(java.util.Objects::nonNull)
                .anyMatch(permission -> permission.value().equals("foundation.skills.publish")));
        assertEquals("draft_id", jsonName(SkillDraftController.SaveCommand.class, "draftId"));
        assertEquals("model_invocable", jsonName(SkillDraftController.SaveCommand.class, "modelInvocable"));
        assertEquals("activation_mode", jsonName(SkillDraftController.SaveCommand.class, "activationMode"));
        assertEquals("base_version_id", jsonName(SkillDraftController.CreateCommand.class, "baseVersionId"));
    }

    private static SkillDraftView view(long id) {
        return new SkillDraftView(id, 11L, null, 4L, "", Map.of(), true, false,
                SkillActivationMode.ALWAYS, null, List.of(), null, null, null);
    }

    private static String jsonName(Class<?> type, String componentName) {
        for (RecordComponent component : type.getRecordComponents()) {
            if (component.getName().equals(componentName)) {
                return component.getAccessor().getAnnotation(JsonProperty.class).value();
            }
        }
        throw new AssertionError("未找到请求字段: " + componentName);
    }
}
