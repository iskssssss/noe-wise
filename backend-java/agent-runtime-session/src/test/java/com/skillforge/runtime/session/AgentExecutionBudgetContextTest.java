package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentExecutionBudgetContextTest {
    @Test void restoresExactFrozenObservationWithoutReadingOrRecalculatingLedger() {
        var user = new ModelMessage("user", ModelRole.USER, "继续");
        var old = new ModelMessage("execution-budget:1", ModelRole.USER, "旧余额");
        var frozen = new ModelMessage("execution-budget:2", ModelRole.USER, "原请求采样值");
        assertEquals(List.of(user, frozen), AgentExecutionBudgetContext.restore(List.of(user, old), List.of(user, frozen), 2));
        assertEquals(List.of(user), AgentExecutionBudgetContext.restore(List.of(user, old), List.of(user), 2));
    }

    @Test void rejectsWrongOrdinalRoleAndDuplicateFrozenObservations() {
        var frozen = new ModelMessage("execution-budget:2", ModelRole.USER, "原请求采样值");
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionBudgetContext.restore(List.of(), List.of(frozen), 3));
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionBudgetContext.restore(List.of(), List.of(frozen, frozen), 2));
        var wrongRole = new ModelMessage("execution-budget:2", ModelRole.SYSTEM, "旧角色文本");
        assertThrows(IllegalArgumentException.class, () -> AgentExecutionBudgetContext.restore(List.of(), List.of(wrongRole), 2));
    }

    @Test void exposesTighterBalancesAfterConsumptionAndUnknownReservationsWithoutGrantingPermission() {
        var snapshot = snapshot(new Policy.Enforced(new Limits(Scenario.STANDARD,
                resources(20, 30, 40, 1000), resources(15, 25, 35, 900), Resources.ZERO,
                100, 80, "v1")), Phase.BUSINESS);
        var messages = AgentExecutionBudgetContext.messages(List.of(), Optional.of(snapshot), 7);
        assertEquals("execution-budget:7", messages.getFirst().messageId());
        assertEquals(ModelRole.USER, messages.getFirst().role());
        var text = messages.getFirst().textContent();
        assertTrue(text.contains("账本余额：业务模型 9 次，工具 14 次，知识 17 次，总 Token 600。"));
        assertTrue(text.contains("当前账本收尾保护：业务模型 2 次，工具 1 次，知识 0 次，总 Token 100。"));
        assertTrue(text.contains("扣除当前收尾保护后可用于业务的额度：业务模型 7 次，工具 13 次，知识 17 次，总 Token 500。"));
        assertTrue(text.contains("活跃剩余时间：60000 毫秒"));
        assertTrue(text.contains("业务可用时间：55000 毫秒"));
        assertTrue(text.contains("本次请求预留前的账本快照"));
        assertTrue(text.contains("本次输入/输出还会消耗额度"));
        assertTrue(text.contains("收尾保护会随实际请求重新计量"));
        assertTrue(text.contains("可用量不代表调用许可"));
        assertTrue(text.contains("UNKNOWN"));
        assertFalse(text.contains("private-run"));
    }

    @Test void refreshReplacesOwnMessageAndPreservesConversationOrderAndOriginalList() {
        var user = new ModelMessage("user", ModelRole.USER, "继续");
        var other = new ModelMessage("other", ModelRole.SYSTEM, "其它约束");
        var original = List.of(user, new ModelMessage("execution-budget:1", ModelRole.USER, "旧余额"), other);
        var observed = snapshot(new Policy.ObserveOnly(Scenario.STANDARD, "v1"), Phase.BUSINESS);
        var refreshed = AgentExecutionBudgetContext.messages(original, Optional.of(observed), 2);
        assertEquals(List.of(user, other), refreshed.subList(0, 2));
        assertEquals(3, original.size());
        assertEquals("execution-budget:2", refreshed.getLast().messageId());
        assertEquals(List.of(user, other), AgentExecutionBudgetContext.messages(original, Optional.empty(), 3));
    }

    @Test void observeOnlyDoesNotInventUnlimitedOrZeroBalances() {
        var observed = snapshot(new Policy.ObserveOnly(Scenario.STANDARD, "v1"), Phase.BUSINESS);
        var text = AgentExecutionBudgetContext.messages(List.of(), Optional.of(observed), 1).getFirst().textContent();
        assertTrue(text.contains("仅计量，未设置数值限额"));
        assertTrue(text.contains("剩余额度未定义"));
        assertFalse(text.contains("账本余额："));
        assertFalse(text.contains("无限"));
        assertFalse(text.contains("业务模型 0 次"));
    }

    @Test void exhaustedBalancesClampToZeroAndClosingDoesNotInviteBusinessCalls() {
        var exhausted = snapshot(new Policy.Enforced(new Limits(Scenario.STANDARD,
                resources(1, 1, 1, 10), resources(1, 1, 1, 10), Resources.ZERO,
                10, 10, "v1")), Phase.CLOSING);
        var text = AgentExecutionBudgetContext.messages(List.of(), Optional.of(exhausted), 1).getFirst().textContent();
        assertTrue(text.contains("账本余额：业务模型 0 次，工具 0 次，知识 0 次，总 Token 0。"));
        assertTrue(text.contains("活跃剩余时间：0 毫秒"));
        assertTrue(text.contains("不表示可以继续业务调用"));
    }

    private static Snapshot snapshot(Policy policy, Phase phase) {
        var run = new RunRef(new TenantId("tenant"), "private-run");
        return new Snapshot(new Registration(run, run, Role.PRIMARY, policy),
                resources(8, 10, 20, 200), resources(3, 6, 3, 100),
                resources(4, 5, 5, 200), resources(2, 3, 3, 100),
                new Closing(resources(2, 1, 0, 100), 5), phase, 40000, 10000, 0);
    }

    private static Resources resources(long models, long tools, long knowledge, long tokens) {
        return new Resources(models, 0, tools, knowledge, tokens, tokens, tokens);
    }
}
