package com.skillforge.institution.artifact;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证真实 MVC 参数绑定，避免固定路由改名后写接口仍依赖 query。 */
class ArtifactHttpContractTest {
    private static final String BASE="11111111-1111-1111-1111-111111111111";
    private static final String TARGET="22222222-2222-2222-2222-222222222222";
    @Test
    void downloadIdentifierAndOptionsComeFromJsonBody() throws Exception {
        var access = mock(ArtifactAccess.class);
        when(access.ticket("artifact-1", "file", "index.html")).thenReturn("https://example.test/content");
        var mvc = MockMvcBuilders.standaloneSetup(new ArtifactController(mock(ArtifactStorage.class), access,
                mock(ArtifactVersionService.class))).build();
        mvc.perform(post("/api/v1/chat/artifacts/download-ticket")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"artifact-1\",\"payload\":{\"path\":\"index.html\",\"zip\":false}}"))
                .andExpect(status().isOk());
        verify(access).ticket("artifact-1", "file", "index.html");
    }

    @Test
    void writesDoNotFallBackToQueryOrOldResourcePaths() throws Exception {
        var access = mock(ArtifactAccess.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ArtifactController(mock(ArtifactStorage.class), access,
                mock(ArtifactVersionService.class))).build();
        mvc.perform(post("/api/v1/chat/artifacts/preview-ticket").param("id", "artifact-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat/artifacts/artifact-1/preview-ticket"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/chat/artifacts/download-ticket")
                .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"artifact-1\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(access);
    }

    @Test void diffAndRecoveryUseTypedJsonBodies()throws Exception {
        var versions=mock(ArtifactVersionService.class);
        when(versions.diff(any(),any())).thenReturn(new ArtifactVersionService.DiffResult("comparable",
                new ArtifactVersionService.Reference(BASE,"a.txt",1,"0".repeat(64)),
                new ArtifactVersionService.Reference(TARGET,"b.txt",1,"1".repeat(64)),java.util.List.of()));
        when(versions.recovery(anyString(),anyList())).thenReturn(new ArtifactVersionService.RecoveryResult(
                "validated","session",java.util.List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new ArtifactController(mock(ArtifactStorage.class),
                mock(ArtifactAccess.class),versions)).build();

        mvc.perform(post("/api/v1/chat/artifacts/diff").contentType(MediaType.APPLICATION_JSON)
                .content("{\"base\":{\"artifact_id\":\""+BASE+"\",\"path\":\"a.txt\"},"
                        +"\"target\":{\"artifact_id\":\""+TARGET+"\",\"path\":\"b.txt\"}}"))
                .andExpect(status().isOk());
        verify(versions).diff(new com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput(BASE,"a.txt"),
                new com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput(TARGET,"b.txt"));

        mvc.perform(post("/api/v1/chat/artifacts/recovery-inputs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"session_id\":\"session\",\"artifact_inputs\":[{\"artifact_id\":\""+BASE
                        +"\",\"path\":\"a.txt\"}]}"))
                .andExpect(status().isOk());
        verify(versions).recovery("session",java.util.List.of(
                new com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput(BASE,"a.txt")));
    }
}
