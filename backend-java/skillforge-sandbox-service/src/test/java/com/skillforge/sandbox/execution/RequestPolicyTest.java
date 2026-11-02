package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.*;
import com.skillforge.sandbox.api.SandboxRequestException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 校验流式清单与封闭执行配置，字节完整性由工作区 ZIP 测试验证。 */
class RequestPolicyTest {
    private final RequestPolicy policy=new RequestPolicy(new ObjectMapper());
    private final Instant deadline=Instant.now().plusSeconds(600);
    private ExecuteRequest inline(boolean program,List<String> outputs) {
        return new ExecuteRequest("task",Language.NODE,"console.log('ok')",List.of(),List.of(),null,
                program,deadline,TestSandboxProperties.policy(),outputs,ExecutionProfile.STANDARD,null,null);
    }
    @Test void identityIncludesProgramModeOutputsAndFileManifest() {
        var first=inline(false,List.of());
        assertNotEquals(policy.validate(first).digest(),policy.validate(inline(true,List.of())).digest());
        assertNotEquals(policy.validate(first).digest(),policy.validate(inline(false,List.of("report.txt"))).digest());
        assertNotEquals(policy.validate(first).digest(),policy.validate(first,List.of(new ArtifactFile("data/input.csv",1,"a".repeat(64)))).digest());
    }
    @Test void workspaceBindingParticipatesInIdentityAndSupportsOrdinaryCode() {
        var binding = workspace("a");
        var raw = inline(false,  List.of());
        var bound = new ExecuteRequest(raw.taskId(), raw.language(), raw.code(), raw.dependencies(), raw.inputs(),
                raw.scriptPath(), raw.programTools(), raw.deadlineAt(), raw.executionPolicy(),
                raw.outputFiles(), raw.profile(), raw.environmentVersion(), raw.inputJson(), binding);
        assertEquals(binding, policy.validate(bound).workspace());
        assertNotEquals(policy.validate(raw).digest(), policy.validate(bound).digest());
        var other = new ExecuteRequest(raw.taskId(), raw.language(), raw.code(), raw.dependencies(), raw.inputs(),
                raw.scriptPath(), raw.programTools(), raw.deadlineAt(), raw.executionPolicy(),
                raw.outputFiles(), raw.profile(), raw.environmentVersion(), raw.inputJson(), workspace("b"));
        assertNotEquals(policy.validate(bound).digest(), policy.validate(other).digest());
    }
    @Test void shellRequiresWorkspaceProfileAndRejectsConflictingChannels() {
        var shell = shell(false, List.of(), workspace("a"), ExecutionProfile.WORKSPACE_SHELL_V1);
        assertEquals("/input/main.sh", policy.validate(shell).entryPath());
        assertEquals("INVALID_WORKSPACE_SHELL_PROFILE", assertThrows(SandboxRequestException.class,
                () -> policy.validate(shell(false, List.of(), null, ExecutionProfile.WORKSPACE_SHELL_V1))).code());
        assertEquals("INVALID_EXECUTION_LANGUAGE", assertThrows(SandboxRequestException.class,
                () -> policy.validate(shell(false, List.of(), workspace("a"), ExecutionProfile.STANDARD))).code());
        for (var invalid : List.of(
                shell(true, List.of(), workspace("a"), ExecutionProfile.WORKSPACE_SHELL_V1),
                shell(false, List.of(new DependencySpec("kleur", "4.1.5")), workspace("a"), ExecutionProfile.WORKSPACE_SHELL_V1))) {
            assertEquals("INVALID_WORKSPACE_SHELL_PROFILE", assertThrows(SandboxRequestException.class,
                    () -> policy.validate(invalid)).code());
        }
    }

