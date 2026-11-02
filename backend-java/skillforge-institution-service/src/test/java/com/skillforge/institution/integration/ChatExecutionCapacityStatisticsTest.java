package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.JTokkitSessionTokenCounter;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ModelPort.FrozenModelTarget;
import com.skillforge.runtime.api.port.ModelPort.ModelBindingRef;
import com.skillforge.runtime.api.port.ModelPort.ModelCapabilitySnapshot;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.api.port.ModelPort.ResponseFormatKind;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 仅核验统计与真实钉扎tokenizer，不启动Spring、数据库或HTTP服务。 */
class ChatExecutionCapacityStatisticsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void completedEventWithFailedStatusPreservesBudgetReason() {
        var capture = capture(List.of("execution.completed"),
                "event:execution.completed\ndata:{\"status\":\"failed\",\"reason\":\"context_budget_exceeded\"}\n\n");
        assertEquals(Optional.of("failed:context_budget_exceeded"), ChatExecutionCapacityE2ETest.terminalFailure(json, capture));
    }

    @Test void onlySuccessfulCompletedPayloadPasses() {
        assertTrue(ChatExecutionCapacityE2ETest.terminalFailure(json, capture(List.of("execution.completed"),
                "event:execution.completed\r\ndata:{\"status\":\"completed\"}\r\n\r\n")).isEmpty());
        assertEquals(Optional.of("error_event"), ChatExecutionCapacityE2ETest.terminalFailure(json, capture(List.of("error"), "event:error\ndata:{}\n\n")));
        assertEquals(Optional.of("terminal_missing"), ChatExecutionCapacityE2ETest.terminalFailure(json, capture(List.of(), ":heartbeat\n\n")));
        assertEquals(Optional.of("invalid_completed_payload"), ChatExecutionCapacityE2ETest.terminalFailure(json,
                capture(List.of("execution.completed"), "event:execution.completed\ndata:broken\n\n")));
    }

    @Test void unchangedHundredMessageHistoryAlreadyExceedsThirtyTwoKModelInputAllowance() {
        var messages = new ArrayList<ModelMessage>();
        for (int index = 0; index < 100; index++) messages.add(new ModelMessage("history-" + index,
                index % 2 == 0 ? ModelRole.USER : ModelRole.ASSISTANT, ChatExecutionCapacityE2ETest.longHistoryMessage(index)));
        assertEquals(100, messages.size());
        assertEquals(65536, messages.stream().mapToInt(message -> message.accountingText().getBytes(StandardCharsets.UTF_8).length).sum());
        var reference = new ResultRef("capacity-history-tokenizer");
        var target = new FrozenModelTarget(new ModelBindingRef(new TenantId("capacity-test"), "binding", 1), "runtime-fixture",
                new ModelCapabilitySnapshot(Set.of(ResponseFormatKind.TEXT), false, false, new ResultRef("capabilities")),
                Duration.ofSeconds(30), new ResultRef("resolution"));
        var profile = new SessionTokenCounter.Profile(reference, "v1", JTokkitSessionTokenCounter.TOKENIZER_VERSION,
                "cl100k_base", target, new SessionTokenCounter.RoleCosts(1, 2, 3), 3, 4, 100000, 32 * 1024 * 1024);
        var measured = new JTokkitSessionTokenCounter(ref -> Optional.of(profile)).measure(reference, target, messages);
        assertEquals(32200, measured.contentTokens(), "真实JTokkit对原64KiB历史的独立内容计量");
        long inputAllowance = 32768 - 1024 - (32768 * 5 + 99) / 100;
        assertEquals(30105, inputAllowance);
        assertTrue(measured.totalTokens() > inputAllowance, "尚未加入system、当前输入和tools即已超过硬预算");
        long longHistoryAllowance = 65536 - 1024 - (65536 * 5 + 99) / 100;
        assertEquals(61235, longHistoryAllowance);
        assertTrue(measured.totalTokens() < longHistoryAllowance, "独立64k场景保留原历史及5%安全量，完整请求仍由真实容量验收核对");
    }

    private static ChatRuntimeChannelE2ETest.SseCapture capture(List<String> events, String raw) {
        return new ChatRuntimeChannelE2ETest.SseCapture(200, events, raw, 1, 2, 3);
    }
}
