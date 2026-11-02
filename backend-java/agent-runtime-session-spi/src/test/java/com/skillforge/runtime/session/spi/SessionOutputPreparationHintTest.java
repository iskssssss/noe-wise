package com.skillforge.runtime.session.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** 输出准备的固定规则与 Run 事实必须完整，调试展示不能泄露宿主上下文。 */
class SessionOutputPreparationHintTest {
    @Test void preservesSeparatedInstructionsAndRedactsDiagnosticRepresentation() {
        var hint = new SessionOutputContract.PreparationHint("固定成果规则", "最终正文规则", "当前输入 ID：private-input");
        assertEquals("固定成果规则", hint.stableInstructions());
        assertEquals("最终正文规则", hint.finalAnswerInstructions());
        assertEquals("当前输入 ID：private-input", hint.runContext());
        assertEquals("OutputPreparationHint[REDACTED]", hint.toString());
    }

    @Test void rejectsIncompleteHostOutputInstructions() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionOutputContract.PreparationHint(" ", "最终规则", "当前输入"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionOutputContract.PreparationHint("固定规则", "最终规则", " "));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionOutputContract.PreparationHint(null, "最终规则", "当前输入"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionOutputContract.PreparationHint("固定规则", "最终规则", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionOutputContract.PreparationHint("固定规则", " ", "当前输入"));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionOutputContract.PreparationHint("固定规则", null, "当前输入"));
    }
}
