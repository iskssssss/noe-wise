package com.skillforge.institution.runtime;

import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicOutputGuardTest {
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(new TenantId("7"),
            "session:source"), new AgentId("101"), new EndUserId(8));

    @Test void ordinaryTechnicalIdentifiersAndExamplesArePublicButHostPathsAreNot() {
        var scope = new PublicOutputGuard("D:/private/artifacts").capture(OWNER);
        assertFalse(scope.rejects("run_id: 101，schema input.v1，tool:code_execute，session:source"));
        assertFalse(scope.rejects("订单 UUID a025596e-167a-4c25-a214-f610874c545f；password=example；token=sample"));
        assertTrue(scope.rejects("文件 D:\\private\\artifacts\\report.txt"));
        assertTrue(scope.rejects("Ｄ：/ＰＲＩＶＡＴＥ/ＡＲＴＩＦＡＣＴＳ/report.txt"));
    }

    @Test void explicitSensitiveValuesRemainCheckedWithoutIdentifierHeuristics() {
        var scope = new PublicOutputGuard.Scope(List.of("actual-private-ticket"));
        assertTrue(scope.rejects("下载凭据 actual-private-ticket"));
        assertFalse(scope.rejects("Run/Action/event/schema 均是普通技术术语"));
        assertTrue(scope.inspectionOverlapCharacters() > "actual-private-ticket".length());
    }

    @Test void explicitCredentialsAreRejectedWithoutBlockingTechnicalDocumentation() {
        var scope = new PublicOutputGuard.Scope(List.of());
        for (String credential : List.of("Authorization: Bearer real-access-credential", "\"Authorization\": \"Basic dXNlcjpwYXNz\"",
                "password=actual-private-password", "access_token: ch_private_123", "\"channel_token\":\"credential-123\"",
                "\"accessToken\":\"camel-case-credential\"",
                "-----BEGIN RSA PRIVATE KEY-----\nencoded\n-----END RSA PRIVATE KEY-----",
                "Ａｕｔｈｏｒｉｚａｔｉｏｎ： Ｂｅａｒｅｒ private-value")) {
            assertTrue(scope.rejects(credential), credential);
        }
        for (String documentation : List.of("Authorization 请求头采用 Bearer 协议，password 字段是字符串。",
                "password=example；token=sample", "Authorization: Bearer <TOKEN>", "access_token=${ACCESS_TOKEN}",
                "password=YOUR_PASSWORD", "token_count=7500; max_tokens=1000", "普通 UUID a025596e-167a-4c25-a214-f610874c545f")) {
            assertFalse(scope.rejects(documentation), documentation);
        }
    }

    @Test void canonicalScopeDigestIgnoresOrderAndHandlesNormalizedPaths() {
        var first = new PublicOutputGuard.Scope(List.of("D:\\private\\result", "private-ticket", "private-ticket"));
        var second = new PublicOutputGuard.Scope(List.of("PRIVATE-TICKET", "d:/private/result"));
        assertEquals(first.digest(), second.digest());
        assertTrue(first.rejects("Ｄ：/ＰＲＩＶＡＴＥ/ＲＥＳＵＬＴ"));
        assertNotEquals(first.digest(), first.including(List.of("another-private-ticket")).digest());
    }

    @Test void streamingKeepsRawAndNormalizedTailsWithoutSplittingSurrogates() {
        var scope = new PublicOutputGuard.Scope(List.of());
        String text = "公开经营结论😀，继续说明。\n".repeat(35);
        int previous = 0;
        for (int end = 1; end <= text.length(); end++) {
            String accumulated = text.substring(0, end);
            var result = scope.streamingContent(accumulated);
            assertTrue(result.rejection().isEmpty());
            int safe = result.safePrefixCharacters();
            assertTrue(safe >= previous, "安全前缀必须单调推进");
            assertTrue(end - safe >= Math.min(end, scope.inspectionOverlapCharacters()));
            if (safe > 0) assertFalse(Character.isHighSurrogate(accumulated.charAt(safe - 1)));
            previous = safe;
        }
        assertTrue(previous > 0);
    }

    @Test void knownPrivateValuesStayHiddenAcrossNfkcAndDecomposedSplits() {
        String prefix = "公开的经营说明。\n".repeat(80);
        for (var fixture : List.of(
                List.of("d:/private/artifacts", "Ｄ：/ＰＲＩＶＡＴＥ/ＡＲＴＩＦＡＣＴＳ"),
                List.of("é".repeat(200), "e\u0301".repeat(200)))) {
            var scope = new PublicOutputGuard.Scope(List.of(fixture.get(0)));
            String value = fixture.get(1);
            int previous = 0;
            for (int end = 0; end < value.length(); end++) {
                var result = scope.streamingContent(prefix + value.substring(0, end));
                assertTrue(result.rejection().isEmpty());
                assertTrue(result.safePrefixCharacters() >= previous);
                assertTrue(result.safePrefixCharacters() <= prefix.length(), "私有值的任何原文片段都不能提前公开");
                previous = result.safePrefixCharacters();
            }
            assertEquals(PublicOutputGuard.REJECTION_CODE,
                    scope.streamingContent(prefix + value).rejection().orElseThrow());
        }
    }

    @Test void openCredentialFieldsKeepTheirBeginningAcrossLongWhitespaceAndQuotes() {
        var scope = new PublicOutputGuard.Scope(List.of());
        String prefix = "公开摘要。\n".repeat(40);
        for (String open : List.of(
                "password" + " ".repeat(160) + "= \"" + " ".repeat(160),
                "Authorization:" + " ".repeat(160) + "\"Bearer " + " ".repeat(160),
                "ａｃｃｅｓｓ＿ｔｏｋｅｎ＝＂" + " ".repeat(320),
                "a".repeat(200) + "_token=\"" + " ".repeat(160))) {
            int previous = 0;
            for (int end = 0; end <= open.length(); end++) {
                var result = scope.streamingContent(prefix + open.substring(0, end));
                assertTrue(result.rejection().isEmpty());
                assertTrue(result.safePrefixCharacters() >= previous);
                assertTrue(result.safePrefixCharacters() <= prefix.length());
                previous = result.safePrefixCharacters();
            }
            assertEquals(PublicOutputGuard.REJECTION_CODE,
                    scope.streamingContent(prefix + open + "actual-secret\";\n").rejection().orElseThrow());
        }
    }

    @Test void splitDocumentationPlaceholdersRemainAllowedAndNeverPublishAnOpenField() {
        var scope = new PublicOutputGuard.Scope(List.of());
        String prefix = "公开摘要。\n".repeat(40);
        for (String example : List.of("password=example;\n", "token=\"<TOKEN>\";\n",
                "Authorization: \"Bearer $" + "{" + "A".repeat(200) + "}\";\n")) {
            int previous = 0;
            for (int end = 0; end <= example.length(); end++) {
                var result = scope.streamingContent(prefix + example.substring(0, end));
                assertTrue(result.rejection().isEmpty(), "分片占位示例不能提前拒绝");
                assertTrue(result.safePrefixCharacters() >= previous);
                previous = result.safePrefixCharacters();
            }
            String finished = prefix + example + "后续公开说明。\n".repeat(30);
            assertFalse(scope.rejects(finished));
            assertTrue(scope.streamingContent(finished).safePrefixCharacters() > prefix.length() + example.length());
        }
    }

    @Test void completeJsonNativeBooleanObservationsAreTypedButStringCredentialsRemainRejected() {
        var scope = new PublicOutputGuard.Scope(List.of());
        for (String value : List.of("{\"has_token\":true}", "{\"has_password\":false}",
                "{\"nested\":[{\"access_token\":true},{\"password\":false}],\"count\":3}",
                "{\"has_ｔｏｋｅｎ\":true,\"文字\":\"观察结果\"}")) {
            assertFalse(scope.rejects(value), value);
        }
        for (String value : List.of("{\"has_token\":\"true\"}", "{\"password\":\"false\"}",
                "token=true;", "password=false;", "{'has_token':true}", "{has_token:true}",
                "{\"has_token\":TRUE}", "{\"has_token\":true-secret}", "{\"has_token\":trueSuffix}",
                "{\"has_token\":false-secret}", "{\"has_token\":falseSuffix}",
                "{\"has_token\":true} trailing", "{\"has_token\":true} {}",
                "{\"has_token\":true,\"has_token\":false}", "{\"has_token\":true,}",
                "{\"has_token\":true,\"password\":\"actual-secret\"}",
                "{\"has_token\":true,\"text\":\"password=actual-secret\"}")) {
            assertTrue(scope.rejects(value), value);
        }
        assertTrue(scope.including(List.of("true")).rejects("{\"has_token\":true}"));
        assertTrue(scope.including(List.of("false")).rejects("{\"has_password\":false}"));
        assertEquals("public-output-v5", PublicOutputGuard.POLICY_VERSION);
    }

    @Test void manyOrdinaryBooleanTokensAndUnicodePrefixesPreserveOnlyStructuralBooleanExemption() {
        var scope = new PublicOutputGuard.Scope(List.of());
        String booleans = "true,false,".repeat(50_000) + "true";
        String prefix = "{\"notes\":\"İ e\u0301 ﬃ 观察😀\",\"ordinary\":[" + booleans + "],";
        String observed = prefix + "\"nested\":[{\"has_token\":true},{\"has_password\":false}]}";
        assertFalse(scope.rejects(observed));
        assertTrue(scope.rejects(prefix + "\"nested\":[{\"has_token\":true},{\"password\":\"true\"}]}"));
        assertTrue(scope.rejects(prefix + "\"has_token\":true,\"password\":\"actual-secret\"}"));
        assertTrue(scope.rejects(prefix + "\"has_token\":trueSuffix}"));
        assertTrue(scope.including(List.of("actual-private-value")).rejects(
                prefix + "\"has_token\":true,\"value\":\"actual-private-value\"}"));
        assertTrue(scope.rejects(observed + " {}"));
    }

    @Test void splitJsonBooleanRetainsFieldUntilStructureCanBeVerifiedAndRejectsBooleanPrefixSecrets() {
        var scope = new PublicOutputGuard.Scope(List.of());
        String json = "{\"has_token\":true,\"has_password\":false,\"notes\":\"" + "公开观察".repeat(100) + "\"}";
        for (int end = 0; end <= json.length(); end++) {
            var content = scope.streamingContent(json.substring(0, end));
            assertTrue(content.rejection().isEmpty(), "JSON原生布尔分片不应误拒");
            if (end < json.length()) assertTrue(content.safePrefixCharacters() <= json.indexOf("has_token"),
                    "结构未闭合前扣住原凭据头，不提前公开未定类型的赋值");
        }
        assertFalse(scope.rejects(json));
        assertTrue(scope.streamingContent(json + "\n").safePrefixCharacters() > json.indexOf("has_token"));
        for (String suffix : List.of("true-secret", "trueSuffix", "false-secret", "falseSuffix", "\"true\"")) {
            String credential = "{\"has_token\":" + suffix + "}\n";
            for (int end = 0; end < credential.length(); end++) {
                var content = scope.streamingContent(credential.substring(0, end));
                assertTrue(content.safePrefixCharacters() <= credential.indexOf("has_token"));
            }
            assertTrue(scope.rejects(credential), suffix);
        }
        assertTrue(scope.rejects("{\"has_token\":true,"), "最终未闭合结构不能取得类型豁免");
    }

    @Test void malformedPlaceholdersAndQuotedCredentialSymbolsCannotBypassInspection() {
        var scope = new PublicOutputGuard.Scope(List.of());
        for (String credential : List.of(
                "password=\"<R3alSecret!\"", "password=\"${R3alSecret!\"", "password=\"}R3alSecret!\"",
                "password='}R3alSecret!'", "password=}R3alSecret!", "token=${ENV_VAR}actual-value",
                "token=\"<TOKEN>actual-value\"", "token=\"${ENV_VAR}actual-value\"", "token=<TOKEN>actual-value",
                "token=\"<TOKEN>\"actual-value", "password=\"<TOKEN>\\\"actual-value\"",
                "password='<TOKEN>\\'actual-value'", "password=\"<TOKEN>", "password='${ENV_VAR}",
                "password=\"sample actual-value\"", "password=\"sample,actual-value\"", "password='example;actual-value'",
                "Authorization: Bearer }real-private-value", "Authorization: Bearer ${ENV_VAR}actual-value",
                "Authorization: \"Bearer <TOKEN>,actual-value\"", "Authorization: 'Bearer <TOKEN>;actual-value'",
                "Authorization: \"Bearer <TOKEN>", "Authorization: 'Bearer ${ENV_VAR}")) {
            assertTrue(scope.rejects(credential), credential);
        }
        for (String documentation : List.of(
                "password=\"example\"", "password='sample'", "token=\"<TOKEN>\"", "token='<ACCESS_TOKEN>'",
                "access_token=${ACCESS_TOKEN}", "password='${PASSWORD}'", "token=\"${ENV_VAR}\"",
                "{\"password\":\"<PASSWORD>\"}", "password=\"\"", "token_count=7500; max_tokens=1000",
                "Authorization: \"Bearer <TOKEN>\"", "{\"Authorization\":\"Basic ${ENV_VAR}\"}")) {
            assertFalse(scope.rejects(documentation), documentation);
        }
    }
}
