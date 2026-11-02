package com.skillforge.institution.artifact;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 产物版本比较和定向恢复共用当前授权、不可变清单与文件摘要校验。 */
@Service
public class ArtifactVersionService {
    static final int MAX_DIFF_BYTES=128*1024;
    static final int MAX_DIFF_LINES=2000;
    private static final Pattern LINE_BREAK=Pattern.compile("\\R");
    private final ArtifactStorage storage;
    private final ArtifactAccess access;

    public ArtifactVersionService(ArtifactStorage storage,ArtifactAccess access) {
        this.storage=storage;
        this.access=access;
    }

    public RecoveryResult recovery(String sessionId,List<ArtifactInput> inputs) throws IOException {
        String tenant=access.tenant();
        access.authorizeCurrent(tenant,sessionId);
        var verified=new ArrayList<ArtifactProvenance>();
        try {
            if(inputs==null||inputs.isEmpty()||inputs.size()>256||new java.util.HashSet<>(inputs).size()!=inputs.size())
                throw new IllegalArgumentException("artifact_inputs_rejected");
            verified.ensureCapacity(inputs.size());
            for(var input:inputs) {
                var row=storage.require(tenant,input.artifactId());
                if(!sessionId.equals(access.authorizeArtifactCurrent(tenant,row)))throw ArtifactStorage.unavailable();
                verified.add(storage.verifiedFile(row,input.path(),0).provenance());
            }
        } catch(IOException|IllegalStateException rejected) {
            throw ArtifactStorage.unavailable();
        }
        access.authorizeCurrent(tenant,sessionId);
        for(var input:verified)access.auditCurrent(tenant,"artifact.recovery_input",input.artifactId(),
                "核验产物定向恢复输入",java.util.Map.of("size",input.size()));
        return new RecoveryResult("validated",sessionId,verified);
    }

    public DiffResult diff(ArtifactInput base,ArtifactInput target) throws IOException {
        String tenant=access.tenant();
        ArtifactRow baseRow=storage.require(tenant,base.artifactId());
        ArtifactRow targetRow=storage.require(tenant,target.artifactId());
        String baseSession=access.authorizeArtifactCurrent(tenant,baseRow);
        String targetSession=access.authorizeArtifactCurrent(tenant,targetRow);
        if(!baseSession.equals(targetSession))throw ArtifactStorage.unavailable();
        ArtifactStorage.VerifiedFile baseFile;
        ArtifactStorage.VerifiedFile targetFile;
        try {
            baseFile=storage.verifiedFile(baseRow,base.path(),MAX_DIFF_BYTES);
            access.authorizeArtifactCurrent(tenant,targetRow);
            targetFile=storage.verifiedFile(targetRow,target.path(),MAX_DIFF_BYTES);
        } catch(IOException|IllegalStateException rejected) {
            throw ArtifactStorage.unavailable();
        }
        access.authorizeArtifactCurrent(tenant,baseRow);
        var baseRef=Reference.from(baseFile.provenance());
        var targetRef=Reference.from(targetFile.provenance());
        if(baseFile.content()==null||targetFile.content()==null)
            return auditedDiff(tenant,new DiffResult("too_large",baseRef,targetRef,List.of()));
        List<String> baseLines=decodeLines(baseFile.content());
        List<String> targetLines=decodeLines(targetFile.content());
        if(baseLines==null||targetLines==null)
            return auditedDiff(tenant,new DiffResult("binary",baseRef,targetRef,List.of()));
        if(baseLines.size()>MAX_DIFF_LINES||targetLines.size()>MAX_DIFF_LINES)
            return auditedDiff(tenant,new DiffResult("too_many_lines",baseRef,targetRef,List.of()));
        return auditedDiff(tenant,new DiffResult("comparable",baseRef,targetRef,changes(baseLines,targetLines)));
    }

    private DiffResult auditedDiff(String tenant,DiffResult result) {
        access.auditCurrent(tenant,"artifact.diff",result.base().artifactId(),"比较会话产物文本差异",
                java.util.Map.of("targetArtifact",result.target().artifactId(),"status",result.status()));
        return result;
    }

    /** 空文件为零行；CRLF、LF、CR 与其余 Unicode 行分隔符按相同逻辑行比较。 */
    private static List<String> decodeLines(byte[] content) {
        String text;
        try {
            text=StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch(CharacterCodingException invalidUtf8) {
            return null;
        }
        if(text.indexOf('\u0000')>=0)return null;
        if(text.isEmpty())return List.of();
        String[] split=LINE_BREAK.split(text,-1);
        int length=split.length;
        if(length>0&&split[length-1].isEmpty())length--;
        return List.copyOf(java.util.Arrays.asList(java.util.Arrays.copyOf(split,length)));
    }

    private static List<Change> changes(List<String> base,List<String> target) {
        int[][] lcs=new int[base.size()+1][target.size()+1];
        for(int left=base.size()-1;left>=0;left--)
            for(int right=target.size()-1;right>=0;right--)
                lcs[left][right]=base.get(left).equals(target.get(right))
                        ?lcs[left+1][right+1]+1:Math.max(lcs[left+1][right],lcs[left][right+1]);
        var changes=new ArrayList<Change>(base.size()+target.size());
        int left=0,right=0;
        while(left<base.size()||right<target.size()) {
            if(left<base.size()&&right<target.size()&&base.get(left).equals(target.get(right))) {
                changes.add(new Change("unchanged",left+1,right+1,base.get(left)));left++;right++;
            } else if(right<target.size()&&(left==base.size()||lcs[left][right+1]>=lcs[left+1][right])) {
                changes.add(new Change("added",null,right+1,target.get(right)));right++;
            } else {
                changes.add(new Change("removed",left+1,null,base.get(left)));left++;
            }
        }
        return List.copyOf(changes);
    }

    public record RecoveryResult(String status,@JsonProperty("session_id") String sessionId,
            @JsonProperty("artifact_inputs") List<ArtifactProvenance> artifactInputs) {
        public RecoveryResult { artifactInputs=List.copyOf(artifactInputs); }
    }
    public record DiffResult(String status,Reference base,Reference target,List<Change> changes) {
        public DiffResult { changes=List.copyOf(changes); }
    }
    public record Reference(@JsonProperty("artifact_id") String artifactId,String path,long size,String sha256) {
        static Reference from(ArtifactProvenance input) {
            return new Reference(input.artifactId(),input.path(),input.size(),input.sha256());
        }
    }
    public record Change(String type,@JsonProperty("base_line") Integer baseLine,
            @JsonProperty("target_line") Integer targetLine,String text) { }
}
