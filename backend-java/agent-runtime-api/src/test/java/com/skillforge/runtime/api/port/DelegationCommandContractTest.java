package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 原生委派命令只接受目录句柄和业务输入，不接纳执行配置或预算。 */
class DelegationCommandContractTest {
    /** 单段指令只负责业务内容，后台开关仅改变父等待方式。 */
    @Test void ordinaryDelegateNormalizesInstructionWithoutChildConfiguration() {
        var command = DelegationCommand.delegate("member:reviewer", "核对业务结果", false);
        assertEquals(false, command.background());
        assertEquals(new DelegationCommand.Task("核对业务结果", "", List.of(), ""), command.task().orElseThrow());
        assertEquals(true, DelegationCommand.delegate("member:reviewer", "并行检查", true).background());
        assertThrows(IllegalArgumentException.class, () -> DelegationCommand.delegate("member:reviewer", " ", false));
        assertThrows(IllegalArgumentException.class, () -> new DelegationCommand(
                DelegationCommand.Operation.WAIT, "", Optional.empty(), List.of("d1"), "", false));
    }

    private static final DelegationCommand.Task TASK =
            new DelegationCommand.Task("inspect", "context",
                    List.of("cite evidence"), "report");
    @Test
    void acceptsEveryClosedOperationShape() {
        assertEquals(DelegationCommand.Operation.START,
                DelegationCommand.start("member:reviewer", TASK).operation());
        for (DelegationCommand command : List.of(
                command(DelegationCommand.Operation.TARGETS, List.of(), ""),
                command(DelegationCommand.Operation.LIST, List.of(), ""),
                command(DelegationCommand.Operation.GET, List.of("d1"), ""),
                command(DelegationCommand.Operation.SEND, List.of("d1"),
                        "continue"),
                command(DelegationCommand.Operation.WAIT,
                        List.of("d1", "d2"), ""),
                command(DelegationCommand.Operation.CANCEL, List.of("d1"),
                        ""))) {
            assertEquals("DelegationCommand[" + command.operation() + "]",
                    command.toString());
        }
    }

    @Test
    void rejectsFieldsOutsideTheSelectedOperation() {
        assertThrows(IllegalArgumentException.class, () -> new DelegationCommand(
                DelegationCommand.Operation.START, "", Optional.of(TASK), List.of(), "", true));
        assertThrows(IllegalArgumentException.class, () -> new DelegationCommand(
                DelegationCommand.Operation.START, "member:reviewer",
                Optional.empty(), List.of(), "", true));
        assertThrows(IllegalArgumentException.class, () -> new DelegationCommand(
                DelegationCommand.Operation.LIST, "member:reviewer",
                Optional.empty(), List.of(), "", true));
        assertThrows(IllegalArgumentException.class, () -> command(
                DelegationCommand.Operation.GET, List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> command(
                DelegationCommand.Operation.SEND, List.of("d1"), " "));
        assertThrows(IllegalArgumentException.class, () -> command(
                DelegationCommand.Operation.WAIT, List.of(), ""));
        assertThrows(IllegalArgumentException.class, () -> command(
                DelegationCommand.Operation.CANCEL, List.of("d1", "d2"), ""));
        assertThrows(IllegalArgumentException.class, () -> command(
                DelegationCommand.Operation.WAIT, List.of("d1", "d1"), ""));
        assertThrows(IllegalArgumentException.class, () ->
                new DelegationCommand.Task("inspect", "context",
                        List.of("same", "same"), "report"));
    }

    @Test
    void defensivelyCopiesTaskAndDelegationIdentifiers() {
        var constraints = new ArrayList<>(List.of("read only"));
        var task = new DelegationCommand.Task("inspect", null, constraints,
                "report");
        constraints.add("changed");
        assertEquals("", task.context());
        assertEquals(List.of("read only"), task.constraints());
        assertEquals("DelegatedTask[REDACTED]", task.toString());

        var ids = new ArrayList<>(List.of("d1"));
        var command = command(DelegationCommand.Operation.GET, ids, "");
        ids.add("d2");
        assertEquals(List.of("d1"), command.delegationIds());

    }

    private static DelegationCommand command(DelegationCommand.Operation operation,
            List<String> ids, String message) {
        return new DelegationCommand(operation, "", Optional.empty(), ids,
                message, true);
    }
}
