package com.skillforge.sandbox.execution;

import com.skillforge.execution.Language;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.sandbox.config.SandboxProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;

/** 管理任务私有目录；输入文件只读，可写工作区和输出目录不与其他任务共享。 */
@Component
public class TaskWorkspace {

    private static final String PROGRAM_TOOLS_SDK = """
            import { randomUUID } from 'node:crypto';
            import { lstat, readFile, rename, unlink, writeFile } from 'node:fs/promises';
            import { setTimeout as delay } from 'node:timers/promises';

            const programDirectory = '/workspace/program';
            const maximumCalls = %d;
            const maximumMessageBytes = %d;
            let nextOrdinal = 0;
            let queue = Promise.resolve();

            function validate(handle, args) {
              if (typeof handle !== 'string' || handle.trim().length === 0 ||
                  [...handle].length > 128 || /[\\u0000-\\u001f\\u007f-\\u009f]/u.test(handle)) {
                throw new TypeError('tool handle must contain 1..128 characters without controls');
              }
              if (args === null || typeof args !== 'object' || Array.isArray(args)) {
                throw new TypeError('tool args must be an object');
              }
            }

            async function callOnce(handle, args) {
              validate(handle, args);
              const ordinal = nextOrdinal + 1;
              if (ordinal > maximumCalls) {
                throw new Error('program tool call limit exceeded');
              }
              const message = JSON.stringify({ ordinal, handle, arguments: args });
              const normalized = JSON.parse(message).arguments;
              if (normalized === null || typeof normalized !== 'object' || Array.isArray(normalized)) {
                throw new TypeError('tool args must serialize as an object');
              }
              const encoded = Buffer.from(message, 'utf8');
              if (encoded.length > maximumMessageBytes) {
                throw new Error('program tool request exceeds 64 KiB');
              }
              const request = `${programDirectory}/request-${ordinal}.json`;
              const temporary = `${programDirectory}/.request-${ordinal}-${randomUUID()}.tmp`;
              await writeFile(temporary, encoded, { flag: 'wx', mode: 0o644 });
              try {
                await rename(temporary, request);
              } catch (failure) {
                await unlink(temporary).catch(() => undefined);
                throw failure;
              }
              nextOrdinal = ordinal;

              const response = `${programDirectory}/response-${ordinal}.json`;
              for (;;) {
                let bytes;
                try {
                  const attributes = await lstat(response);
                  if (!attributes.isFile() || attributes.isSymbolicLink() || attributes.size > maximumMessageBytes) {
                    throw new Error('invalid program tool response file');
                  }
                  bytes = await readFile(response);
                  if (bytes.length > maximumMessageBytes) {
                    throw new Error('program tool response exceeds 64 KiB');
                  }
                } catch (failure) {
                  if (failure?.code !== 'ENOENT') throw failure;
                  await delay(25);
                  continue;
                }
                const payload = JSON.parse(bytes.toString('utf8'));
                if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) {
                  throw new Error('program tool response must be an object');
                }
                return payload;
              }
            }

            const call = (handle, args = {}) => {
              const pending = queue.then(() => callOnce(handle, args));
              queue = pending.catch(() => undefined);
              return pending;
            };

            export const tools = new Proxy({ call }, {
              get(target, property, receiver) {
                if (Reflect.has(target, property)) return Reflect.get(target, property, receiver);
                if (property === 'then') return undefined;
                return typeof property === 'string' ? (args = {}) => call(property, args) : undefined;
              }
            });
            export default tools;
            """.formatted(ExecutionLimits.PROGRAM_CALLS, ExecutionLimits.PROGRAM_MESSAGE_BYTES);

