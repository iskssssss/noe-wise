package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.DependencySpec;
import com.skillforge.execution.ExecuteRequest;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.execution.WorkspaceBinding;
import com.skillforge.execution.WorkspacePolicy;
import com.skillforge.sandbox.config.SandboxProperties;
import com.skillforge.sandbox.persistence.TaskRecord;
import com.skillforge.sandbox.workspace.WorkspaceCloudProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerExecutionRunnerTest {

    @TempDir
    Path temporary;

    @Test
    void emptyDependenciesKeepSealedPerTaskMaterialsAndOnlyOneExecutionContainer() throws Exception {
        for (var language : List.of(Language.PYTHON, Language.NODE)) verifyExecutionStages(language, List.of());
    }

    @Test
    void declaredDependenciesStillInstallBeforeSealingAndExecution() throws Exception {
        verifyExecutionStages(Language.PYTHON, List.of(new DependencySpec("anyio", "4.8.0")));
        verifyExecutionStages(Language.NODE, List.of(new DependencySpec("kleur", "4.1.5")));
    }

    /** 真实临时材料与 seal，受控 Docker 仅代替容器进程；不能从省去阶段推断耗时改善。 */
    private void verifyExecutionStages(Language language, List<DependencySpec> dependencies) throws Exception {
        var properties = TestSandboxProperties.create(temporary.resolve(language.name() + dependencies.size()));
        var workspace = new TaskWorkspace(properties);
        String taskId = "execution-stages";
        String image = language == Language.PYTHON ? properties.pythonImage() : properties.nodeImage();
        String imageId = "sha256:" + "1".repeat(64);
        Instant now = Instant.now();
        var request = new RequestPolicy(new ObjectMapper()).validate(new ExecuteRequest(taskId, language,
                language == Language.PYTHON ? "assert True" : "if(false)throw Error()", dependencies,
                List.of(), null, false, now.plusSeconds(60), TestSandboxProperties.policy(), List.of(),
                ExecutionProfile.STANDARD, null, null), List.of(), image, imageId);
        workspace.prepare(request);
        Path root = properties.dataRoot().resolve("workspaces").resolve(taskId);
        assertTrue(java.nio.file.Files.isDirectory(root.resolve("work/node_modules")),
                "STANDARD prepare 已建立本次目录，空安装须与 mkdir -p 一样接纳它");
        var task = new TaskRecord(taskId, request.digest(), language, false, dependencies, now.plusSeconds(60),
                ExecutionState.QUEUED, "queued", null, null, "", "", false, false, null, null, List.of(),
                null, now, now, TestSandboxProperties.policy(), ExecutionProfile.STANDARD, null, image, imageId, null);
        var docker = org.mockito.Mockito.mock(DockerCommandExecutor.class);
        var created = new ArrayList<List<String>>();
        var phases = new ArrayList<String>();
        org.mockito.Mockito.when(docker.run(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenAnswer(invocation -> {
                    List<String> command = invocation.getArgument(0);
                    String stdout = "";
                    if (command.getFirst().equals("create")) {
                        created.add(List.copyOf(command));
                        if (command.contains("install")) {
                            java.nio.file.Files.writeString(root.resolve("evidence/dependencies.json"), "{\"installed\":true}\n");
                        } else {
                            assertTrue(java.nio.file.Files.isRegularFile(root.resolve("evidence/dependencies.sha256")),
                                    "任何执行容器创建前都必须已固化本次依赖证据");
                        }
                        stdout = "a".repeat(64);
                    } else if (command.getFirst().equals("inspect")) {
                        stdout = command.contains("{{.State.Running}} {{.State.ExitCode}}") ? "false 0"
                                : "a".repeat(64) + " " + owner(properties.taskVolume());
                    } else if (!command.getFirst().equals("rm")) throw new AssertionError(command);
                    return new DockerCommandExecutor.CommandResult(0, stdout, "", false);
                });
        org.mockito.Mockito.when(docker.start(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation -> {
            var process = org.mockito.Mockito.mock(Process.class);
            org.mockito.Mockito.when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            org.mockito.Mockito.when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
            org.mockito.Mockito.when(process.waitFor(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(TimeUnit.class))).thenReturn(true);
            return process;
        });
        var runner = new DockerExecutionRunner(properties, docker, workspace, new ObjectMapper(), workspaceProperties(properties));
        var result = runner.execute(task, request, ignored -> { }, phases::add, () -> false);
        assertEquals(ExecutionState.COMPLETED, result.state());
        assertEquals(0, result.exitCode());
        assertEquals(List.of("dependency_install", "execution"), phases);
        assertEquals(dependencies.isEmpty() ? 1 : 2, created.size());
        assertEquals(!dependencies.isEmpty(), created.stream().anyMatch(command -> command.contains("install")));
        assertTrue(created.getLast().contains("execute"));
        var report = root.resolve("evidence/dependencies.json");
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(report))),
                java.nio.file.Files.readString(root.resolve("evidence/dependencies.sha256")).trim());
        if (dependencies.isEmpty()) {
            assertTrue(java.nio.file.Files.isDirectory(root.resolve("work/home")));
            if (language == Language.PYTHON) {
                assertEquals("{\"install\":[],\"version\":\"1\"}\n", java.nio.file.Files.readString(report));
                assertTrue(java.nio.file.Files.isDirectory(root.resolve("work/python")));
            } else {
                assertEquals("{\"lockfileVersion\":3,\"packages\":{}}\n", java.nio.file.Files.readString(report));
                assertEquals("{\"private\":true,\"type\":\"module\"}\n", java.nio.file.Files.readString(root.resolve("work/package.json")));
                assertTrue(java.nio.file.Files.isDirectory(root.resolve("work/node_modules")));
            }
            assertThrows(IllegalStateException.class, () -> workspace.prepareEmptyDependencies(request), "不覆盖或复用已有材料");
        } else {
            assertThrows(IllegalArgumentException.class, () -> workspace.prepareEmptyDependencies(request));
        }
    }

    @Test
    void createCommandPinsIsolationAndExposesOnlyPublicCa() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskWorkspace workspace = new TaskWorkspace(properties);
        DockerExecutionRunner runner = new DockerExecutionRunner(properties,
                new DockerCommandExecutor(properties), workspace, new ObjectMapper(), workspaceProperties(properties));
        Instant now = Instant.now();
        String imageId = "sha256:" + "1".repeat(64);
        TaskRecord task = new TaskRecord("node-task", "digest", Language.NODE, true,
                List.of(new DependencySpec("kleur", "4.1.5")), now.plusSeconds(60),
                ExecutionState.QUEUED, "queued", null, null, "", "", false, false,
                null, null, List.of(), null, now, now, TestSandboxProperties.policy(),
                ExecutionProfile.STANDARD, null, properties.nodeImage(), imageId, null);
        RequestPolicy.ValidatedRequest request = new RequestPolicy(new ObjectMapper()).validate(
                new ExecuteRequest("node-task", Language.NODE, "console.log('ok')",
                        task.dependencies(), List.of(), null, true, now.plusSeconds(60),
                        TestSandboxProperties.policy(), List.of(),
                        ExecutionProfile.STANDARD, null, null), List.of(), properties.nodeImage(), imageId);

        List<String> command = runner.createCommand(task, request, "container-name",
                DockerExecutionRunner.Stage.EXECUTE);
        String rendered = String.join(" ", command);

        assertTrue(rendered.contains("--network skillforge-sandbox-task"));
        assertTrue(rendered.contains("--label skillforge.sandbox.owner=" + owner(properties.taskVolume())));
        assertTrue(rendered.contains("--read-only"));
        assertTrue(rendered.contains("--cap-drop ALL"));
        assertTrue(rendered.contains("no-new-privileges"));
        assertTrue(rendered.contains("dst=/etc/skillforge/ca,volume-subpath=public,readonly"));
        assertTrue(rendered.contains("dst=/input,volume-subpath=workspaces/node-task/input,readonly"));
        assertTrue(rendered.contains("dst=/input/node_modules,volume-subpath=workspaces/node-task/work/node_modules"));
        assertTrue(rendered.contains("dst=/workspace/program,volume-subpath=workspaces/node-task/program"));
        assertFalse(rendered.contains("dst=/workspace/program,volume-subpath=workspaces/node-task/program,readonly"));
        assertTrue(rendered.contains("sha256:" + "1".repeat(64)));
        assertFalse(rendered.contains("docker.sock"));
        assertFalse(rendered.contains("service-token"));

        String install = String.join(" ", runner.createCommand(task, request, "install-container",
                DockerExecutionRunner.Stage.INSTALL));
        assertFalse(install.contains("/workspace/program"));

        TaskRecord disabledTask = new TaskRecord("node-disabled", "digest", Language.NODE, false,
                task.dependencies(), now.plusSeconds(60), ExecutionState.QUEUED, "queued", null, null,
                "", "", false, false, null, null, List.of(), null, now, now,
                TestSandboxProperties.policy(), ExecutionProfile.STANDARD, null, properties.nodeImage(), imageId,
                null);
        RequestPolicy.ValidatedRequest disabledRequest = new RequestPolicy(new ObjectMapper()).validate(
                new ExecuteRequest("node-disabled", Language.NODE, "console.log('ok')",
                        disabledTask.dependencies(), List.of(), null, false, now.plusSeconds(60),
                        TestSandboxProperties.policy(), List.of(),
                        ExecutionProfile.STANDARD, null, null), List.of(), properties.nodeImage(), imageId);
        String disabledExecute = String.join(" ", runner.createCommand(disabledTask, disabledRequest,
                "disabled-container", DockerExecutionRunner.Stage.EXECUTE));
        assertFalse(disabledExecute.contains("/workspace/program"));
    }

    /** Shell 使用只读 main.sh 和单个项目子目录，无安装阶段和其他项目目录挂载。 */
    @Test
    void shellExecutesFromProjectWithOneStageAndFrozenNodeImage() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskWorkspace workspace = new TaskWorkspace(properties);
        DockerExecutionRunner runner = new DockerExecutionRunner(properties,
                new DockerCommandExecutor(properties), workspace, new ObjectMapper(), workspaceProperties(properties));
        String imageId = "sha256:" + "4".repeat(64);
        setImageId(runner, "nodeImageId", imageId);
        Instant now = Instant.now();
        WorkspaceBinding binding = workspaceBinding();
        ExecuteRequest raw = new ExecuteRequest("shell-task", Language.SHELL, "printf ready > result.txt\n",
                List.of(), List.of(), null, false, now.plusSeconds(60), TestSandboxProperties.policy(), List.of(), ExecutionProfile.WORKSPACE_SHELL_V1, null, null, binding);
        RequestPolicy.ValidatedRequest request = new RequestPolicy(new ObjectMapper()).validate(raw, List.of(),
                properties.nodeImage(), imageId);
        workspace.prepare(request);
        TaskRecord task = workspaceTask(raw, request, now);

        String rendered = String.join(" ", runner.createCommand(task, request, "shell-container",
                DockerExecutionRunner.Stage.EXECUTE));

        assertTrue(rendered.contains("--workdir /workspace/project"));
        assertTrue(rendered.contains("src=test-project-volume,dst=/workspace/project,volume-subpath="
                + binding.workspaceId() + "/project"));
        assertTrue(rendered.endsWith(imageId + " shell-execute /input/main.sh /workspace/project"));
        assertFalse(rendered.contains("/workspace/evidence"));
        assertFalse(rendered.contains("node_modules"));
        assertEquals("printf ready > result.txt\n", java.nio.file.Files.readString(
                properties.dataRoot().resolve("workspaces/shell-task/input/main.sh")));
        assertTrue(java.nio.file.Files.isDirectory(properties.dataRoot().resolve("workspaces/shell-task/work/home")));
        assertEquals(imageId, runner.resolveEnvironment(ExecutionProfile.WORKSPACE_SHELL_V1, Language.SHELL, null).imageId());
        assertThrows(IllegalStateException.class, () -> runner.createCommand(task, request, "shell-install",
                DockerExecutionRunner.Stage.INSTALL));
    }

    /** 冻结 Skill 资源复用真实 /input 只读卷，命令只对已授权的项目目录拥有写入挂载。 */
    @Test
    void shellSkillResourcesKeepReadOnlyInputMountAndWritableProjectSeparate() {
        var properties=TestSandboxProperties.create(temporary);
        var workspace=new TaskWorkspace(properties);
        var runner=new DockerExecutionRunner(properties,new DockerCommandExecutor(properties),workspace,
                new ObjectMapper(),workspaceProperties(properties));
        String imageId="sha256:"+"4".repeat(64);
        Instant now=Instant.now();
        var files=List.of(new ArtifactFile("scripts/check.sh",10,"a".repeat(64)),
                new ArtifactFile("assets/template.bin",3,"b".repeat(64)));
        var raw=new ExecuteRequest("shell-skill",Language.SHELL,"export SKILL_DIR=/input\nbash \"$SKILL_DIR/scripts/check.sh\"",
                List.of(),files,null,false,now.plusSeconds(60),TestSandboxProperties.policy(),List.of(),
                ExecutionProfile.WORKSPACE_SHELL_V1,null,null,workspaceBinding());
        var validated=new RequestPolicy(new ObjectMapper()).validate(raw,files,properties.nodeImage(),imageId);
        var task=workspaceTask(raw,validated,now);
        String command=String.join(" ",runner.createCommand(task,validated,"shell-skill-container",DockerExecutionRunner.Stage.EXECUTE));
        assertTrue(command.contains("dst=/input,volume-subpath=workspaces/shell-skill/input,readonly"));
        assertTrue(command.contains("dst=/workspace/project,volume-subpath="+workspaceBinding().workspaceId()+"/project"));
        assertTrue(command.endsWith(imageId+" shell-execute /input/main.sh /workspace/project"));
        assertFalse(command.contains("node_modules"));
        assertThrows(IllegalStateException.class,()->runner.createCommand(task,validated,"shell-install",DockerExecutionRunner.Stage.INSTALL));
    }

    /** 普通代码仅执行阶段获得授权项目目录，安装阶段不接触持久项目文件。 */
    @Test
    void standardProjectMountIsExcludedFromInstallationForBothLanguages() {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        DockerExecutionRunner runner = new DockerExecutionRunner(properties,
                new DockerCommandExecutor(properties), new TaskWorkspace(properties), new ObjectMapper(), workspaceProperties(properties));
        Instant now = Instant.now();
        for (Language language : List.of(Language.PYTHON, Language.NODE)) {
            String image = language == Language.PYTHON ? properties.pythonImage() : properties.nodeImage();
            String imageId = "sha256:" + "5".repeat(64);
            ExecuteRequest raw = new ExecuteRequest("project-" + language.value(), language, "print('ready')",
                    List.of(), List.of(), null, false, now.plusSeconds(60), TestSandboxProperties.policy(), List.of(), ExecutionProfile.STANDARD, null, null, workspaceBinding());
            RequestPolicy.ValidatedRequest request = new RequestPolicy(new ObjectMapper()).validate(raw, List.of(), image, imageId);
            TaskRecord task = workspaceTask(raw, request, now);
            String installed = String.join(" ", runner.createCommand(task, request, "installation", DockerExecutionRunner.Stage.INSTALL));
            String executed = String.join(" ", runner.createCommand(task, request, "execution", DockerExecutionRunner.Stage.EXECUTE));
            assertFalse(installed.contains("test-project-volume"));
            assertFalse(installed.contains("/workspace/project"));
            assertTrue(executed.contains("test-project-volume"));
            assertTrue(executed.endsWith("execute " + request.entryPath() + " /workspace/project"));
        }
    }

    @Test
    void workflowCommandHasNoNetworkCredentialsOrWritablePersistentMounts() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskWorkspace workspace = new TaskWorkspace(properties);
        DockerExecutionRunner runner = new DockerExecutionRunner(properties,
                new DockerCommandExecutor(properties), workspace, new ObjectMapper(), workspaceProperties(properties));
        Instant now = Instant.now();
        String imageId = "sha256:" + "2".repeat(64);
        String script = "import json,sys; print(json.dumps(json.load(sys.stdin)))";
        var input = inputFile("scripts/main.py", script);
        ExecuteRequest raw = new ExecuteRequest("workflow-task", Language.PYTHON, "", List.of(), List.of(input),
                "scripts/main.py", false, now.plusSeconds(60), TestSandboxProperties.policy(), List.of(), ExecutionProfile.WORKFLOW_SCRIPT_V1,
                properties.workflowPythonEnvironmentVersion(), "{\"name\":\"demo\"}");
        RequestPolicy.ValidatedRequest request = new RequestPolicy(new ObjectMapper()).validate(raw, List.of(input),
                properties.workflowPythonImage(), imageId);
        TaskRecord task = new TaskRecord(raw.taskId(), request.digest(), raw.language(), false, List.of(),
                raw.deadlineAt(), ExecutionState.QUEUED, "queued", null, null, "", "", false, false,
                null, null, List.of(), null, now, now, raw.executionPolicy(), raw.profile(),
                raw.environmentVersion(), properties.workflowPythonImage(), imageId, null);

        String rendered = String.join(" ", runner.createCommand(task, request, "workflow-container",
                DockerExecutionRunner.Stage.EXECUTE));

        assertTrue(rendered.contains("--network none"));
        assertTrue(rendered.contains("--label skillforge.sandbox.owner=" + owner(properties.taskVolume())));
        assertTrue(rendered.contains("HOME=/tmp"));
        assertTrue(rendered.contains("dst=/input,volume-subpath=workspaces/workflow-task/input,readonly"));
        assertTrue(rendered.endsWith(" workflow-execute /input/scripts/main.py"));
        assertFalse(rendered.contains(properties.proxyUrl()));
        assertFalse(rendered.contains(properties.caVolume()));
        assertFalse(rendered.contains("/workspace/work"));
        assertFalse(rendered.contains("/workspace/output"));
        assertFalse(rendered.contains("/workspace/program"));
        assertFalse(rendered.contains("node_modules"));
        assertThrows(IllegalStateException.class, () -> runner.createCommand(task, request,
                "workflow-install", DockerExecutionRunner.Stage.INSTALL));
    }

    @Test
    void workflowOutputRequiresOneStrictUtf8JsonObjectWithinEightMiB() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        DockerExecutionRunner runner = new DockerExecutionRunner(properties,
                new DockerCommandExecutor(properties), new TaskWorkspace(properties), new ObjectMapper(), workspaceProperties(properties));
        BoundedCapture valid = capture("{\"ok\":true}", com.skillforge.execution.ExecutionLimits.WORKFLOW_RESULT_BYTES);
        BoundedCapture stderr = capture("", 4096);

        DockerExecutionRunner.RunnerResult completed = runner.workflowResult(0, valid, stderr);

        assertEquals(ExecutionState.COMPLETED, completed.state());
        assertNotNull(completed.jsonResult());
        assertTrue(completed.jsonResult().get("ok").booleanValue());

        for (String invalid : List.of("[]", "{\"ok\":true} trailing", "{\"ok\":true,\"ok\":false}")) {
            DockerExecutionRunner.RunnerResult failed = runner.workflowResult(0,
                    capture(invalid, com.skillforge.execution.ExecutionLimits.WORKFLOW_RESULT_BYTES), stderr);
            assertEquals(ExecutionState.FAILED, failed.state());
            assertEquals("WORKFLOW_JSON_OUTPUT_INVALID", failed.error());
            assertNull(failed.jsonResult());
        }

        BoundedCapture oversized = capture("x".repeat(com.skillforge.execution.ExecutionLimits.WORKFLOW_RESULT_BYTES + 1), com.skillforge.execution.ExecutionLimits.WORKFLOW_RESULT_BYTES);
        DockerExecutionRunner.RunnerResult tooLarge = runner.workflowResult(0, oversized, stderr);
        assertEquals(ExecutionState.FAILED, tooLarge.state());
        assertEquals("WORKFLOW_JSON_OUTPUT_TOO_LARGE", tooLarge.error());

        BoundedCapture invalidUtf8 = new BoundedCapture(com.skillforge.execution.ExecutionLimits.WORKFLOW_RESULT_BYTES);
        invalidUtf8.drain(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}));
        assertEquals("WORKFLOW_JSON_OUTPUT_INVALID", runner.workflowResult(0, invalidUtf8, stderr).error());
    }

    @Test
    void workflowEnvironmentVersionSelectsConfiguredFrozenImage() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        DockerExecutionRunner runner = new DockerExecutionRunner(properties,
                new DockerCommandExecutor(properties), new TaskWorkspace(properties), new ObjectMapper(), workspaceProperties(properties));
        String imageId = "sha256:" + "3".repeat(64);
        setImageId(runner, "workflowPythonImageId", imageId);

        DockerExecutionRunner.ExecutionEnvironment environment = runner.resolveEnvironment(
                ExecutionProfile.WORKFLOW_SCRIPT_V1, Language.PYTHON,
                properties.workflowPythonEnvironmentVersion());

        assertEquals(properties.workflowPythonImage(), environment.imageReference());
        assertEquals(imageId, environment.imageId());
        assertEquals("UNSUPPORTED_ENVIRONMENT_VERSION", assertThrows(
                com.skillforge.sandbox.api.SandboxRequestException.class,
                () -> runner.resolveEnvironment(ExecutionProfile.WORKFLOW_SCRIPT_V1, Language.PYTHON,
                        "different-version")).code());
    }

    @Test
    void startupCleanupLeavesOtherVolumesAndUnownedContainersUntouched() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        ControlledDocker docker = new ControlledDocker(properties);
        docker.containers.put("own-container", owner(properties.taskVolume()));
        docker.containers.put("other-container", owner("other-task-volume"));
        docker.containers.put("unowned-container", "");
        DockerExecutionRunner runner = runner(properties, docker);

        runner.cleanupOwnedContainers();

        assertEquals(List.of("own-container"), docker.removed);
        assertTrue(docker.containers.containsKey("other-container"));
        assertTrue(docker.containers.containsKey("unowned-container"));
        assertEquals(List.of(docker.id("own-container")), docker.removedIds);
        List<String> enumeration = docker.commands.stream().filter(command -> command.getFirst().equals("ps"))
                .findFirst().orElseThrow();
        assertTrue(enumeration.contains("label=skillforge.sandbox.managed=true"));
        assertTrue(enumeration.contains("label=skillforge.sandbox.owner=" + owner(properties.taskVolume())));
    }

    @Test
    void fallbackCancellationNamesAreStablePerVolumeAndSeparateIdenticalTaskIds() throws Exception {
        SandboxProperties first = TestSandboxProperties.create(temporary.resolve("first"));
        SandboxProperties other = withTaskVolume(first, "other-task-volume", temporary.resolve("other"));
        ControlledDocker firstDocker = new ControlledDocker(first);
        ControlledDocker restartDocker = new ControlledDocker(first);
        ControlledDocker otherDocker = new ControlledDocker(other);
        TaskRecord task = cancellableTask(first, null);

        assertTrue(runner(first, firstDocker).cancel(task));
        assertTrue(runner(first, restartDocker).cancel(task));
        assertTrue(runner(other, otherDocker).cancel(task));

        List<String> firstNames = firstDocker.containerLookups();
        assertEquals(2, firstNames.size());
        assertEquals(firstNames, restartDocker.containerLookups());
        assertFalse(firstNames.stream().anyMatch(otherDocker.containerLookups()::contains));
        final String firstOwner = owner(first.taskVolume());
        final String otherOwner = owner(other.taskVolume());
        assertTrue(firstNames.stream().allMatch(name -> name.contains(firstOwner)));
        assertTrue(otherDocker.containerLookups().stream().allMatch(name -> name.contains(otherOwner)));
    }

    @Test
    void persistedContainerIdMustMatchVolumeOwnerBeforeCancellation() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        ControlledDocker docker = new ControlledDocker(properties);
        docker.containers.put("foreign-container", owner("other-task-volume"));
        docker.containers.put("unowned-container", "");
        docker.containers.put("own-container", owner(properties.taskVolume()));
        DockerExecutionRunner runner = runner(properties, docker);

        assertFalse(runner.cancel(cancellableTask(properties, "foreign-container")));
        assertFalse(runner.cancel(cancellableTask(properties, "unowned-container")));
        assertTrue(runner.cancel(cancellableTask(properties, "own-container")));

        assertEquals(List.of("own-container"), docker.removed);
        assertTrue(docker.containers.containsKey("foreign-container"));
        assertTrue(docker.containers.containsKey("unowned-container"));
    }

    @Test
    void failedOwnerInspectionDoesNotRemoveContainerOrClaimCancellation() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        ControlledDocker docker = new ControlledDocker(properties);
        docker.containers.put("own-container", owner(properties.taskVolume()));
        docker.ownerInspectionUnavailable = true;

        assertFalse(runner(properties, docker).cancel(cancellableTask(properties, "own-container")));
        assertTrue(docker.removed.isEmpty());
        assertTrue(docker.containers.containsKey("own-container"));
    }

    @Test
    void removalUsesInspectedImmutableIdWhenContainerNameIsReplaced() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        ControlledDocker docker = new ControlledDocker(properties);
        docker.containers.put("own-name", owner(properties.taskVolume()));
        String originalId = docker.id("own-name");
        docker.replaceNameAfterInspection = "own-name";

        assertTrue(runner(properties, docker).cancel(cancellableTask(properties, "own-name")));

        assertEquals(List.of(originalId), docker.removedIds);
        assertEquals("replacement-owner", docker.containers.get("own-name"));
        assertFalse(docker.containers.containsKey(originalId));
    }

    @Test
    void missingContainerIsQuiescentButMalformedIdentityAndRemovalTimeoutAreUnconfirmed() throws Exception {
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        ControlledDocker docker = new ControlledDocker(properties);
        DockerExecutionRunner runner = runner(properties, docker);
        assertTrue(runner.cancel(cancellableTask(properties, "absent-container")));
        assertTrue(docker.removedIds.isEmpty());

        docker.containers.put("own-container", owner(properties.taskVolume()));
        docker.malformedIdentity = true;
        assertFalse(runner.cancel(cancellableTask(properties, "own-container")));
        assertTrue(docker.removedIds.isEmpty());

        docker.malformedIdentity = false;
        docker.removalUnavailable = true;
        assertFalse(runner.cancel(cancellableTask(properties, "own-container")));
        assertTrue(docker.containers.containsKey("own-container"));
    }

    private DockerExecutionRunner runner(SandboxProperties properties, ControlledDocker docker) {
        return new DockerExecutionRunner(properties, docker, new TaskWorkspace(properties), new ObjectMapper(), workspaceProperties(properties));
    }

    /** 项目卷与云端配置通过正式 Runner 构造入口注入，测试不需要真实云端凭据。 */
    private WorkspaceCloudProperties workspaceProperties(SandboxProperties properties) {
        return new WorkspaceCloudProperties(properties.dataRoot().resolve("projects"), "test-project-volume",
                URI.create("https://v0.api.upyun.com"), "", "", "", 1);
    }

    /** 使用可信身份和合法冻结策略构造工作区关联。 */
    private WorkspaceBinding workspaceBinding() {
        return new WorkspaceBinding("a".repeat(64), "tenant", "runtime-session", 1,
                "product-session", new WorkspacePolicy(1, 300, 24, 10));
    }

    /** 任务环境身份取校验后的请求，与 Runner 的冻结环境检查保持一致。 */
    private TaskRecord workspaceTask(ExecuteRequest raw, RequestPolicy.ValidatedRequest request, Instant now) {
        return new TaskRecord(raw.taskId(), request.digest(), raw.language(), false, List.of(), raw.deadlineAt(),
                ExecutionState.QUEUED, "queued", null, null, "", "", false, false, null, null,
                List.of(), null, now, now, raw.executionPolicy(), raw.profile(), null,
                request.imageReference(), request.imageId(), null, request.workspace());
    }

    private TaskRecord cancellableTask(SandboxProperties properties, String containerId) {
        Instant now = Instant.now();
        return new TaskRecord("same-task", "digest", Language.NODE, false, List.of(), now.plusSeconds(60),
                ExecutionState.RUNNING, "execution", containerId, null, "", "", false, false, null, null,
                List.of(), null, now, now, TestSandboxProperties.policy(), ExecutionProfile.STANDARD, null,
                properties.nodeImage(), "sha256:" + "1".repeat(64), null);
    }

    private SandboxProperties withTaskVolume(SandboxProperties source, String volume, Path dataRoot) {
        return new SandboxProperties(source.serviceToken(), dataRoot, source.dockerBinary(), source.taskNetwork(),
                volume, source.caVolume(), source.proxyUrl(), source.proxyContainer(), source.pythonImage(),
                source.nodeImage(), source.pythonIndexUrl(), source.nodeRegistryUrl(), source.dockerControlTimeout(),
                source.workflowPythonEnvironmentVersion(), source.workflowNodeEnvironmentVersion(),
                source.workflowPythonImage(), source.workflowNodeImage());
    }

    private String owner(String volume) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(volume.getBytes(StandardCharsets.UTF_8)));
    }

    /** 只解释已声明的 Docker 控制命令，不连接宿主 Docker 或启动子进程。 */
    private static final class ControlledDocker extends DockerCommandExecutor {
        private final Map<String, String> containers = new LinkedHashMap<>();
        private final List<List<String>> commands = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private final List<String> removedIds = new ArrayList<>();
        private final Map<String, String> identities = new LinkedHashMap<>();
        private boolean ownerInspectionUnavailable;
        private boolean malformedIdentity;
        private boolean removalUnavailable;
        private String replaceNameAfterInspection;

        private ControlledDocker(SandboxProperties properties) {
            super(properties);
        }

        @Override
        public CommandResult run(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            String action = arguments.getFirst();
            if (action.equals("network")) return success("true isolated false");
            if (action.equals("volume")) return success("[]");
            if (action.equals("image")) return success("sha256:" + "1".repeat(64));
            if (action.equals("ps")) {
                String requestedOwner = arguments.stream().filter(value -> value.startsWith("label=skillforge.sandbox.owner="))
                        .map(value -> value.substring("label=skillforge.sandbox.owner=".length())).findFirst().orElse(null);
                return success(String.join("\n", containers.entrySet().stream()
                        .filter(entry -> requestedOwner == null || requestedOwner.equals(entry.getValue()))
                        .map(Map.Entry::getKey).toList()));
            }
            if (action.equals("inspect")) {
                if (arguments.contains("{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}")) {
                    return success("true healthy");
                }
                if (ownerInspectionUnavailable) return new CommandResult(-1, "", "Docker control unavailable", true);
                String container = resolve(arguments.getLast());
                if (container == null) return new CommandResult(1, "", "No such object: " + arguments.getLast(), false);
                if (malformedIdentity) return success("invalid-id " + containers.get(container));
                String containerId = id(container);
                String inspectedOwner = containers.get(container);
                if (container.equals(replaceNameAfterInspection)) {
                    containers.put(containerId, inspectedOwner);
                    identities.put(containerId, containerId);
                    containers.put(container, "replacement-owner");
                    identities.put(container, id("replacement:" + container));
                    replaceNameAfterInspection = null;
                }
                return success(containerId + " " + inspectedOwner);
            }
            if (action.equals("rm")) {
                removedIds.add(arguments.getLast());
                if (removalUnavailable) return new CommandResult(-1, "", "No such container during timeout", true);
                String container = resolve(arguments.getLast());
                if (container == null) return new CommandResult(1, "", "No such container: " + arguments.getLast(), false);
                removed.add(container);
                containers.remove(container);
                return success("");
            }
            throw new AssertionError("Unexpected Docker command: " + arguments);
        }

        private List<String> containerLookups() {
            return commands.stream().filter(command -> command.getFirst().equals("inspect"))
                    .map(List::getLast).toList();
        }

        private String resolve(String target) {
            if (containers.containsKey(target)) return target;
            return containers.keySet().stream().filter(name -> id(name).equals(target)).findFirst().orElse(null);
        }

        private String id(String name) {
            if (identities.containsKey(name)) return identities.get(name);
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(name.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private CommandResult success(String stdout) {
            return new CommandResult(0, stdout, "", false);
        }
    }

    private BoundedCapture capture(String value, int limit) throws Exception {
        BoundedCapture capture = new BoundedCapture(limit);
        capture.drain(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
        return capture;
    }

    private ArtifactFile inputFile(String path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new ArtifactFile(path, bytes.length, digest);
    }

    private void setImageId(DockerExecutionRunner runner, String fieldName, String value) throws Exception {
        Field field = DockerExecutionRunner.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(runner, value);
    }
}
