package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArtifactVersionServiceTest {
    private static final String BASE="11111111-1111-1111-1111-111111111111";
    private static final String TARGET="22222222-2222-2222-2222-222222222222";
    private final ArtifactStorage storage=mock(ArtifactStorage.class);
    private final ArtifactAccess access=mock(ArtifactAccess.class);
    private final ArtifactVersionService service=new ArtifactVersionService(storage,access);

    @Test void comparesStrictUtf8LogicalLinesAndTreatsCrLfAsLf()throws Exception {
        authorizeSameSession();
        when(storage.verifiedFile(any(),eq("report.txt"),eq(ArtifactVersionService.MAX_DIFF_BYTES)))
                .thenReturn(file(BASE,"report.txt","same\r\nold\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        file(TARGET,"report.txt","same\nnew\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        var result=service.diff(input(BASE),input(TARGET));

        assertEquals("comparable",result.status());
        assertEquals(BASE,result.base().artifactId());assertEquals(TARGET,result.target().artifactId());
        assertEquals(List.of("unchanged","added","removed"),result.changes().stream()
                .map(ArtifactVersionService.Change::type).toList());
        assertEquals(List.of("same","new","old"),result.changes().stream()
                .map(ArtifactVersionService.Change::text).toList());
        verify(access,times(4)).authorizeArtifactCurrent(eq("1"),any());
    }

    @Test void definesEmptyBinarySizeAndLineLimitsWithoutReplacement()throws Exception {
        authorizeSameSession();
        when(storage.verifiedFile(any(),eq("report.txt"),anyInt()))
                .thenReturn(file(BASE,"report.txt",new byte[0]),file(TARGET,"report.txt",new byte[0]));
        var empty=service.diff(input(BASE),input(TARGET));
        assertEquals("comparable",empty.status());assertEquals(List.of(),empty.changes());

        reset(storage,access);authorizeSameSession();
        when(storage.verifiedFile(any(),eq("report.txt"),anyInt()))
                .thenReturn(file(BASE,"report.txt",new byte[]{(byte)0xc3,(byte)0x28}),
                        file(TARGET,"report.txt","valid".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("binary",service.diff(input(BASE),input(TARGET)).status());

        reset(storage,access);authorizeSameSession();
        when(storage.verifiedFile(any(),eq("report.txt"),anyInt()))
                .thenReturn(new ArtifactStorage.VerifiedFile(provenance(BASE,"report.txt",ArtifactVersionService.MAX_DIFF_BYTES+1),null),
                        file(TARGET,"report.txt","valid".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("too_large",service.diff(input(BASE),input(TARGET)).status());

        reset(storage,access);authorizeSameSession();
        byte[] lines=("line\n".repeat(ArtifactVersionService.MAX_DIFF_LINES+1)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(storage.verifiedFile(any(),eq("report.txt"),anyInt()))
                .thenReturn(file(BASE,"report.txt",lines),file(TARGET,"report.txt",new byte[0]));
        assertEquals("too_many_lines",service.diff(input(BASE),input(TARGET)).status());
    }

    @Test void recoveryOnlyReportsValidatedInputsAndRechecksAuthorization()throws Exception {
        var input=input(BASE);var verified=provenance(BASE,"report.txt",7);
        when(access.tenant()).thenReturn("1");
        var row=row(BASE,"child");
        when(storage.require("1",BASE)).thenReturn(row);
        when(access.authorizeArtifactCurrent("1",row)).thenReturn("session");
        when(storage.verifiedFile(row,"report.txt",0)).thenReturn(
                new ArtifactStorage.VerifiedFile(verified,null));

        var result=service.recovery("session",List.of(input));

        assertEquals("validated",result.status());assertEquals(List.of(verified),result.artifactInputs());
        verify(access,times(2)).authorizeCurrent("1","session");
    }

    @Test void recoveryFailsClosedWhenSessionAccessIsRevokedAfterFileValidation()throws Exception {
        var input=input(BASE);
        when(access.tenant()).thenReturn("1");
        var row=row(BASE,"child");
        when(storage.require("1",BASE)).thenReturn(row);
        when(access.authorizeArtifactCurrent("1",row)).thenReturn("session");
        when(storage.verifiedFile(row,"report.txt",0)).thenReturn(
                new ArtifactStorage.VerifiedFile(provenance(BASE,"report.txt",7),null));
        doNothing().doThrow(ArtifactStorage.unavailable()).when(access).authorizeCurrent("1","session");

        assertThrows(RuntimeException.class,()->service.recovery("session",List.of(input)));

        verify(access,never()).auditCurrent(anyString(),anyString(),anyString(),anyString(),anyMap());
    }

    @Test void refusesCrossSessionComparisonBeforeReadingFiles() throws Exception {
        when(access.tenant()).thenReturn("1");
        when(storage.require("1",BASE)).thenReturn(row(BASE,"first"));
        when(storage.require("1",TARGET)).thenReturn(row(TARGET,"second"));
        when(access.authorizeArtifactCurrent(eq("1"),any())).thenReturn("first","second");
        assertThrows(RuntimeException.class,()->service.diff(input(BASE),input(TARGET)));
        verify(storage,never()).verifiedFile(any(),anyString(),anyInt());
    }

    private void authorizeSameSession() {
        when(access.tenant()).thenReturn("1");
        when(storage.require("1",BASE)).thenReturn(row(BASE,"session"));
        when(storage.require("1",TARGET)).thenReturn(row(TARGET,"session"));
        when(access.authorizeArtifactCurrent(eq("1"),any())).thenReturn("session");
    }
    private static ArtifactInput input(String id){return new ArtifactInput(id,"report.txt");}
    private static ArtifactRow row(String id,String session) {
        return new ArtifactRow(id,"1",session,"run","invocation","file","report.txt",0,"0".repeat(64),"[]",
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
                Instant.now(),Instant.now().plusSeconds(60),"ready");
    }
    private static ArtifactStorage.VerifiedFile file(String id,String path,byte[] content) {
        return new ArtifactStorage.VerifiedFile(provenance(id,path,content.length),content);
    }
    private static ArtifactProvenance provenance(String id,String path,long size) {
        return new ArtifactProvenance(id,path,size,"0".repeat(64));
    }
}