    private static final Set<PosixFilePermission> WRITABLE_DIRECTORY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);
    private static final Set<PosixFilePermission> READ_ONLY_FILE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);

    private final Path root;
    public TaskWorkspace(SandboxProperties properties) {
        this.root = properties.dataRoot().resolve("workspaces").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException failure) {
            throw new IllegalStateException("无法初始化任务工作目录: " + root, failure);
        }
    }

    public void prepare(RequestPolicy.ValidatedRequest request) {
        prepare(request,null);
    }

    /** 清单已绑定请求摘要；输入完整校验并落入私有只读目录后才允许调度容器。 */
    public void prepare(RequestPolicy.ValidatedRequest request,java.util.zip.ZipInputStream archive) {
        Path taskRoot = taskRoot(request.taskId());
        boolean created=false;
        try {
            Files.createDirectory(taskRoot);
            created=true;
            setDirectoryPermissions(taskRoot);
            Path input = Files.createDirectory(taskRoot.resolve("input"));
            Path output = Files.createDirectory(taskRoot.resolve("output"));
            Path work = Files.createDirectory(taskRoot.resolve("work"));
            Path evidence = Files.createDirectory(taskRoot.resolve("evidence"));
            setDirectoryPermissions(input);
            setDirectoryPermissions(output);
            setDirectoryPermissions(work);
            setDirectoryPermissions(evidence);
            if (request.profile() == ExecutionProfile.WORKSPACE_SHELL_V1) {
                // Shell 跳过安装阶段，仍提供本次调用独享的 HOME，不能写入持久项目状态目录。
                setDirectoryPermissions(Files.createDirectory(work.resolve("home")));
            }
            if (request.programTools()) {
                Path program = Files.createDirectory(taskRoot.resolve("program"));
                setProgramDirectoryPermissions(program);
            }

            if (request.scriptPath()==null) {
                String fileName = switch (request.language()) {
                    case PYTHON -> "main.py";
                    case NODE -> "main.mjs";
                    case SHELL -> "main.sh";
                };
                Path source = input.resolve(fileName);
                Files.writeString(source, request.code(), StandardCharsets.UTF_8);
                setReadOnlyPermissions(source);
                if (request.programTools()) {
                    Path sdk = input.resolve("__skillforge_tools.mjs");
                    Files.writeString(sdk, PROGRAM_TOOLS_SDK, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    setReadOnlyPermissions(sdk);
                }
            }
            if (request.profile() == ExecutionProfile.WORKFLOW_SCRIPT_V1) {
                Path workflowInput = input.resolve("input.json");
                Files.writeString(workflowInput, request.inputJson(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                setReadOnlyPermissions(workflowInput);
            }
            if(!request.artifactInputs().isEmpty()) {
                if(archive==null)throw new IOException("artifact_input_archive_missing");
                var expected=new java.util.HashMap<String,com.skillforge.execution.ArtifactFile>();
                request.artifactInputs().forEach(file->expected.put(file.path(),file));
                var received=new java.util.HashSet<String>();
                java.util.zip.ZipEntry entry;
                while((entry=archive.getNextEntry())!=null) {
                    var file=expected.get(entry.getName());
                    if(file==null||entry.isDirectory()||!received.add(entry.getName()))throw new IOException("artifact_input_archive_rejected");
                    Path target=input.resolve(file.path()).normalize();
                    if(!target.startsWith(input))throw new IOException("artifact_input_path_rejected");
                    Files.createDirectories(target.getParent());
                    var digest=MessageDigest.getInstance("SHA-256");long size=0;
                    try(var outputFile=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
                        byte[] buffer=new byte[64*1024];int count;
                        while((count=archive.read(buffer))!=-1) {
                            if(Thread.currentThread().isInterrupted()||!java.time.Instant.now().isBefore(request.deadlineAt()))
                                throw new IOException("artifact_input_deadline_elapsed");
                            size=Math.addExact(size,count);if(size>file.size())throw new IOException("artifact_input_size_mismatch");
                            outputFile.write(buffer,0,count);digest.update(buffer,0,count);
                        }
                    }
                    if(size!=file.size()||!HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))throw new IOException("artifact_input_integrity_mismatch");
                    setReadOnlyPermissions(target);
                }
                if(!received.equals(expected.keySet()))throw new IOException("artifact_input_incomplete");
            } else if(archive!=null&&archive.getNextEntry()!=null)throw new IOException("artifact_input_archive_unexpected");
            for(var file:request.inputs()) {
                // Shell 资源只按冻结字节挂载；模板、图片等不要求 UTF-8，也不自动成为执行入口。
                if(request.profile()==ExecutionProfile.WORKSPACE_SHELL_V1)continue;
                // 冻结包的模板、图片等附件保留原始字节，仅执行源码要求 UTF-8。
                if(request.profile()==ExecutionProfile.STANDARD
                        && !(file.path().endsWith(".py") || file.path().endsWith(".js")
                        || file.path().endsWith(".mjs") || file.path().endsWith(".cjs"))) continue;
                Path source=input.resolve(file.path());
                try(var reader=new java.io.InputStreamReader(Files.newInputStream(source),
                        StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))) {
                    char[] buffer=new char[ExecutionLimits.STREAM_BUFFER_BYTES];
                    while(reader.read(buffer)!=-1) {
                        if(Thread.currentThread().isInterrupted()||!java.time.Instant.now().isBefore(request.deadlineAt()))
                            throw new IOException("script_input_deadline_elapsed");
                    }
                }
            }
            if (request.profile() == ExecutionProfile.STANDARD && request.language() == Language.NODE) {
                String entryPath = request.scriptPath() == null ? "main.mjs" : request.scriptPath();
                Path entryParent = input.resolve(entryPath).normalize().getParent();
                Path nodeModulesMountPoint = entryParent.resolve("node_modules");
                Files.createDirectory(nodeModulesMountPoint);
                setDirectoryPermissions(nodeModulesMountPoint);
            }
            if (request.profile() == ExecutionProfile.STANDARD) {
                Path nodeModules = Files.createDirectory(work.resolve("node_modules"));
                setDirectoryPermissions(nodeModules);
            }
        } catch (FileAlreadyExistsException collision) {
            if(created)try{delete(request.taskId());}catch(RuntimeException cleanup){collision.addSuppressed(cleanup);}
            throw new IllegalStateException("任务工作目录已经存在，拒绝覆盖: " + request.taskId(), collision);
        } catch (IOException|NoSuchAlgorithmException failure) {
            try{delete(request.taskId());}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            throw new IllegalStateException("无法准备任务工作目录: " + request.taskId(), failure);
        }
    }

    public Path output(String taskId) {
        return taskRoot(taskId).resolve("output");
    }

    Path program(String taskId) {
        return taskRoot(taskId).resolve("program");
    }

    /** 无依赖 STANDARD 调用只生成与空安装入口相同的本次材料，不共享环境或跳过证据封存。 */
    public void prepareEmptyDependencies(RequestPolicy.ValidatedRequest request) {
        if (request.profile() != ExecutionProfile.STANDARD || !request.dependencies().isEmpty()
                || request.language() != Language.PYTHON && request.language() != Language.NODE)
            throw new IllegalArgumentException("empty_dependency_preparation_invalid");
        Path task = taskRoot(request.taskId());
        Path work = task.resolve("work");
        try {
            prepareEmptyDependencyDirectory(work.resolve("home"));
            String report;
            if (request.language() == Language.PYTHON) {
                prepareEmptyDependencyDirectory(work.resolve("python"));
                report = "{\"install\":[],\"version\":\"1\"}\n";
            } else {
                prepareEmptyDependencyDirectory(work.resolve("node_modules"));
                Files.writeString(work.resolve("package.json"), "{\"private\":true,\"type\":\"module\"}\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                report = "{\"lockfileVersion\":3,\"packages\":{}}\n";
            }
            Files.writeString(task.resolve("evidence").resolve("dependencies.json"), report,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException failure) {
            throw new IllegalStateException("无法准备本次空依赖材料", failure);
        }
    }

    /** 对 prepare 已建立的本次目录遵循 mkdir -p 语义，但不接纳符号链接或其他文件。 */
    private void prepareEmptyDependencyDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException existing) {
            var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) throw existing;
        }
        setDirectoryPermissions(directory);
    }

    /** 安装容器移除或本次空依赖材料生成后，由服务固化执行容器不可修改的依赖证据摘要。 */
    public String sealDependencyEvidence(String taskId) {
        Path evidence = taskRoot(taskId).resolve("evidence");
        Path report = evidence.resolve("dependencies.json");
        try {
            var attributes = Files.readAttributes(report, java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                throw new IllegalStateException("依赖安装未生成普通证据文件");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (var input = new DigestInputStream(
                    Files.newInputStream(report, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS), digest)) {
                while (input.read(buffer) >= 0) {
                    // 固定缓冲流式计算，证据大小不进入内存。
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            Path manifest = evidence.resolve("dependencies.sha256");
            Files.writeString(manifest, sha256 + "\n", StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setReadOnlyPermissions(report);
            setReadOnlyPermissions(manifest);
            return sha256;
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("无法固化依赖安装证据: " + taskId, failure);
        }
    }

    public String volumeSubpath(String taskId, String child) {
        return "workspaces/" + taskId + "/" + child;
    }

    public boolean exists(String taskId) {
        return Files.isDirectory(taskRoot(taskId), LinkOption.NOFOLLOW_LINKS);
    }

    /** 默认不跟随链接递归删除本任务短期目录，持久任务记录由 TaskRepository 保留。 */
    public void delete(String taskId) {
        Path target = taskRoot(taskId);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (Files.isSymbolicLink(target)) {
                Files.delete(target);
                return;
            }
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    // Windows 不允许直接删除只读文件；仅回收本任务普通文件，绝不跟随链接修改目标权限。
                    if(attributes.isRegularFile()) {
                        var dos=Files.getFileAttributeView(file,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                        if(dos!=null&&dos.readAttributes().isReadOnly())dos.setReadOnly(false);
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException failure) {
            throw new IllegalStateException("无法清理任务工作目录: " + taskId, failure);
        }
    }

    private Path taskRoot(String taskId) {
        Path candidate = root.resolve(taskId).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("任务目录越界");
        }
        return candidate;
    }

    private void setDirectoryPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, WRITABLE_DIRECTORY);
        } catch (UnsupportedOperationException ignored) {
            // Windows 开发环境由 Docker Desktop 的挂载隔离承担权限映射。
        }
    }

    private void setProgramDirectoryPermissions(Path path) throws IOException {
        setDirectoryPermissions(path);
        try {
            Files.setAttribute(path, "unix:mode", 01777, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Windows 开发环境没有 sticky bit；生产 Linux 目录用它冻结服务写入的响应文件名。
        }
    }

    private void setReadOnlyPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, READ_ONLY_FILE);
        } catch (UnsupportedOperationException ignored) {
            path.toFile().setReadOnly();
        }
    }
}
