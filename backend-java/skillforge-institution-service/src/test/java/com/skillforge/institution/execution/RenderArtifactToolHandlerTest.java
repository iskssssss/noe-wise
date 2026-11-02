package com.skillforge.institution.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatReferenceView;
import com.skillforge.core.chat.contract.ProcessPresentation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

class RenderArtifactToolHandlerTest {

    @Test
    void reportsOnlyCollectedRenderFactsAndPreservesExistingPresentation() throws Exception {
        var execution = mock(CodeExecutionToolHandler.class);
        var files = List.of(new ChatInputReference.ArtifactFile("render-id", "pages/page-1.png"),
                new ChatInputReference.ArtifactFile("render-id", "pages/page-2.png"));
        var presentation = new ProcessPresentation("title", "summary",
                List.of(new ProcessPresentation.Text("plain", "existing result")),
                List.of(new ChatReferenceView("pages", new ChatInputReference.ArtifactFiles(files))));
        Map<String,Object> ready = Map.of("success", true, "delivery_status", "ready",
                "artifacts", List.of(Map.of("id", "render-id", "paths",
                        List.of("pages/page-1.png", "pages/page-2.png", "other.png"))),
                "public_presentation", presentation, "content", List.of(Map.of("type", "text", "text", "existing result")));
        when(execution.executeForRender(any(), isNull())).thenReturn(ready);
        var handler = new RenderArtifactToolHandler(execution, new ObjectMapper());
        var arguments = Map.<String,Object>of("artifact_id", "12345678-1234-1234-1234-123456789abc",
                "path", "deck.pptx", "format", "pages");
        var result = handler.execute(arguments, null);
        var enriched = (ProcessPresentation) result.get("public_presentation");
        assertEquals(presentation.references(), enriched.references());
        assertEquals(presentation.results().getFirst(), enriched.results().getFirst());
        assertEquals(2, enriched.results().size());
        String facts = ((ProcessPresentation.Text) enriched.results().getLast()).text();
        org.junit.jupiter.api.Assertions.assertTrue(facts.contains("2 张候选页图"));
        org.junit.jupiter.api.Assertions.assertTrue(facts.contains("未执行视觉审阅、元素重叠检测或页面安全区检查"));
        org.junit.jupiter.api.Assertions.assertTrue(facts.contains("不代表已选为最终交付或已完成发布"));
        assertEquals(ready.get("artifacts"), result.get("artifacts"));
        assertEquals(2, ((List<?>) result.get("content")).size());
        for (var override : List.of(Map.<String,Object>of("success", false),
                Map.<String,Object>of("delivery_status", "failed"),
                Map.<String,Object>of("artifacts", List.of(Map.of("id", "render-id", "paths", List.of("other.png")))))) {
            var incomplete = new java.util.LinkedHashMap<>(ready);
            incomplete.putAll(override);
            when(execution.executeForRender(any(), isNull())).thenReturn(incomplete);
            assertEquals(incomplete, handler.execute(arguments, null));
        }
    }

    @Test
    void delegatesOnlyFixedRendererAndTrustedArtifactInput() throws Exception {
        CodeExecutionToolHandler execution = mock(CodeExecutionToolHandler.class);
        when(execution.executeForRender(any(), isNull())).thenReturn(Map.of("success", true));
        RenderArtifactToolHandler handler = new RenderArtifactToolHandler(execution, new ObjectMapper());
        org.junit.jupiter.api.Assertions.assertInstanceOf(
                com.skillforge.core.capability.connector.PlatformMediaToolHandler.class, handler);

        Map<String, Object> result = handler.execute(Map.of(
                "artifact_id", "12345678-1234-1234-1234-123456789abc",
                "path", "reports/weekly.xlsx", "format", "table"), null);

        assertEquals(Map.of("success", true), result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(execution).executeForRender(captor.capture(), isNull());
        Map<String, Object> internal = captor.getValue();
        assertEquals("inline", internal.get("mode"));
        assertEquals("python", internal.get("language"));
        assertEquals(List.of(), internal.get("dependencies"));
        assertEquals(List.of(Map.of("artifact_id", "12345678-1234-1234-1234-123456789abc",
                "path", "reports/weekly.xlsx")), internal.get("artifact_inputs"));
        String trustedRenderer = new ClassPathResource("renderer/render_artifact.py")
                .getContentAsString(StandardCharsets.UTF_8);
        assertEquals(trustedRenderer + "\nmain(\"table\", \"index.html\")\n", internal.get("code"));
    }

    @Test
    void namedPdfIsGeneratedAndCollectedAtTheRequestedActualBasename() throws Exception {
        var execution=mock(CodeExecutionToolHandler.class);
        when(execution.executeForRender(any(),isNull())).thenReturn(Map.of("success",true));
        var handler=new RenderArtifactToolHandler(execution,new ObjectMapper());
        handler.execute(Map.of("artifact_id","12345678-1234-1234-1234-123456789abc","path","document.docx",
                "format","pdf","output_name","expense-review.pdf"),null);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> args=ArgumentCaptor.forClass(Map.class);
        verify(execution).executeForRender(args.capture(),isNull());
        assertEquals(List.of("expense-review.pdf"),args.getValue().get("output_files"));
        org.junit.jupiter.api.Assertions.assertTrue(args.getValue().get("code").toString().endsWith("main(\"pdf\", \"expense-review.pdf\")\n"));
    }

    @Test
    void rejectsUnsafeMismatchedOrCollectionOutputNamesBeforeExecution() throws Exception {
        var execution=mock(CodeExecutionToolHandler.class);
        var handler=new RenderArtifactToolHandler(execution,new ObjectMapper());
        for(String name:List.of("../report.pdf","a/b.pdf","report.docx","CON.pdf","report.pdf\n","Ａ.pdf")) {
            assertEquals(false,handler.execute(Map.of("artifact_id","12345678-1234-1234-1234-123456789abc",
                    "path","document.docx","format","pdf","output_name",name),null).get("success"));
        }
        assertEquals(false,handler.execute(Map.of("artifact_id","12345678-1234-1234-1234-123456789abc",
                "path","document.docx","format","pages","output_name","page.png"),null).get("success"));
        verifyNoInteractions(execution);
    }

    @Test
    void rejectsUntrustedArgumentsBeforeDelegating() throws Exception {
        CodeExecutionToolHandler execution = mock(CodeExecutionToolHandler.class);
        RenderArtifactToolHandler handler = new RenderArtifactToolHandler(execution, new ObjectMapper());

        for (Map<String, Object> invalid : List.<Map<String, Object>>of(
                Map.of(),
                Map.of("artifact_id", "12345678-1234-1234-1234-123456789abc", "path", "a.pdf", "format", "zip"),
                Map.of("artifact_id", "12345678-1234-1234-1234-123456789abc", "path", "/tmp/a.pdf", "format", "pdf"),
                Map.of("artifact_id", "12345678-1234-1234-1234-123456789abc", "path", "a.pdf", "format", "pdf", "code", "x"))) {
            assertEquals(Map.of("success", false, "error", "render_artifact_arguments_rejected"),
                    handler.execute(invalid, null));
        }
        verifyNoInteractions(execution);
    }
}
