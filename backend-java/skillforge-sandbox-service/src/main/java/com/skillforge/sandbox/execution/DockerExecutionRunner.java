package com.skillforge.sandbox.execution;

import com.skillforge.execution.ExecutionLimits;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.DependencySpec;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.sandbox.api.SandboxRequestException;
import com.skillforge.sandbox.config.SandboxProperties;
import com.skillforge.sandbox.persistence.TaskRecord;
import com.skillforge.sandbox.workspace.WorkspaceCloudProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** STANDARD 使用安装与执行两阶段；Shell 直接执行；Workflow 使用无网络容器。 */
@Component
public class DockerExecutionRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerExecutionRunner.class);
    private static final String MANAGED_LABEL = "skillforge.sandbox.managed=true";
    private static final String OWNER_LABEL_KEY = "skillforge.sandbox.owner";
    private static final int MAX_WORKFLOW_JSON_BYTES = ExecutionLimits.WORKFLOW_RESULT_BYTES;

    private final SandboxProperties properties;
    private final DockerCommandExecutor docker;
    private final TaskWorkspace workspace;
    private final WorkspaceCloudProperties projectProperties;
    private final String owner;
    private final ObjectMapper strictJson;
    private volatile String pythonImageId;
    private volatile String nodeImageId;
    private volatile String workflowPythonImageId;
    private volatile String workflowNodeImageId;

    public DockerExecutionRunner(SandboxProperties properties,
                                 DockerCommandExecutor docker,
                                 TaskWorkspace workspace,
                                 ObjectMapper objectMapper,
                                 WorkspaceCloudProperties projectProperties) {
        this.properties = properties;
        this.docker = docker;
        this.workspace = workspace;
        this.projectProperties = projectProperties;
        // 任务卷同时承载记录和工作区；重启后归属稳定，不以进程或容器名称充当身份。
        this.owner = sha256(properties.taskVolume());
        this.strictJson = objectMapper.copy()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public RunnerResult execute(TaskRecord task,
                                RequestPolicy.ValidatedRequest request,
                                Consumer<String> containerRegistered,
                                Consumer<String> phaseChanged,
                                BooleanSupplier cancelled) {
        Instant sharedDeadline = min(task.deadlineAt(), Instant.now().plusSeconds(task.executionPolicy().executionTimeoutSeconds()));
        if (!Instant.now().isBefore(sharedDeadline)) {
            return RunnerResult.timedOut("deadline_at_reached", "", "", false, false);
        }
        BoundedCapture stdout = new BoundedCapture(task.profile() == ExecutionProfile.WORKFLOW_SCRIPT_V1
                ? MAX_WORKFLOW_JSON_BYTES : task.executionPolicy().outputCaptureBytes());
        BoundedCapture stderr = new BoundedCapture(task.executionPolicy().outputCaptureBytes());

        if (task.profile() == ExecutionProfile.WORKFLOW_SCRIPT_V1) {
            phaseChanged.accept("execution");
            StageResult execution = runStage(task, request, Stage.EXECUTE, sharedDeadline,
                    stdout, stderr, containerRegistered, cancelled);
            if (execution.terminalState() != null) {
                return execution.toRunnerResult(stdout, stderr);
            }
            return workflowResult(execution.exitCode(), stdout, stderr);
        }

        if (task.profile() == ExecutionProfile.WORKSPACE_SHELL_V1) {
            // Shell 运行受控镜像已有系统命令，不产生依赖安装容器或安装证据。
            phaseChanged.accept("execution");
            StageResult execution = runStage(task, request, Stage.EXECUTE, sharedDeadline,
                    stdout, stderr, containerRegistered, cancelled);
            if (execution.terminalState() != null) {
                return execution.toRunnerResult(stdout, stderr);
            }
            return new RunnerResult(ExecutionState.COMPLETED, "completed", execution.exitCode(),
                    stdout.text(), stderr.text(), stdout.truncated(), stderr.truncated(), null, null);
        }

        phaseChanged.accept("dependency_install");
        StageResult installation = null;
        if (!task.dependencies().isEmpty()) {
            installation = runStage(task, request, Stage.INSTALL, sharedDeadline,
                    stdout, stderr, containerRegistered, cancelled);
            if (installation.terminalState() != null) {
                return installation.toRunnerResult(stdout, stderr);
            }
        }
        try {
            // 没有依赖时安装容器只写固定空清单；在本次独享目录生成相同材料后仍统一封存。
            if (task.dependencies().isEmpty()) workspace.prepareEmptyDependencies(request);
            String evidenceSha256 = workspace.sealDependencyEvidence(task.taskId());
            log.debug("依赖证据已固化 taskTag={} evidenceTag={}", taskTag(task.taskId()),
                    evidenceSha256.substring(0, 12));
        } catch (RuntimeException invalidEvidence) {
            log.warn("依赖证据固化失败 taskTag={} error={}", taskTag(task.taskId()),
                    safeLog(invalidEvidence.getMessage()));
            return RunnerResult.failed("dependency_install", installation == null ? 0 : installation.exitCode(), stdout.text(), stderr.text(),
                    stdout.truncated(), stderr.truncated(), "DEPENDENCY_EVIDENCE_INVALID");
        }

        if (cancelled.getAsBoolean()) {
            return RunnerResult.cancelled(stdout, stderr);
        }
        phaseChanged.accept("execution");
        StageResult execution = runStage(task, request, Stage.EXECUTE, sharedDeadline,
                stdout, stderr, containerRegistered, cancelled);
        if (execution.terminalState() != null) {
            return execution.toRunnerResult(stdout, stderr);
        }
        return new RunnerResult(ExecutionState.COMPLETED, "completed", execution.exitCode(),
                stdout.text(), stderr.text(), stdout.truncated(), stderr.truncated(), null, null);
    }

    public ExecutionEnvironment resolveEnvironment(ExecutionProfile profile, Language language,
                                                   String environmentVersion) {
        if (profile == null) {
            throw new SandboxRequestException("INVALID_EXECUTION_PROFILE", "profile 必须显式指定");
        }
        if (profile == ExecutionProfile.STANDARD || profile == ExecutionProfile.WORKSPACE_SHELL_V1) {
            if (environmentVersion != null) {
                throw new SandboxRequestException("INVALID_STANDARD_PROFILE",
                        "STANDARD 与工作区 Shell 不接受 environmentVersion");
            }
            if ((profile == ExecutionProfile.WORKSPACE_SHELL_V1 && language != Language.SHELL)
                    || (profile == ExecutionProfile.STANDARD && language == Language.SHELL)) {
                throw new SandboxRequestException("INVALID_EXECUTION_LANGUAGE", "执行语言与 profile 不匹配");
            }
            return language == Language.PYTHON
                    ? new ExecutionEnvironment(properties.pythonImage(), requiredImageId(pythonImageId))
                    : new ExecutionEnvironment(properties.nodeImage(), requiredImageId(nodeImageId));
        }
        String requiredVersion = language == Language.PYTHON
                ? properties.workflowPythonEnvironmentVersion() : properties.workflowNodeEnvironmentVersion();
        if (!requiredVersion.equals(environmentVersion)) {
            throw new SandboxRequestException("UNSUPPORTED_ENVIRONMENT_VERSION",
                    "environmentVersion 不匹配受信 Workflow 运行环境");
        }
        return language == Language.PYTHON
                ? new ExecutionEnvironment(properties.workflowPythonImage(), requiredImageId(workflowPythonImageId))
                : new ExecutionEnvironment(properties.workflowNodeImage(), requiredImageId(workflowNodeImageId));
    }

    public boolean cancel(TaskRecord task) {
        if (task.containerId() != null && !task.containerId().isBlank()) {
            return removeContainer(task.containerId(), Duration.ofSeconds(task.executionPolicy().dockerControlTimeoutSeconds()));
        }
        boolean installRemoved = removeContainer(containerName(task.taskId(), Stage.INSTALL), Duration.ofSeconds(task.executionPolicy().dockerControlTimeoutSeconds()));
        boolean executeRemoved = removeContainer(containerName(task.taskId(), Stage.EXECUTE), Duration.ofSeconds(task.executionPolicy().dockerControlTimeoutSeconds()));
        return installRemoved && executeRemoved;
    }

    /** 只清理当前持久任务卷所属容器；其他卷和无归属标签的容器不由本实例接管。 */
    public void cleanupOwnedContainers() {
        verifyIsolationReady();
        DockerCommandExecutor.CommandResult listed = docker.run(
                List.of("ps", "--all", "--quiet", "--filter", "label=" + MANAGED_LABEL,
                        "--filter", "label=" + OWNER_LABEL_KEY + "=" + owner),
                properties.dockerControlTimeout());
        if (!listed.succeeded()) {
            throw new IllegalStateException("无法枚举遗留沙箱容器: " + listed.stderr());
        }
        boolean cleaned = listed.stdout().lines().map(String::trim).filter(value -> !value.isBlank())
                .map(this::removeContainer).reduce(true, (left, right) -> left && right);
        if (!cleaned) {
            throw new IllegalStateException("存在无法确认清理的遗留沙箱容器");
        }
    }

    List<String> createCommand(TaskRecord task,
                               RequestPolicy.ValidatedRequest request,
                               String containerName,
                               Stage stage) {
        if (task.profile() != request.profile() || !task.imageReference().equals(request.imageReference())
                || !task.imageId().equals(request.imageId())) {
            throw new IllegalStateException("任务执行环境与已校验请求不一致");
        }
        if (task.profile() == ExecutionProfile.WORKFLOW_SCRIPT_V1) {
            return createWorkflowCommand(task, request, containerName, stage);
        }
        if (task.profile() == ExecutionProfile.WORKSPACE_SHELL_V1 && stage != Stage.EXECUTE) {
            throw new IllegalStateException("工作区 Shell 不允许依赖安装阶段");
        }
        boolean projectExecution = stage == Stage.EXECUTE && request.workspace() != null;
        List<String> command = new ArrayList<>();
        command.addAll(List.of("create",
                "--name", containerName,
                "--label", MANAGED_LABEL,
                "--label", OWNER_LABEL_KEY + "=" + owner,
                "--label", "skillforge.sandbox.task-id=" + task.taskId(),
                "--label", "skillforge.sandbox.stage=" + stage.value,
                "--label", "skillforge.sandbox.profile=" + task.profile().name(),
                "--network", properties.taskNetwork(),
                "--user", "65532:65532",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", Integer.toString(task.executionPolicy().pidsLimit()),
                "--memory", task.executionPolicy().memoryLimit(),
                "--cpus", task.executionPolicy().cpuLimit().toPlainString(),
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777",
                "--workdir", projectExecution ? "/workspace/project" : "/workspace",
                "--env", "HOME=/workspace/work/home",
                "--env", "HTTP_PROXY=" + properties.proxyUrl(),
                "--env", "HTTPS_PROXY=" + properties.proxyUrl(),
                "--env", "ALL_PROXY=",
                "--env", "NO_PROXY=localhost,127.0.0.1,::1",
                "--env", "SSL_CERT_FILE=/etc/skillforge/ca/ca.crt",
                "--env", "REQUESTS_CA_BUNDLE=/etc/skillforge/ca/ca.crt",
                "--env", "NODE_EXTRA_CA_CERTS=/etc/skillforge/ca/ca.crt",
                "--mount", "type=volume,src=" + properties.caVolume()
                        + ",dst=/etc/skillforge/ca,volume-subpath=public,readonly",
                volumeMount(task.taskId(), "work", "/workspace/work", false)));

        if (stage == Stage.INSTALL) {
            command.addAll(List.of(
                    "--env", "PIP_INDEX_URL=" + properties.pythonIndexUrl(),
                    "--env", "PIP_NO_CACHE_DIR=1",
                    "--env", "NPM_CONFIG_REGISTRY=" + properties.nodeRegistryUrl(),
                    "--env", "NPM_CONFIG_IGNORE_SCRIPTS=true",
                    "--env", "NPM_CONFIG_CACHE=/workspace/work/.npm",
                    "--env", "npm_config_cafile=/etc/skillforge/ca/ca.crt",
                    volumeMount(task.taskId(), "evidence", "/workspace/evidence", false)));
        } else {
            command.addAll(List.of(
                    volumeMount(task.taskId(), "input", "/input", true),
                    volumeMount(task.taskId(), "output", "/workspace/output", false)));
            if (task.programTools()) {
                command.add(volumeMount(task.taskId(), "program", "/workspace/program", false));
            }
            if (task.language() == Language.NODE) {
                command.add(nodeModulesMount(task.taskId(), request.entryPath()));
            }
            if (projectExecution) {
                command.add(projectMount(request.workspace().workspaceId()));
            }
        }

        command.add(requiredImageId(task.imageId()));
        command.add(task.profile() == ExecutionProfile.WORKSPACE_SHELL_V1 ? "shell-execute" : stage.value);
        if (stage == Stage.INSTALL) {
            for (DependencySpec dependency : task.dependencies()) {
                command.add(task.language() == Language.PYTHON
                        ? dependency.name() + "==" + dependency.version()
                        : dependency.name() + "@" + dependency.version());
            }
        } else {
            command.add(request.entryPath());
            if (projectExecution) {
                command.add("/workspace/project");
            }
        }
        return command;
    }

    private List<String> createWorkflowCommand(TaskRecord task,
                                               RequestPolicy.ValidatedRequest request,
                                               String containerName,
                                               Stage stage) {
        if (stage != Stage.EXECUTE) {
            throw new IllegalStateException("WORKFLOW_SCRIPT_V1 不允许依赖安装阶段");
        }
        List<String> command = new ArrayList<>(List.of("create",
                "--name", containerName,
                "--label", MANAGED_LABEL,
                "--label", OWNER_LABEL_KEY + "=" + owner,
                "--label", "skillforge.sandbox.task-id=" + task.taskId(),
                "--label", "skillforge.sandbox.stage=" + stage.value,
                "--label", "skillforge.sandbox.profile=" + task.profile().name(),
                "--network", "none",
                "--user", "65532:65532",
                "--read-only",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", Integer.toString(task.executionPolicy().pidsLimit()),
                "--memory", task.executionPolicy().memoryLimit(),
                "--cpus", task.executionPolicy().cpuLimit().toPlainString(),
                "--tmpfs", "/tmp:rw,noexec,nosuid,nodev,size=64m,mode=1777",
                "--workdir", "/tmp",
                "--env", "HOME=/tmp",
                "--interactive",
                volumeMount(task.taskId(), "input", "/input", true),
                requiredImageId(task.imageId()),
                "workflow-execute",
                request.entryPath()));
        return command;
    }

    private StageResult runStage(TaskRecord task,
                                 RequestPolicy.ValidatedRequest request,
                                 Stage stage,
                                 Instant sharedDeadline,
                                 BoundedCapture stdout,
                                 BoundedCapture stderr,
                                 Consumer<String> containerRegistered,
                                 BooleanSupplier cancelled) {
        if (!Instant.now().isBefore(sharedDeadline)) {
            return StageResult.terminal(ExecutionState.TIMED_OUT, stage.phase, null, "EXECUTION_TIMED_OUT");
        }
        Duration taskControlTimeout = Duration.ofSeconds(task.executionPolicy().dockerControlTimeoutSeconds());
        String containerName = containerName(task.taskId(), stage);
        DockerCommandExecutor.CommandResult creation = docker.run(
                createCommand(task, request, containerName, stage), controlTimeout(sharedDeadline, taskControlTimeout));
        if (!creation.succeeded()) {
            log.warn("创建沙箱容器失败 taskTag={} stage={} error={}", taskTag(task.taskId()), stage.value,
                    safeLog(creation.stderr()));
            return StageResult.terminal(creation.timedOut() ? ExecutionState.TIMED_OUT : ExecutionState.FAILED,
                    stage.phase, null, creation.timedOut() ? "DOCKER_CONTROL_TIMEOUT" : "CONTAINER_CREATE_FAILED");
        }
        String containerId = creation.stdout().trim().lines().findFirst().orElse(containerName);
        containerRegistered.accept(containerId);
        if (cancelled.getAsBoolean()) {
            return removeContainer(containerId, taskControlTimeout)
                    ? StageResult.terminal(ExecutionState.CANCELLED, "cancelled", null, "CANCELLED")
                    : StageResult.unknown();
        }

        Process attachment;
        try {
            attachment = task.profile() == ExecutionProfile.WORKFLOW_SCRIPT_V1
                    ? docker.start(List.of("start", "--attach", "--interactive", containerId))
                    : docker.start(List.of("start", "--attach", containerId));
        } catch (IOException startFailure) {
            boolean removed = removeContainer(containerId, taskControlTimeout);
            log.warn("启动沙箱容器失败 taskTag={} stage={} error={}", taskTag(task.taskId()), stage.value,
                    safeLog(startFailure.getMessage()));
            return removed
                    ? StageResult.terminal(ExecutionState.FAILED, stage.phase, null, "CONTAINER_START_FAILED")
                    : StageResult.unknown();
        }

        boolean timedOut = false;
        boolean wasCancelled = false;
        boolean terminationConfirmed = true;
        boolean inputDelivered = true;
        try (var drains = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> stdoutDrain = drains.submit(() -> drain(stdout, attachment.getInputStream()));
            Future<?> stderrDrain = drains.submit(() -> drain(stderr, attachment.getErrorStream()));
            Future<Boolean> inputDelivery = task.profile() == ExecutionProfile.WORKFLOW_SCRIPT_V1
                    ? drains.submit(() -> writeInput(attachment, request.inputJson())) : null;
            while (!attachment.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    wasCancelled = true;
                    terminationConfirmed = removeContainer(containerId, taskControlTimeout);
                    break;
                }
                if (!Instant.now().isBefore(sharedDeadline)) {
                    timedOut = true;
                    terminationConfirmed = removeContainer(containerId, taskControlTimeout);
                    break;
                }
            }
            if (!attachment.waitFor(5, TimeUnit.SECONDS)) {
                attachment.destroyForcibly();
                attachment.waitFor(5, TimeUnit.SECONDS);
            }
            await(stdoutDrain);
            await(stderrDrain);
            if (inputDelivery != null) inputDelivered = awaitResult(inputDelivery);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            wasCancelled = true;
            terminationConfirmed = removeContainer(containerId, taskControlTimeout);
        }

        if ((wasCancelled || timedOut) && !terminationConfirmed) {
            return StageResult.unknown();
        }
        if (wasCancelled || cancelled.getAsBoolean()) {
            return StageResult.terminal(ExecutionState.CANCELLED, "cancelled", null, "CANCELLED");
        }
        if (timedOut) {
            return StageResult.terminal(ExecutionState.TIMED_OUT, stage.phase, null, "EXECUTION_TIMED_OUT");
        }
        ContainerState state = inspectContainerState(containerId, taskControlTimeout);
        if (state == null || state.running() || !removeContainer(containerId, taskControlTimeout)) {
            if (state == null || state.running()) {
                removeContainer(containerId, taskControlTimeout);
            }
            return StageResult.unknown();
        }
        if (!inputDelivered) {
            return StageResult.terminal(ExecutionState.FAILED, stage.phase, state.exitCode(),
                    "WORKFLOW_INPUT_DELIVERY_FAILED");
        }
        if (state.exitCode() != 0) {
            String error = stage == Stage.INSTALL ? "DEPENDENCY_INSTALL_FAILED" : "EXECUTION_FAILED";
            return StageResult.terminal(ExecutionState.FAILED, stage.phase, state.exitCode(), error);
        }
        return StageResult.succeeded(state.exitCode(), stage.phase);
    }

    private boolean writeInput(Process attachment, String inputJson) {
        try (var input = attachment.getOutputStream()) {
            input.write(inputJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            input.flush();
            return true;
        } catch (IOException failed) {
            return false;
        }
    }

    RunnerResult workflowResult(Integer exitCode, BoundedCapture stdout, BoundedCapture stderr) {
        if (stdout.truncated()) {
            return RunnerResult.failed("execution", exitCode, stdout.text(), stderr.text(), true,
                    stderr.truncated(), "WORKFLOW_JSON_OUTPUT_TOO_LARGE");
        }
        try {
            String value = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(stdout.bytes())).toString();
            JsonNode parsed = strictJson.readTree(value);
            if (parsed == null || !parsed.isObject()) throw new CharacterCodingException();
            return new RunnerResult(ExecutionState.COMPLETED, "completed", exitCode, value, stderr.text(),
                    false, stderr.truncated(), null, parsed);
        } catch (Exception invalid) {
            return RunnerResult.failed("execution", exitCode, stdout.text(), stderr.text(), false,
                    stderr.truncated(), "WORKFLOW_JSON_OUTPUT_INVALID");
        }
    }

    private String nodeModulesMount(String taskId, String entryPath) {
        int separator = entryPath.lastIndexOf('/');
        String parent = separator <= 0 ? "/input" : entryPath.substring(0, separator);
        return "--mount=type=volume,src=" + properties.taskVolume() + ",dst=" + parent
                + "/node_modules,volume-subpath=" + workspace.volumeSubpath(taskId, "work/node_modules");
    }

    private void verifyIsolationReady() {
        DockerCommandExecutor.CommandResult network = docker.run(
                List.of("network", "inspect", "--format",
                        "{{.Internal}} {{index .Options \"com.docker.network.bridge.gateway_mode_ipv4\"}} {{.EnableIPv6}}",
                        properties.taskNetwork()), properties.dockerControlTimeout());
        if (!network.succeeded() || !"true isolated false".equals(network.stdout().trim())) {
            throw new IllegalStateException("沙箱任务网络必须是 internal、isolated gateway 且禁用 IPv6");
        }
        DockerCommandExecutor.CommandResult proxy = docker.run(
                List.of("inspect", "--format",
                        "{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
                        properties.proxyContainer()), properties.dockerControlTimeout());
        if (!proxy.succeeded() || !"true healthy".equals(proxy.stdout().trim())) {
            throw new IllegalStateException("沙箱出站代理未运行或未就绪");
        }
        inspectVolume(properties.taskVolume());
        inspectVolume(properties.caVolume());
        inspectVolume(projectProperties.projectVolume());
        pythonImageId = inspectImage(properties.pythonImage());
        nodeImageId = inspectImage(properties.nodeImage());
        workflowPythonImageId = inspectImage(properties.workflowPythonImage());
        workflowNodeImageId = inspectImage(properties.workflowNodeImage());
        log.info("沙箱隔离资源就绪 taskNetwork={} pythonImageTag={} nodeImageTag={} "
                        + "workflowPythonImageTag={} workflowNodeImageTag={}",
                properties.taskNetwork(), objectTag(pythonImageId), objectTag(nodeImageId),
                objectTag(workflowPythonImageId), objectTag(workflowNodeImageId));
    }

    private void inspectVolume(String volume) {
        DockerCommandExecutor.CommandResult inspected = docker.run(List.of("volume", "inspect", volume),
                properties.dockerControlTimeout());
        if (!inspected.succeeded()) {
            throw new IllegalStateException("沙箱持久卷未就绪: " + volume);
        }
    }

    private String inspectImage(String image) {
        DockerCommandExecutor.CommandResult inspected = docker.run(
                List.of("image", "inspect", "--format", "{{.Id}}", image), properties.dockerControlTimeout());
        String id = inspected.stdout().trim();
        if (!inspected.succeeded() || !id.startsWith("sha256:") || id.length() != 71) {
            throw new IllegalStateException("沙箱运行镜像不存在或无法固定摘要: " + image);
        }
        return id;
    }

    private String requiredImageId(String imageId) {
        if (imageId == null || imageId.isBlank()) {
            throw new IllegalStateException("沙箱运行镜像尚未通过就绪校验");
        }
        return imageId;
    }

    private String volumeMount(String taskId, String child, String destination, boolean readOnly) {
        return "--mount=type=volume,src=" + properties.taskVolume() + ",dst=" + destination
                + ",volume-subpath=" + workspace.volumeSubpath(taskId, child)
                + (readOnly ? ",readonly" : "");
    }

    /** 只暴露单个工作区的项目子目录；容器无法访问卷内的其他项目、状态和快照。 */
    private String projectMount(String workspaceId) {
        log.debug("构造授权工作区挂载 workspaceTag={}", objectTag(workspaceId));
        String mount = "--mount=type=volume,src=" + projectProperties.projectVolume()
                + ",dst=/workspace/project,volume-subpath=" + workspaceId + "/project";
        log.debug("授权工作区挂载构造完成 workspaceTag={}", objectTag(workspaceId));
        return mount;
    }

    private ContainerState inspectContainerState(String containerId, Duration timeout) {
        DockerCommandExecutor.CommandResult inspection = docker.run(
                List.of("inspect", "--format", "{{.State.Running}} {{.State.ExitCode}}", containerId),
                timeout);
        if (!inspection.succeeded()) {
            return null;
        }
        try {
            String[] fields = inspection.stdout().trim().split("\\s+");
            if (fields.length != 2) {
                return null;
            }
            return new ContainerState(Boolean.parseBoolean(fields[0]), Integer.parseInt(fields[1]));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private boolean removeContainer(String containerId) {
        return removeContainer(containerId, properties.dockerControlTimeout());
    }

    private boolean removeContainer(String containerId, Duration timeout) {
        DockerCommandExecutor.CommandResult inspection = docker.run(
                List.of("inspect", "--type", "container", "--format", "{{.Id}} {{index .Config.Labels \"" + OWNER_LABEL_KEY + "\"}}",
                        containerId), timeout);
        if (!inspection.succeeded()) {
            if (!inspection.timedOut() && (inspection.stderr().contains("No such container")
                    || inspection.stderr().contains("No such object"))) {
                return true;
            }
            log.warn("无法确认沙箱容器归属 containerTag={}", objectTag(containerId));
            return false;
        }
        String[] identity = inspection.stdout().trim().split("\\s+");
        if (identity.length != 2 || !identity[0].matches("[0-9a-f]{64}") || !owner.equals(identity[1])) {
            log.warn("拒绝清理非当前任务卷容器 containerTag={}", objectTag(containerId));
            return false;
        }
        // 名称可在 inspect 后被替换；只删除已确认 owner 的不可变容器 ID。
        DockerCommandExecutor.CommandResult removal = docker.run(
                List.of("rm", "--force", "--volumes", identity[0]), timeout);
        boolean absent = !removal.timedOut() && removal.stderr().contains("No such container");
        if (!removal.succeeded() && !absent) {
            log.warn("清理沙箱容器失败 containerTag={} error={}", objectTag(containerId), safeLog(removal.stderr()));
        }
        return removal.succeeded() || absent;
    }

    private String containerName(String taskId, Stage stage) {
        return "sf-sandbox-" + owner + "-" + sha256(taskId).substring(0, 20) + "-" + stage.value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256", impossible);
        }
    }

    private Duration controlTimeout(Instant deadline, Duration timeout) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            return Duration.ofMillis(1);
        }
        return remaining.compareTo(timeout) < 0
                ? remaining : timeout;
    }

    private Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private void drain(BoundedCapture capture, java.io.InputStream input) {
        try {
            capture.drain(input);
        } catch (IOException ignored) {
            // 容器杀停会关闭 attach 管道；终态由取消/超时事实单独决定。
        }
    }

    private void await(Future<?> future) throws InterruptedException {
        try {
            future.get();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // drain 已在所属线程处理 I/O 关闭。
        }
    }

    private boolean awaitResult(Future<Boolean> future) throws InterruptedException {
        try {
            return future.get();
        } catch (java.util.concurrent.ExecutionException failed) {
            return false;
        }
    }

    private String taskTag(String taskId) {
        return Integer.toHexString(taskId.hashCode());
    }

    private String safeLog(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        return cleaned.length() > 256 ? cleaned.substring(0, 256) : cleaned;
    }

    private String objectTag(String value) {
        return value == null ? "unknown" : Integer.toHexString(value.hashCode());
    }

    enum Stage {
        INSTALL("install", "dependency_install"),
        EXECUTE("execute", "execution");

        private final String value;
        private final String phase;

        Stage(String value, String phase) {
            this.value = value;
            this.phase = phase;
        }
    }

    private record ContainerState(boolean running, int exitCode) {
    }

    private record StageResult(ExecutionState terminalState, String phase, Integer exitCode, String error) {

        static StageResult succeeded(int exitCode, String phase) {
            return new StageResult(null, phase, exitCode, null);
        }

        static StageResult terminal(ExecutionState state, String phase, Integer exitCode, String error) {
            return new StageResult(state, phase, exitCode, error);
        }

        static StageResult unknown() {
            return terminal(ExecutionState.UNKNOWN, "termination_unconfirmed", null,
                    "CONTAINER_TERMINATION_UNCONFIRMED");
        }

        RunnerResult toRunnerResult(BoundedCapture stdout, BoundedCapture stderr) {
            return new RunnerResult(terminalState, phase, exitCode, stdout.text(), stderr.text(),
                    stdout.truncated(), stderr.truncated(), error, null);
        }
    }

    public record RunnerResult(ExecutionState state, String phase, Integer exitCode,
                               String stdout, String stderr, boolean stdoutTruncated,
                               boolean stderrTruncated, String error, JsonNode jsonResult) {

        public RunnerResult {
            jsonResult = jsonResult == null ? null : jsonResult.deepCopy();
        }

        @Override
        public JsonNode jsonResult() {
            return jsonResult == null ? null : jsonResult.deepCopy();
        }

        static RunnerResult failed(String phase, Integer exitCode, String stdout, String stderr,
                                   boolean stdoutTruncated, boolean stderrTruncated, String error) {
            return new RunnerResult(ExecutionState.FAILED, phase, exitCode, stdout, stderr,
                    stdoutTruncated, stderrTruncated, error, null);
        }

        static RunnerResult timedOut(String phase, String stdout, String stderr,
                                     boolean stdoutTruncated, boolean stderrTruncated) {
            return new RunnerResult(ExecutionState.TIMED_OUT, phase, null, stdout, stderr,
                    stdoutTruncated, stderrTruncated, "EXECUTION_TIMED_OUT", null);
        }

        static RunnerResult cancelled(BoundedCapture stdout, BoundedCapture stderr) {
            return new RunnerResult(ExecutionState.CANCELLED, "cancelled", null, stdout.text(), stderr.text(),
                    stdout.truncated(), stderr.truncated(), "CANCELLED", null);
        }
    }

    public record ExecutionEnvironment(String imageReference, String imageId) {
    }
}
