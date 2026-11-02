package com.skillforge.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 多任务、撤权和结果未知的持久序列；Fake只证明服务契约，不代表真实设备。 */
class BrowserComplexSequenceTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    @Test void seventyTwoPageCallsAcrossThreeTasksReplayWithoutAnyExtraDispatch() throws Exception {
        var engine = new BrowserEngineTest.Fake(root);
        var tasks = List.of(engine.execute(start("start-a")), engine.execute(start("start-b")), engine.execute(start("start-c")));
        for (int index = 0; index < 72; index++) {
            var request = navigate("row-" + index, tasks.get(index % 3).task(), index);
            var receipt = engine.execute(request);
            assertEquals("succeeded", receipt.status());
            int count = engine.calls.size(); assertEquals(receipt, engine.execute(request)); assertEquals(count, engine.calls.size());
        }
        assertEquals(72, engine.calls.stream().filter(args -> args.contains("navigate")).count());
        assertEquals(3, engine.taskStates("1", "2").size()); assertTrue(engine.taskStates("1", "3").isEmpty());
    }

    @Test void revocationRejectsNewEffectsButRetainsSeventyTwoOriginalReceipts() throws Exception {
        var engine = new BrowserEngineTest.Fake(root);
        var task = engine.execute(start("grant-start"));
        var requests = new java.util.ArrayList<BrowserRequest>();
        var receipts = new java.util.ArrayList<BrowserReceipt>();
        for (int index = 0; index < 72; index++) {
            var request = navigate("grant-row-" + index, task.task(), index);
            var receipt = engine.execute(request);
            assertEquals("succeeded", receipt.status());
            requests.add(request); receipts.add(receipt);
        }
        Files.writeString(engine.home("1", "2").resolve("remote-authorization.json"), "{\"devices\":{}}");
        int before = engine.calls.size();
        var denied = engine.execute(navigate("revoked-new", task.task(), 73));
        // BrowserSkill design section 2 specifies failed/browser_not_authorized for revoked grants.
        assertEquals("failed", denied.status());
        assertEquals("browser_not_authorized", denied.result().path("error").asText());
        for (int index = 0; index < 72; index++) assertEquals(receipts.get(index), engine.execute(requests.get(index)));
        assertEquals(before, engine.calls.size());
        assertEquals(74, engine.receipts("1", "2").size());
    }

    @Test void unknownCallSurvivesReconstructionAndConflictCannotOverwriteIt() throws Exception {
        var engine = new BrowserEngineTest.Fake(root);
        var task = engine.execute(start("unknown-start")); engine.loseNavigation = true;
        var request = navigate("lost-72-row", task.task(), 72);
        var unknown = engine.execute(request); assertEquals("unknown", unknown.status());
        var rebuilt = new BrowserEngineTest.Fake(root);
        assertEquals(unknown, rebuilt.execute(request)); assertTrue(rebuilt.calls.isEmpty());
        var conflict = rebuilt.execute(navigate("lost-72-row", task.task(), 73));
        assertEquals("rejected", conflict.status()); assertEquals("browser_invocation_conflict", conflict.result().path("error").asText());
        assertEquals(unknown, rebuilt.execute(request)); assertTrue(rebuilt.calls.isEmpty());
        assertEquals("succeeded", rebuilt.execute(navigate("explicit-new-read", task.task(), 73)).status());
        assertEquals(1, rebuilt.calls.stream().filter(args -> args.contains("navigate")).count());
    }

    private BrowserRequest start(String invocation) {
        return new BrowserRequest("1", "2", invocation, null, "start", new BrowserParameters.StartParameters("b1"), "owned-session", "owned-run", Instant.now().plusSeconds(120));
    }
    private BrowserRequest navigate(String invocation, String task, int row) throws Exception {
        return new BrowserRequest("1", "2", invocation, task, "navigate", BrowserParameters.parse("navigate",
                json.createObjectNode().put("url", "http://127.0.0.1:18581/records?row=" + row)), "owned-session", "owned-run", Instant.now().plusSeconds(120));
    }
}
