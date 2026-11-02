package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** 公开文件传输在正式发布和当前授权通过前不写任何字节。 */
class DeliverableDownloadServiceTest {
    private final DeliverableReader reader=mock(DeliverableReader.class);
    private final ArtifactAccess access=mock(ArtifactAccess.class);
    private final ArtifactStorage storage=mock(ArtifactStorage.class);
    private final DeliverableDownloadService service=new DeliverableDownloadService(reader,access,storage);

    @Test void unpublishedFileNeverReachesStorage() throws Exception {
        when(access.tenant()).thenReturn("7");when(reader.list("s",true)).thenReturn(List.of());
        assertThrows(RuntimeException.class,()->service.transfer("s","rev","file",value->{},new ByteArrayOutputStream()));
        verifyNoInteractions(storage);verify(reader,never()).file(any(),any(),any());
    }
    @Test void exactPublishedFileRechecksArtifactAndInspectionBeforeWriting() throws Exception {
        when(access.tenant()).thenReturn("7");var artifact=mock(ArtifactRow.class);
        var view=new DeliverableView("rev","deliverable",1,true,List.of("run"),"s","run","file","成果",4,"hash",null,
                "ready",List.of(new DeliverableView.File("file","artifact","report.txt","报告.txt",4,"hash","txt",null,false,false)),List.of(),List.of());
        when(reader.list("s",true)).thenReturn(List.of(view));when(reader.file("7","rev","file"))
                .thenReturn(new DeliverableReader.FileGrant("rev","file","s",artifact,"report.txt","报告.txt","txt",null,null,null));
        var output=new ByteArrayOutputStream();var headers=mock(Consumer.class);
        service.transfer("s","rev","file",headers,output);
        var ordered=inOrder(access,storage,headers);
        ordered.verify(access).authorizeCurrent("7","s");ordered.verify(access).authorizeArtifactCurrent("7",artifact);
        ordered.verify(storage).requirePublicInspection(artifact);
        ordered.verify(access).auditCurrent(eq("7"),eq("deliverable.download"),any(),eq("下载正式成果文件"),any());
        ordered.verify(headers).accept(new DeliverableDownloadService.PublishedFile("报告.txt","txt"));
        ordered.verify(storage).file(artifact,"report.txt",output);
    }
}
