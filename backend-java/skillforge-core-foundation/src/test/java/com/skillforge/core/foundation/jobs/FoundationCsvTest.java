package com.skillforge.core.foundation.jobs;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.shared.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

/** CSV 预检的精确材料与导出公式安全，失败不能返回部分合法行冒充完整材料。 */
class FoundationCsvTest {
    @Test void parsesQuotedCommaMultilineAndEscapedQuote(){var result=FoundationCsv.parse("\uFEFFcode,label\r\na,\"中文,第二行\n含\"\"引号\"\"\"\r\n".getBytes(StandardCharsets.UTF_8),List.of("code","label"));assertEquals(List.of(List.of("a","中文,第二行\n含\"引号\"")),result);}
    @Test void rejectsHeaderMismatchMalformedQuotesAndColumnCount(){for(String csv:new String[]{"label,code\na,b","code,label\na,\"unfinished","code,label\na,b,c","code,label\na,\"x\"suffix"})assertThrows(ApiException.class,()->FoundationCsv.parse(csv.getBytes(StandardCharsets.UTF_8),List.of("code","label")));}
    @Test void enforcesRowAndCellBudget(){assertThrows(ApiException.class,()->FoundationCsv.parse(("code\n"+"a\n".repeat(10001)).getBytes(StandardCharsets.UTF_8),List.of("code")));assertThrows(ApiException.class,()->FoundationCsv.parse(("code\n"+"a".repeat(4097)).getBytes(StandardCharsets.UTF_8),List.of("code")));}
    @Test void rejectsInvalidUtf8AndEmptyData(){assertThrows(ApiException.class,()->FoundationCsv.parse(new byte[]{(byte)0xc3,0x28},List.of("code")));assertThrows(ApiException.class,()->FoundationCsv.parse("code\n".getBytes(StandardCharsets.UTF_8),List.of("code")));}
    @Test void exportsFormulaAsTextAndRetainsQuotedChinese(){String csv=new String(FoundationCsv.encode(List.of("name"),List.of(List.of("=SUM(A1:A2)"),List.of("  @command"),List.of("中文\"标记"))),StandardCharsets.UTF_8);assertTrue(csv.contains("\"'=SUM(A1:A2)\""));assertTrue(csv.contains("\"'  @command\""));assertTrue(csv.contains("\"中文\"\"标记\""));assertTrue(csv.startsWith("\uFEFF"));}

    /** 人工RFC4180材料与独立期望同时声明，24行交错逗号、多行、引号、空格及空值。 */
    @Test
    void parsesTwentyFourComplexRowsAgainstIndependentValues() {
        StringBuilder material = new StringBuilder("\uFEFFcode,label,notes\r\n");
        List<List<String>> expected = new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            material.append("row_").append(index).append(",\"阶段,").append(index)
                    .append("\n含\"\"引号\"\"\",\"  保留空格  \"\r\n");
            expected.add(List.of("row_" + index, "阶段," + index + "\n含\"引号\"", "  保留空格  "));
        }
        assertEquals(expected, FoundationCsv.parse(material.toString().getBytes(StandardCharsets.UTF_8),
                List.of("code", "label", "notes")));
        assertEquals(List.of(List.of("row", "", "")), FoundationCsv.parse("code,label,notes\r\nrow,,\r\n"
                .getBytes(StandardCharsets.UTF_8), List.of("code", "label", "notes")));
    }

    /** 最后一行损坏必须使整个批次失败，23行合法前缀不能作为部分成功返回。 */
    @Test
    void rejectsFinalRowMalformedQuotesAndUtf8WithPreciseDomainCode() throws Exception {
        String prefix = "code,label\r\n" + "ok,中文\r\n".repeat(23);
        List<byte[]> invalid = new ArrayList<>();
        invalid.add((prefix + "last,\"unfinished").getBytes(StandardCharsets.UTF_8));
        invalid.add((prefix + "last,\"closed\"suffix\r\n").getBytes(StandardCharsets.UTF_8));
        invalid.add((prefix + "last,extra,column\r\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream utf8 = new ByteArrayOutputStream();
        utf8.write(prefix.getBytes(StandardCharsets.UTF_8));
        utf8.write(new byte[] {'x', ',', (byte) 0xc3, 0x28});
        invalid.add(utf8.toByteArray());
        for (byte[] bytes : invalid) {
            ApiException failure = assertThrows(ApiException.class,
                    () -> FoundationCsv.parse(bytes, List.of("code", "label")));
            assertEquals(HttpStatus.BAD_REQUEST, failure.getStatus());
            assertEquals("csv_invalid", failure.getCode());
            assertFalse(failure.isRetryable());
        }
    }

    /** 公式前缀含前导Unicode空格、制表符和多行，完整字节golden验证转义。 */
    @Test
    void exportsAllFormulaPrefixesAsLiteralWithIndependentGoldenBytes() {
        byte[] actual = FoundationCsv.encode(List.of("value", "note"), List.of(
                List.of("=1+1", "中文,逗号"), List.of(" +SUM(A1:A2)", "含\"引号\""),
                List.of("\t-2", "多行\n第二行"), List.of("\u2003@command", "保持Unicode"),
                List.of("plain", "")));
        String golden = "\uFEFF\"value\",\"note\"\r\n"
                + "\"'=1+1\",\"中文,逗号\"\r\n"
                + "\"' +SUM(A1:A2)\",\"含\"\"引号\"\"\"\r\n"
                + "\"'\t-2\",\"多行\n第二行\"\r\n"
                + "\"'\u2003@command\",\"保持Unicode\"\r\n"
                + "\"plain\",\"\"\r\n";
        assertArrayEquals(golden.getBytes(StandardCharsets.UTF_8), actual);
    }
}