    /** 工作区 Shell 的资源清单仍逐项绑定传输摘要，不改变 Bash 入口和禁止安装约束。 */
    @Test void shellAcceptsFrozenPackageResourcesAndRejectsEntryReplacementOrManifestDrift() {
        var shell=shell(false,List.of(),workspace("a"),ExecutionProfile.WORKSPACE_SHELL_V1);
        var asset=new ArtifactFile("assets/template.bin",3,"b".repeat(64));
        var script=new ArtifactFile("scripts/check.sh",10,"c".repeat(64));
        var files=List.of(asset,script);
        var withResources=shellResources(shell,files);
        assertEquals("/input/main.sh",policy.validate(withResources,files).entryPath());
        assertEquals(files,policy.validate(withResources,files).inputs());
        assertNotEquals(policy.validate(shell).digest(),policy.validate(withResources,files).digest());
        assertThrows(SandboxRequestException.class,()->policy.validate(withResources,List.of(asset)));
        assertThrows(SandboxRequestException.class,()->policy.validate(withResources,List.of(asset,
                new ArtifactFile(script.path(),script.size(),"d".repeat(64)))));
        for(String path:List.of("main.sh","main.sh/child","../escape")) {
            var forbidden=List.of(new ArtifactFile(path,1,"e".repeat(64)));
            assertThrows(SandboxRequestException.class,()->policy.validate(shellResources(shell,forbidden),forbidden));
        }
    }

    /** 从同一 Shell 任务形成带资源的请求，保留全部授权和执行策略字段。 */
    private ExecuteRequest shellResources(ExecuteRequest shell,List<ArtifactFile> files) {
        return new ExecuteRequest(shell.taskId(),shell.language(),shell.code(),shell.dependencies(),files,null,
                shell.programTools(),shell.deadlineAt(),shell.executionPolicy(),shell.outputFiles(),shell.profile(),
                shell.environmentVersion(),shell.inputJson(),shell.workspace());
    }

    private WorkspaceBinding workspace(String character) {
        return new WorkspaceBinding(character.repeat(64), "7", "session-1", 1, "chat-1",
                new WorkspacePolicy(1, 300, 24, 10));
    }

