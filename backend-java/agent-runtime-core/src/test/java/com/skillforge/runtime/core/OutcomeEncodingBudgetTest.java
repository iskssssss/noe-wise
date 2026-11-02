package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 单独校验分配前额度，不依赖 codec 在编码后的 parse 再次拒绝而获得假绿。 */
class OutcomeEncodingBudgetTest {
    /** literal true/false 成本不同，预检不能把布尔值当字符串或只覆盖 true。 */
    @Test
    void booleanLiteralsHaveExactLengthAndNodeCosts() {
        new OutcomeEncodingBudget(new CodecLimits(4, 1, 1)).bool(1, true);
        new OutcomeEncodingBudget(new CodecLimits(5, 1, 1)).bool(1, false);
        assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(3, 1, 1)).bool(1, true));
        assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(4, 1, 1)).bool(1, false));
        var budget = new OutcomeEncodingBudget(new CodecLimits(100, 1, 1));
        budget.bool(1, false);
        assertThrows(IllegalArgumentException.class, () -> budget.bool(1, true));
    }

    /** 超大集合在读取元素前仅凭 size 拒绝；不会先 map 或生成任何 JSON 元素。 */
    @Test
    void rejectsCollectionBeforeElementTraversal() {
        var budget = new OutcomeEncodingBudget(new CodecLimits(Integer.MAX_VALUE, 10, 128));
        assertThrows(IllegalArgumentException.class, () -> budget.array(1, Integer.MAX_VALUE));
        var exact = new OutcomeEncodingBudget(new CodecLimits(5, 3, 2));
        exact.array(1, 1);
        exact.string(2, "x");
        assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(100, 10, 1)).array(1, 1));
    }

    /** 未配对 surrogate 直接拒绝，合法 pair 必须按两个 code units 而非一个 code point 计量。 */
    @Test
    void unicodeAndEscapeLimitsAreCheckedWithoutEncoding() {
        for (String bad : new String[]{"\ud800", "\udfff", "\ud800x"}) {
            assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(100, 10, 3)).string(1, bad));
        }
        new OutcomeEncodingBudget(new CodecLimits(4, 1, 1)).string(1, "🌍");
        assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(3, 1, 1)).string(1, "🌍"));
        new OutcomeEncodingBudget(new CodecLimits(8, 1, 1)).string(1, "\u0000");
        assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(7, 1, 1)).string(1, "\u0000"));
    }

    /** 空容器和字段名仍计成本，字段名不额外占子值深度。 */
    @Test
    void emptyContainersNamesAndNullMatchParserRules() {
        new OutcomeEncodingBudget(new CodecLimits(2, 1, 1)).object(1);
        new OutcomeEncodingBudget(new CodecLimits(2, 1, 1)).array(1, 0);
        var budget = new OutcomeEncodingBudget(new CodecLimits(10, 3, 2));
        budget.object(1, "x");
        budget.nil(2);
        CanonicalJson.parse("{\"x\":null}", new CodecLimits(10, 3, 2));
        assertThrows(IllegalArgumentException.class, () -> new OutcomeEncodingBudget(new CodecLimits(100, 1, 2)).object(1, "x"));
    }
}
