package com.skillforge.execution.browser;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 固定上游错误保留可用于决策的事实，同时隔离自由文本和未知扩展。 */
class BrowserFailureTest {
    private final ObjectMapper json = new ObjectMapper();

    /** 原因和效果状态应可传输，错误文本、URL 及扩展 data 不属于公开投影。 */
    @Test void projectsOnlyRecognizedFactsWithoutCopyingErrorText() throws Exception {
        var failure = BrowserFailure.fromCli("permission_denied", json.readTree("""
                {"message":"private page text","hint":"token=private","data":{
                  "reason":"element_not_visible","effect_state":"none","cleanup_state":"complete",
                  "url":"https://user:password@example.invalid/?token=private"}}
                """));
        assertEquals(json.readTree("""
                {"error":"permission_denied","reason":"element_not_visible",
                 "effect_state":"none","cleanup_state":"complete"}
                """), json.valueToTree(failure));
        assertFalse(failure.unknownEffect());
        var restored = json.readValue(json.writeValueAsBytes(failure), BrowserFailure.class);
        assertEquals(failure, restored);
    }

    /** 未识别值不能通过默认值或原样回显获得可信诊断身份。 */
    @Test void omitsUnknownFactsWithoutInventingDefaults() throws Exception {
        var failure = BrowserFailure.fromCli("cdp_failed", json.readTree("""
                {"data":{"reason":"private_token","effect_state":true,"cleanup_state":"maybe"}}
                """));
        assertEquals(json.readTree("{\"error\":\"cdp_failed\"}"), json.valueToTree(failure));
        assertNull(failure.effectState());
    }

    /** 明确的未知原因即使未附 effect_state，也不能推定动作未执行。 */
    @Test void retainsUnknownEffectsIndependentlyOfTopLevelErrorCode() throws Exception {
        assertTrue(BrowserFailure.fromCli("cdp_failed", json.readTree(
                "{\"data\":{\"reason\":\"input_outcome_unknown\"}}")).unknownEffect());
        assertTrue(BrowserFailure.fromCli("permission_denied", json.readTree(
                "{\"data\":{\"effect_state\":\"unknown\"}}")).unknownEffect());
        assertTrue(BrowserFailure.fromCli("timeout", json.readTree(
                "{\"data\":{\"reason\":\"input_not_ready\",\"effect_state\":\"none\"}}")).unknownEffect());
        assertTrue(BrowserFailure.fromCli("protocol_error", json.readTree("{}")).unknownEffect());
    }
}
