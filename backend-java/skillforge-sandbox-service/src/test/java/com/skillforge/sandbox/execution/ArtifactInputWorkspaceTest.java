package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.sandbox.api.*;
import com.skillforge.execution.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactInputWorkspaceTest {
    @TempDir Path root;
    private final RequestPolicy policy=new RequestPolicy(new ObjectMapper());
    private final String path="artifacts/12345678-1234-1234-1234-123456789abc/records.json";
    @Test void streamsVerifiedArtifactAlongsideInlineEntryAndBindsDigest()throws Exception {
        var request=request("task");var file=file(path,"[]");
        var validated=policy.validate(request,List.of(file));
        assertNotEquals(policy.validate(request).digest(),validated.digest());
        var workspace=new TaskWorkspace(TestSandboxProperties.create(root));
        try(var zip=archive(path,"[]")){workspace.prepare(validated,zip);}
        assertEquals("[]",Files.readString(root.resolve("workspaces/task/input/"+path)));
        assertEquals("print('ok')",Files.readString(root.resolve("workspaces/task/input/main.py")));
        if(Files.getFileStore(root).supportsFileAttributeView("posix"))
            assertFalse(Files.getPosixFilePermissions(root.resolve("workspaces/task/input/"+path)).contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        workspace.delete("task");
        assertFalse(workspace.exists("task"));
    }
    @Test void corruptMissingAndUnlistedFilesNeverLeavePartialWorkspace()throws Exception {
        var workspace=new TaskWorkspace(TestSandboxProperties.create(root));
        var validated=policy.validate(request("task"),List.of(file(path,"[]")));
        try(var zip=archive(path,"xx")){assertThrows(IllegalStateException.class,()->workspace.prepare(validated,zip));}
        assertFalse(workspace.exists("task"));
        try(var zip=archive("../escape","[]")){assertThrows(IllegalStateException.class,()->workspace.prepare(validated,zip));}
        assertFalse(workspace.exists("task"));
        try(var zip=archive(null,"")){assertThrows(IllegalStateException.class,()->workspace.prepare(validated,zip));}
        assertFalse(workspace.exists("task"));
        assertThrows(SandboxRequestException.class,()->policy.validate(request("task"),List.of(file("artifacts/../escape","[]"))));
    }
    private ExecuteRequest request(String task) {
        return new ExecuteRequest(task, Language.PYTHON, "print('ok')", List.of(), List.of(), null, false,
                Instant.now().plusSeconds(60), TestSandboxProperties.policy(), List.of(),
                com.skillforge.execution.ExecutionProfile.STANDARD, null, null);
    }

    @Test void frozenSkillPackagePreservesExamplesAndBinaryTemplatesAndRejectsCorruption() throws Exception {
        var payloads=new LinkedHashMap<String,byte[]>();
        payloads.put("scripts/main.py","print('ok')".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        payloads.put("examples/input.json","{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        payloads.put("assets/template.bin",new byte[]{(byte)0xff,(byte)0xfe,0,42});
        var files=new ArrayList<ArtifactFile>();
        for(var entry:payloads.entrySet()) files.add(new ArtifactFile(entry.getKey(),entry.getValue().length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))));
        var request=new ExecuteRequest("skill",Language.PYTHON,"",List.of(),files,"scripts/main.py",false,
                Instant.now().plusSeconds(60),TestSandboxProperties.policy(),List.of(),ExecutionProfile.STANDARD,null,null);
        var validated=policy.validate(request,files);
        var workspace=new TaskWorkspace(TestSandboxProperties.create(root));
        try(var zip=archive(payloads)){workspace.prepare(validated,zip);}
        for(var entry:payloads.entrySet()) assertArrayEquals(entry.getValue(),Files.readAllBytes(
                root.resolve("workspaces/skill/input/"+entry.getKey())));
        workspace.delete("skill");
        payloads.put("assets/template.bin",new byte[]{1,2,3,4});
        try(var zip=archive(payloads)){assertThrows(IllegalStateException.class,()->workspace.prepare(validated,zip));}
        assertFalse(workspace.exists("skill"));
    }

    /** 带整包的 Shell 仍创建独立只读入口，保留二进制与原目录，不把包脚本改写成入口。 */
    @Test void shellPackageResourcesPreserveBytesAndReadonlyEntryAlongsideCommand() throws Exception {
        var payloads=new LinkedHashMap<String,byte[]>();
        payloads.put("scripts/check.sh","cat \"$SKILL_DIR/assets/template.bin\"".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        payloads.put("assets/template.bin",new byte[]{(byte)0xff,0,42});
        var files=new ArrayList<ArtifactFile>();
        for(var entry:payloads.entrySet())files.add(new ArtifactFile(entry.getKey(),entry.getValue().length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(entry.getValue()))));
        var binding=new WorkspaceBinding("a".repeat(64),"7","session",1,"chat",new WorkspacePolicy(1,300,24,10));
        String command="export SKILL_DIR=/input\nbash \"$SKILL_DIR/scripts/check.sh\"";
        var raw=new ExecuteRequest("shell-skill",Language.SHELL,command,List.of(),files,null,false,
                Instant.now().plusSeconds(60),TestSandboxProperties.policy(),List.of(),ExecutionProfile.WORKSPACE_SHELL_V1,null,null,binding);
        var workspace=new TaskWorkspace(TestSandboxProperties.create(root));
        try(var zip=archive(payloads)){workspace.prepare(policy.validate(raw,files),zip);}
        var input=root.resolve("workspaces/shell-skill/input");
        assertEquals(command,Files.readString(input.resolve("main.sh")));
        for(var entry:payloads.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(input.resolve(entry.getKey())));
        if(Files.getFileStore(root).supportsFileAttributeView("posix")) {
            for(String path:List.of("main.sh","scripts/check.sh","assets/template.bin"))
                assertFalse(Files.getPosixFilePermissions(input.resolve(path)).contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } else if(Files.getFileStore(root).supportsFileAttributeView("dos")) {
            assertTrue(Files.readAttributes(input.resolve("assets/template.bin"),java.nio.file.attribute.DosFileAttributes.class).isReadOnly());
        }
        workspace.delete("shell-skill");
        payloads.put("assets/template.bin",new byte[]{1,2,3});
        try(var zip=archive(payloads)){assertThrows(IllegalStateException.class,()->workspace.prepare(policy.validate(raw,files),zip));}
        assertFalse(workspace.exists("shell-skill"));
    }

    private ZipInputStream archive(Map<String,byte[]> payloads) throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            for(var entry:payloads.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();
            }
        }
        return new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
    private ArtifactFile file(String name,String content)throws Exception {
        byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new ArtifactFile(name,bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
    private ZipInputStream archive(String name,String content)throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)){if(name!=null){zip.putNextEntry(new ZipEntry(name));zip.write(content.getBytes());zip.closeEntry();}}
        return new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
