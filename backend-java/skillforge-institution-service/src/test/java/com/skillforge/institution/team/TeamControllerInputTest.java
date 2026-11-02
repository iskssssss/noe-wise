package com.skillforge.institution.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实请求绑定与公共异常映射的直接回归；拒绝发生在调用团队写服务之前。 */
class TeamControllerInputTest {
    private final TeamService teams = mock(TeamService.class);
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private MockMvc mvc;

    /** 仅提供入口所属租户和稳定目录，不装配真实认证或数据库。 */
    @BeforeEach void setup() {
        TenantContext.setTenantId("7");
        var baseline = new TeamDefinition("existing", "已有团队", "保持原目录", "draft", 1,
                List.of(new TeamDefinition.Member("101", "1", "主助手", "", List.of())), "101");
        when(teams.list("7")).thenReturn(List.of(baseline));
        mvc = MockMvcBuilders.standaloneSetup(new TeamController(teams))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    /** 释放本测试设置的租户上下文。 */
    @AfterEach void clearTenant() { TenantContext.clear(); }

    /** 合法成员仅改变为重复集合时拒绝且不进入团队写服务。 */
    @Test void duplicateMembersAreClientErrorWithoutDirectoryChange() throws Exception {
        assertRejected(List.of(new TeamController.Member("101"), new TeamController.Member("101")), "101");
    }

    /** 主助手不属于当前成员集合时保留整个已有目录。 */
    @Test void primaryOutsideMembersIsClientErrorWithoutDirectoryChange() throws Exception {
        assertRejected(List.of(new TeamController.Member("101")), "102");
    }

    /** 空集合不形成半建团队。 */
    @Test void emptyMembersAreClientErrorWithoutDirectoryChange() throws Exception {
        assertRejected(List.of(), "101");
    }

    /** 合法输入后的服务内部参数异常不能被请求转换边界伪装为客户端拒绝。 */
    @Test void internalServiceFailureRemainsServerError() throws Exception {
        when(teams.save(anyString(), any(), any())).thenThrow(new IllegalArgumentException("internal contract failure"));
        mvc.perform(post("/api/v1/teams").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request(List.of(new TeamController.Member("101")), "101"))))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    /** 比较拒绝前后的完整公开目录，并独立断言没有任何保存调用。 */
    private void assertRejected(List<TeamController.Member> members, String primary) throws Exception {
        var before = json.readTree(mvc.perform(get("/api/v1/teams")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        mvc.perform(post("/api/v1/teams").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request(members, primary))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.retryable").value(false));
        var after = json.readTree(mvc.perform(get("/api/v1/teams")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        assertEquals(before, after);
        verify(teams, never()).save(anyString(), any(), any());
    }

    /** 其余字段保持合法基线，只暴露被测成员/主助手条件。 */
    private static TeamController.SaveRequest request(List<TeamController.Member> members, String primary) {
        return new TeamController.SaveRequest("新团队", "合法基线", "draft", 0, members, primary);
    }
}
