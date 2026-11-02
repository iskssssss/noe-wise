package com.skillforge.core.knowledge.integration.runtime.knowledge;

import java.util.*;

/** 只编码稳定知识 ID 与游标范围的字符串元组，不另建 Runtime 结果格式。 */
final class KnowledgeJson {
    private KnowledgeJson() { }
    static String encode(List<String> values) {
        return values.stream().map(KnowledgeJson::quote)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    static String quote(String text) {
        var out=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {
                if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(i+1)))throw new IllegalArgumentException("knowledge_invalid_unicode");
                out.append(c).append(text.charAt(++i));
            } else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("knowledge_invalid_unicode");
            else if(c=='"'||c=='\\')out.append('\\').append(c);
            else if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
