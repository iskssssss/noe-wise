package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.*;
import com.skillforge.sandbox.persistence.TaskRepository;
import com.skillforge.sandbox.workspace.SessionWorkspaces;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 固定业务结果通道与真实持久工作区组合；普通文件和原始日志不自动公开。 */
class PublicResultExecutionTest {
    @TempDir Path root;
    @Test void typedResultUsesFixedChannelWithoutSelectorAndNeverBecomesAnArtifact()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var properties=TestSandboxProperties.create(root);
        var workspace=new TaskWorkspace(properties);
        var repository=new TaskRepository(json,properties);
        String result="{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"已核对 7 条记录\"}]}";
        String code="import {writeFileSync} from 'node:fs'; writeFileSync('/workspace/output/result.json',"
                +json.writeValueAsString(result)+");";
        var runner=mock(DockerExecutionRunner.class);
        when(runner.resolveEnvironment(any(),any(),any())).thenReturn(new DockerExecutionRunner.ExecutionEnvironment(
                properties.nodeImage(),"sha256:"+"a".repeat(64)));
        when(runner.execute(any(),any(),any(),any(),any())).thenAnswer(call->{
            RequestPolicy.ValidatedRequest request=call.getArgument(1);
            assertEquals(code,request.code());
            Files.writeString(workspace.output(request.taskId()).resolve("result.json"),result);
            return new DockerExecutionRunner.RunnerResult(ExecutionState.COMPLETED,"completed",0,
                    "","",false,false,null,null);
        });
        var executor=mock(ExecutorService.class);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(executor).execute(any());
        var coordinator=new ExecutionCoordinator(new RequestPolicy(json),repository,workspace,runner,
                new ArtifactCollector(workspace),new ProgramToolExchange(json,workspace),executor,Clock.systemUTC(),
                mock(SessionWorkspaces.class));
        for(String id:List.of("fixed")) {
            coordinator.submit(new ExecuteRequest(id,Language.NODE,code,List.of(),List.of(),null,false,
                    Instant.now().plusSeconds(300),TestSandboxProperties.policy(),List.of(),ExecutionProfile.STANDARD,null,null));
            var response=coordinator.get(id);
            assertEquals(ExecutionState.COMPLETED,response.state());
            assertEquals(0,response.exitCode());
            assertEquals(List.of(),response.files());
            assertEquals(0,response.logs().stream().filter(log->"stdout".equals(log.stream()))
                    .mapToInt(ExecutionResponse.LogReference::characters).sum());
            assertEquals(result,Files.readString(workspace.output(id).resolve("result.json")));
            assertEquals("",repository.find(id).orElseThrow().stdout());
        }
        var selected=coordinator.get("fixed").publicResults();
        assertNotNull(selected);
        assertEquals(1,selected.results().size());
        assertEquals("已核对 7 条记录",((PublicResults.Text)selected.results().getFirst()).text());
        verify(runner,times(1)).execute(any(),any(),any(),any(),any());
    }

    @Test void businessResultIsPersistedAndInvalidAuthorityClaimFailsWithoutDeliverable()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var properties=TestSandboxProperties.create(root);
        var workspace=new TaskWorkspace(properties);
        var repository=new TaskRepository(json,properties);
        var runner=mock(DockerExecutionRunner.class);
        when(runner.resolveEnvironment(any(),any(),any())).thenReturn(new DockerExecutionRunner.ExecutionEnvironment(
                properties.nodeImage(),"sha256:"+"a".repeat(64)));
        when(runner.execute(any(),any(),any(),any(),any())).thenAnswer(call->{
            RequestPolicy.ValidatedRequest request=call.getArgument(1);
            Files.writeString(workspace.output(request.taskId()).resolve(ExecutionLimits.PUBLIC_RESULT_FILE),
                    request.taskId().equals("valid")?"{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"核对完成\"}]}"
                            :"{\"results\":[],\"owner\":\"untrusted\"}");
            return new DockerExecutionRunner.RunnerResult(ExecutionState.COMPLETED,"completed",0,
                    "private diagnostic trace","",false,false,null,null);
        });
        var executor=mock(ExecutorService.class);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(executor).execute(any());
        var coordinator=new ExecutionCoordinator(new RequestPolicy(json),repository,workspace,runner,
                new ArtifactCollector(workspace),new ProgramToolExchange(json,workspace),executor,Clock.systemUTC(),
                mock(SessionWorkspaces.class));
        for(String id:List.of("valid","invalid")) {
            var request=new ExecuteRequest(id,Language.NODE,"",List.of(),List.of(),null,false,
                    Instant.now().plusSeconds(300),TestSandboxProperties.policy(),List.of(),
                    ExecutionProfile.STANDARD,null,null);
            coordinator.submit(request);
        }
        var valid=coordinator.get("valid");
        assertEquals(ExecutionState.COMPLETED,valid.state());
        assertEquals("核对完成",((PublicResults.Text)valid.publicResults().results().getFirst()).text());
        assertEquals(List.of(),valid.files());
        assertFalse(json.writeValueAsString(valid).contains("private diagnostic trace"));
        assertEquals("private diagnostic trace",repository.find("valid").orElseThrow().stdout());
        assertEquals(ExecutionState.FAILED,coordinator.get("invalid").state());
        assertEquals("PUBLIC_RESULT_FILE_INVALID",coordinator.get("invalid").error());
        assertNull(coordinator.get("invalid").publicResults());
        var reopened=new TaskRepository(json,properties);
        assertEquals(repository.find("valid").orElseThrow(),reopened.find("valid").orElseThrow());
    }

    @Test void missingFixedFileAndOrdinaryJsonRemainSuccessfulWithoutPublicValues()throws Exception {
        var fixture=fixture((output,request)->{
            if(request.taskId().equals("ordinary")) {
                Files.writeString(output.resolve("business.json"),"{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"not selected\"}]}");
                Files.createDirectories(output.resolve("nested"));
                Files.writeString(output.resolve("nested/result.json"),"{\"owner\":\"not a result channel\"}");
            }
        });
        for(String id:List.of("assert-only","ordinary")) {
            fixture.coordinator().submit(request(id,Language.PYTHON,"assert 3 + 4 == 7"));
            var response=fixture.coordinator().get(id);
            assertEquals(ExecutionState.COMPLETED,response.state());
            assertNull(response.publicResults());
            assertTrue(response.files().isEmpty());
            assertTrue(response.logs().stream().allMatch(log->log.characters()==0));
        }
    }

    @Test void malformedNullDirectoryAndOversizeFixedResultsFailWithoutArtifacts()throws Exception {
        var fixture=fixture((output,request)->{
            Path result=output.resolve(ExecutionLimits.PUBLIC_RESULT_FILE);
            switch(request.taskId()) {
                case "malformed" -> Files.writeString(result,"{broken");
                case "null-result" -> Files.writeString(result,"null");
                case "duplicate" -> Files.writeString(result,"{\"results\":[],\"results\":[]}");
                case "directory" -> Files.createDirectory(result);
                case "oversize" -> Files.writeString(result,"x".repeat(ExecutionLimits.PUBLIC_RESULT_BYTES+1));
                default -> throw new AssertionError("unexpected result case");
            }
        });
        for(String id:List.of("malformed","null-result","duplicate","directory","oversize")) {
            fixture.coordinator().submit(request(id,Language.NODE,""));
            var response=fixture.coordinator().get(id);
            assertEquals(ExecutionState.FAILED,response.state(),id);
            assertEquals("business_result",response.phase());
            assertEquals("PUBLIC_RESULT_FILE_INVALID",response.error());
            assertNull(response.publicResults());
            assertTrue(response.files().isEmpty());
        }
    }

    @Test void fixedResultRejectsExistingAndDanglingSymbolicLinks()throws Exception {
        Path target=Files.writeString(root.resolve("target.json"),"{\"results\":[]}");
        Path probe=root.resolve("link-probe");
        try {
            Files.createSymbolicLink(probe,target);
            Files.delete(probe);
        } catch(UnsupportedOperationException|java.nio.file.FileSystemException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"symbolic links unavailable on this platform");
        }
        var fixture=fixture((output,request)->Files.createSymbolicLink(output.resolve(ExecutionLimits.PUBLIC_RESULT_FILE),
                request.taskId().equals("link")?target:root.resolve("missing.json")));
        for(String id:List.of("link","dangling-link")) {
            fixture.coordinator().submit(request(id,Language.PYTHON,"pass"));
            var response=fixture.coordinator().get(id);
            assertEquals(ExecutionState.FAILED,response.state());
            assertEquals("PUBLIC_RESULT_FILE_INVALID",response.error());
            assertNull(response.publicResults());
            assertTrue(response.files().isEmpty());
        }
    }

    @Test void artifactInputsDoNotChangeFixedOutputResultLocation()throws Exception {
        byte[] source="source input".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var input=new ArtifactFile("data/source.txt",source.length,java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(source)));
        var bytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(input.path()));
            zip.write(source);zip.closeEntry();
        }
        var fixture=fixture((output,request)->{
            assertEquals("source input",Files.readString(root.resolve("workspaces/with-input/input/data/source.txt")));
            Files.writeString(output.resolve(ExecutionLimits.PUBLIC_RESULT_FILE),
                    "{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"input inspected\"}]}");
        });
        try(var archive=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            fixture.coordinator().submit(request("with-input",Language.PYTHON,"pass"),List.of(input),archive);
        }
        var response=fixture.coordinator().get("with-input");
        assertEquals(ExecutionState.COMPLETED,response.state());
        assertEquals("input inspected",((PublicResults.Text)response.publicResults().results().getFirst()).text());
        assertTrue(response.files().isEmpty());
    }

    private ExecuteRequest request(String id,Language language,String code) {
        return new ExecuteRequest(id,language,code,List.of(),List.of(),null,false,
                Instant.now().plusSeconds(300),TestSandboxProperties.policy(),List.of(),ExecutionProfile.STANDARD,null,null);
    }

    private Fixture fixture(ResultWriter writer)throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var properties=TestSandboxProperties.create(root);
        var workspace=new TaskWorkspace(properties);
        var repository=new TaskRepository(json,properties);
        var runner=mock(DockerExecutionRunner.class);
        when(runner.resolveEnvironment(any(),any(),any())).thenReturn(new DockerExecutionRunner.ExecutionEnvironment(
                properties.nodeImage(),"sha256:"+"a".repeat(64)));
        when(runner.execute(any(),any(),any(),any(),any())).thenAnswer(call->{
            RequestPolicy.ValidatedRequest request=call.getArgument(1);
            writer.write(workspace.output(request.taskId()),request);
            return new DockerExecutionRunner.RunnerResult(ExecutionState.COMPLETED,"completed",0,"","",false,false,null,null);
        });
        var executor=mock(ExecutorService.class);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(executor).execute(any());
        return new Fixture(new ExecutionCoordinator(new RequestPolicy(json),repository,workspace,runner,
                new ArtifactCollector(workspace),new ProgramToolExchange(json,workspace),executor,Clock.systemUTC(),
                mock(SessionWorkspaces.class)));
    }

    @FunctionalInterface private interface ResultWriter {
        void write(Path output,RequestPolicy.ValidatedRequest request)throws Exception;
    }
    private record Fixture(ExecutionCoordinator coordinator) { }
}
