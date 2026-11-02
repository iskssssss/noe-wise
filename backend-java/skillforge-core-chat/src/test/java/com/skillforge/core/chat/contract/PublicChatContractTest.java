package com.skillforge.core.chat.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 公开引用与业务结果只允许明确类型；传输不能退回原始调用 JSON。 */
class PublicChatContractTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void roundTripsEverySelectorAndTypedTableWithoutLosingKind() throws Exception {
        List<ChatInputReference> selectors=List.of(
                new ChatInputReference.ArtifactFiles(List.of(new ChatInputReference.ArtifactFile("artifact-1","report.csv"))),
                new ChatInputReference.DeliverableRevision("revision-1","revision_base"),
                new ChatInputReference.Message(new ChatInputReference.SessionSource(),"message-1",3),
                new ChatInputReference.Process(new ChatInputReference.WorkTaskSource("work-1","direct","task-1"),"process-1",7));
        var presentation=new ProcessPresentation("核验订单","业务 API 返回两条订单",
                List.of(new ProcessPresentation.Table(List.of(new ProcessPresentation.Column("order_id","订单编号"),
                        new ProcessPresentation.Column("amount","金额")),List.of(Arrays.asList("BIZ-001",26),Arrays.asList("BIZ-002",null)))),
                java.util.stream.IntStream.range(0,selectors.size()).mapToObj(index->new ChatReferenceView("材料 "+index,selectors.get(index))).toList());
        String encoded=json.writeValueAsString(presentation);
        assertEquals(presentation,json.readValue(encoded,ProcessPresentation.class));
        assertTrue(encoded.contains("\"kind\":\"work_task\""));
        assertTrue(encoded.contains("\"message_version\":3"));
        assertTrue(encoded.contains("\"updated_sequence\":7"));
        assertFalse(encoded.contains("detail"));
    }

    @Test void rejectsUnversionedSelectorsDuplicateFilesAndNonScalarTableCells() {
        assertThrows(IllegalArgumentException.class,()->new ChatInputReference.Message(new ChatInputReference.SessionSource(),"m",0));
        assertThrows(IllegalArgumentException.class,()->new ChatInputReference.Process(new ChatInputReference.SessionSource(),"p",0));
        var file=new ChatInputReference.ArtifactFile("a","x");
        assertThrows(IllegalArgumentException.class,()->new ChatInputReference.ArtifactFiles(List.of(file,file)));
        assertThrows(IllegalArgumentException.class,()->new ProcessPresentation.Table(
                List.of(new ProcessPresentation.Column("value","值")),List.of(List.of(java.util.Map.of("raw","json")))));
    }
}