    private ExecuteRequest shell(boolean program, List<DependencySpec> dependencies,
            WorkspaceBinding workspace, ExecutionProfile profile) {
        return new ExecuteRequest("shell-task", Language.SHELL, "grep -n TODO .", dependencies, List.of(), null,
                program, deadline, TestSandboxProperties.policy(), List.of(), profile, null, null, workspace);
    }
    @Test void rejectsTraversalDuplicateAndTransferOverflow() {
        for(var files:List.of(List.of(new ArtifactFile("data/../escape",1,"a".repeat(64))),
                List.of(new ArtifactFile("data/input",1,"a".repeat(64)),new ArtifactFile("data/input",1,"a".repeat(64))),
                List.of(new ArtifactFile("data/input",ExecutionLimits.TRANSFER_BYTES+1,"a".repeat(64)))))
            assertThrows(SandboxRequestException.class,()->policy.validate(inline(false,List.of()),files));
        assertThrows(SandboxRequestException.class,()->policy.validate(inline(false,List.of("../result"))));
        for(String reserved:List.of("result.json","result.json/","result.json/child.txt"))
            assertEquals("INVALID_PUBLIC_RESULT_FILE",assertThrows(SandboxRequestException.class,
                    ()->policy.validate(inline(false,List.of(reserved)))).code());
        assertEquals(List.of("nested/result.json"),policy.validate(inline(false,List.of("nested/result.json"))).outputFiles());
    }
    @Test void acceptsLargeManifestWithinCapacityAndRejectsExcessFileCount() {
        var files=java.util.stream.IntStream.range(0,ExecutionLimits.MAX_FILES)
                .mapToObj(i->new ArtifactFile("data/f"+i,1,"a".repeat(64))).toList();
        assertEquals(ExecutionLimits.MAX_FILES,policy.validate(inline(false,List.of()),files).artifactInputs().size());
        var tooMany=new java.util.ArrayList<>(files);tooMany.add(new ArtifactFile("data/extra",1,"a".repeat(64)));
        assertThrows(SandboxRequestException.class,()->policy.validate(inline(false,List.of()),tooMany));
    }
    @Test void preservesExactDependencyAndProgramRestrictions() {
        var ranged=new ExecuteRequest("task",Language.NODE,"",List.of(new DependencySpec("kleur","^4")),
                List.of(),null,false,deadline,TestSandboxProperties.policy(),List.of(),ExecutionProfile.STANDARD,null,null);
        assertEquals("INVALID_DEPENDENCY_VERSION",assertThrows(SandboxRequestException.class,()->policy.validate(ranged)).code());
        var python=new ExecuteRequest("task",Language.PYTHON,"pass",List.of(),List.of(),null,true,deadline,
                TestSandboxProperties.policy(),List.of(),ExecutionProfile.STANDARD,null,null);
        assertEquals("INVALID_PROGRAM_TOOLS_MODE",assertThrows(SandboxRequestException.class,()->policy.validate(python)).code());
    }
    @Test void workflowRequiresFrozenScriptManifestAndCanonicalObject() {
        var script=new ArtifactFile("scripts/main.py",10,"a".repeat(64));
        var first=workflow(script,"{\"z\":1,\"a\":2}");var second=workflow(script,"{\"a\":2,\"z\":1}");
        assertEquals(policy.validate(first,List.of(script)).digest(),policy.validate(second,List.of(script)).digest());
        assertThrows(SandboxRequestException.class,()->policy.validate(first,List.of()));
        for(String bad:List.of("[]","{\"a\":1,\"a\":2}","{} trailing"))
            assertThrows(SandboxRequestException.class,()->policy.validate(workflow(script,bad),List.of(script)));
    }
    private ExecuteRequest workflow(ArtifactFile script,String input) {
        return new ExecuteRequest("task",Language.PYTHON,"",List.of(),List.of(script),script.path(),false,deadline,
                TestSandboxProperties.policy(),List.of(),ExecutionProfile.WORKFLOW_SCRIPT_V1,"workflow-python-v1",input);
    }
    @Test void executionTimeoutSupportsHourButNotUnboundedRun() {
        var p=TestSandboxProperties.policy();
        assertDoesNotThrow(()->new ExecutionPolicy(p.revision(),ExecutionLimits.MAX_TIMEOUT_SECONDS,p.dockerControlTimeoutSeconds(),
                p.workspaceRetentionHours(),p.workspaceCleanupIntervalMinutes(),p.outputCaptureBytes(),p.maxArtifactFiles(),
                p.maxConcurrentExecutions(),p.memoryLimit(),p.cpuLimit(),p.pidsLimit()));
        assertThrows(IllegalArgumentException.class,()->new ExecutionPolicy(p.revision(),ExecutionLimits.MAX_TIMEOUT_SECONDS+1,
                p.dockerControlTimeoutSeconds(),p.workspaceRetentionHours(),p.workspaceCleanupIntervalMinutes(),p.outputCaptureBytes(),
                p.maxArtifactFiles(),p.maxConcurrentExecutions(),p.memoryLimit(),p.cpuLimit(),p.pidsLimit()));
    }

    @Test void frozenSkillResourcesAllowPackagePathsButNotUndeclaredOrMismatchedFiles() {
        var script=new ArtifactFile("scripts/main.py",10,"a".repeat(64));
        var example=new ArtifactFile("examples/document.json",2,"b".repeat(64));
        var files=List.of(script,example);
        var request=new ExecuteRequest("skill",Language.PYTHON,"",List.of(),files,script.path(),false,
                deadline,TestSandboxProperties.policy(),List.of(),ExecutionProfile.STANDARD,null,null);
        assertEquals(files.size(),policy.validate(request,files).inputs().size());
        assertThrows(SandboxRequestException.class,()->policy.validate(inline(false,List.of()),List.of(example)));
        assertThrows(SandboxRequestException.class,()->policy.validate(request,List.of(script,
                new ArtifactFile(example.path(),example.size(),"c".repeat(64)))));
        var extra=new java.util.ArrayList<>(files);
        extra.add(new ArtifactFile("references/undeclared.md",1,"d".repeat(64)));
        assertThrows(SandboxRequestException.class,()->policy.validate(request,extra));
        var workflow=new ExecuteRequest("skill",Language.PYTHON,"",List.of(),files,script.path(),false,
                deadline,TestSandboxProperties.policy(),List.of(),ExecutionProfile.WORKFLOW_SCRIPT_V1,
                "workflow-python-v1","{}");
        assertEquals("INVALID_WORKFLOW_RESOURCE",assertThrows(SandboxRequestException.class,
                ()->policy.validate(workflow,files)).code());
    }
}
