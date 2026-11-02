package com.skillforge.core.chat.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;

/** 业务用户的过程呈现；原始调用 JSON 和内部标识不能作为结果块。 */
public record ProcessPresentation(String title, String summary, List<Result> results,
                                  List<ChatReferenceView> references) {
    public static final String TEXT_KIND="text";
    public static final String TABLE_KIND="table";
    public ProcessPresentation {
        Objects.requireNonNull(title); Objects.requireNonNull(summary);
        results = List.copyOf(results); references = List.copyOf(references);
    }
    public static ProcessPresentation of(String title, String summary) {
        return new ProcessPresentation(title, summary, List.of(), List.of());
    }
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({@JsonSubTypes.Type(value = Text.class, name = TEXT_KIND),
            @JsonSubTypes.Type(value = Table.class, name = TABLE_KIND)})
    public sealed interface Result permits Text, Table { }
    public record Text(String format, String text) implements Result {
        public static final String PLAIN="plain";
        public static final String MARKDOWN="markdown";
        public Text {
            if (!List.of(PLAIN, MARKDOWN).contains(format)) throw new IllegalArgumentException("public_result_format_invalid");
            Objects.requireNonNull(text);
        }
    }
    public record Column(String key, String label) {
        public Column { Objects.requireNonNull(key); Objects.requireNonNull(label); }
    }
    public record Table(List<Column> columns, List<List<Object>> rows) implements Result {
        public Table {
            columns = List.copyOf(columns);
            int width = columns.size();
            rows = rows.stream().map(row -> {
                if (row.size() != width || row.stream().anyMatch(value -> value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)))
                    throw new IllegalArgumentException("public_result_table_invalid");
                return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(row));
            }).toList();
        }
    }
}
