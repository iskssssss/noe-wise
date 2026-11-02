package com.skillforge.runtime.adapter.jdbc.memory.search;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Lucene CJK 成熟分析管道；版本随配置冻结，不注入场景同义词或自定义分词。 */
public final class MemorySearchAnalyzer {
    public static final String VERSION = "lucene-cjk-9.12.3-v1";
    private static final String TEXT_FIELD = "memory_body";

    private MemorySearchAnalyzer() { }

    /** 按 Lucene 的原始出现顺序稳定去重；宽度、大小写和 CJK bigram 均由 Lucene 处理。 */
    public static List<String> tokens(String text) {
        Objects.requireNonNull(text, "text");
        var result = new LinkedHashSet<String>();
        try (var analyzer = new CJKAnalyzer(); var stream = analyzer.tokenStream(TEXT_FIELD, text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) result.add(term.toString());
            stream.end();
        } catch (IOException failure) {
            throw new UncheckedIOException("memory_search_analysis_failed", failure);
        }
        return List.copyOf(result);
    }

    /**
     * 生成 PostgreSQL tsquery 的 OR 词元表达式；调用方将返回值作为一个绑定参数使用。
     * 引号与反斜杠按 tsquery 字面量语法转义，词元不能成为布尔操作符或 SQL。
     * 空结果返回空字符串，由调用方表达正常无词法命中；不重新分词或填充宽泛条件。
     */
    public static String queryLexemes(Collection<String> tokens) {
        Objects.requireNonNull(tokens, "tokens");
        var unique = new LinkedHashSet<String>();
        for (String token : tokens) {
            Objects.requireNonNull(token, "token");
            if (token.isEmpty() || token.indexOf('\0') >= 0)
                throw new IllegalArgumentException("memory_search_lexeme_invalid");
            unique.add(token);
        }
        return unique.stream().map(token -> "'" + token.replace("\\", "\\\\").replace("'", "''") + "'")
                .collect(Collectors.joining(" | "));
    }
}
