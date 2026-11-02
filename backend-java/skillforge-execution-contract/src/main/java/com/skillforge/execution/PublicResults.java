package com.skillforge.execution;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 脚本仅声明业务文字或表格；执行状态、标题和引用均由宿主构造。 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record PublicResults(List<Result> results) {
    public PublicResults { results=List.copyOf(Objects.requireNonNull(results,"results")); }
    @JsonTypeInfo(use=JsonTypeInfo.Id.NAME, property="kind")
    @JsonSubTypes({@JsonSubTypes.Type(value=Text.class,name="text"), @JsonSubTypes.Type(value=Table.class,name="table")})
    public sealed interface Result permits Text, Table { }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Text(String format, String text) implements Result {
        public Text {
            if(!List.of("plain","markdown").contains(format))throw new IllegalArgumentException("public_result_format_invalid");
            Objects.requireNonNull(text,"text");
        }
    }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Column(String key, String label) {
        public Column {
            if(key==null||key.isBlank()||label==null||label.isBlank())throw new IllegalArgumentException("public_result_column_invalid");
        }
    }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Table(List<Column> columns, List<List<Object>> rows) implements Result {
        public Table {
            columns=List.copyOf(Objects.requireNonNull(columns,"columns"));
            if(columns.isEmpty()||columns.stream().map(Column::key).distinct().count()!=columns.size())
                throw new IllegalArgumentException("public_result_columns_invalid");
            var copy=new ArrayList<List<Object>>();
            for(var row:Objects.requireNonNull(rows,"rows")) {
                if(row==null||row.size()!=columns.size()||row.stream().anyMatch(value->value!=null
                        && !(value instanceof String||value instanceof Number||value instanceof Boolean)))
                    throw new IllegalArgumentException("public_result_row_invalid");
                copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
            }
            rows=List.copyOf(copy);
        }
    }
}
