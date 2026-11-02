package com.skillforge.core.agent.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.ExtractionOutput;
import com.skillforge.core.agent.systemagent.MemoryExtractionCandidates.Relation;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;

/** 记忆 wire 边界的唯一固定 camelCase codec；字段名由所属 record 组件维护，不继承应用全局命名或宽松转换。 */
public final class MemoryJsonCodec {
    private static final ObjectMapper JSON = mapper();

    private MemoryJsonCodec() { }

    /** 模型输出边界需要直接访问的协议字段唯一维护于此，正式 record 注解引用同一定义。 */
    public static final class ModelFields {
        public static final String CANDIDATES = "candidates";
        public static final String RELATION = "relation";
        public static final String KIND = "kind";
        public static final String TARGET = "target";

        private ModelFields() { }
    }

    private static ObjectMapper mapper() {
        var time = new SimpleModule();
        time.addSerializer(Instant.class, ToStringSerializer.instance);
        time.addDeserializer(Instant.class, new StdDeserializer<Instant>(Instant.class) {
            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (parser.currentToken() != JsonToken.VALUE_STRING) {
                    throw JsonMappingException.from(parser, "memory_extraction_temporal_invalid");
                }
                try {
                    return Instant.parse(parser.getText());
                } catch (RuntimeException invalid) {
                    throw JsonMappingException.from(parser, "memory_extraction_temporal_invalid");
                }
            }
        });
        ObjectMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .addModule(time)
                .build();
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    /** 严格读取正式 record；缺字段、未知字段、重复键、尾随文档或类型强转均失败关闭。 */
    public static <T> T decode(String encoded, Class<T> type, String reasonCode) {
        try {
            return decodeParsed(JSON.readTree(encoded), type, reasonCode);
        } catch (Exception failure) {
            throw rejected(failure, reasonCode);
        }
    }

    /** 仅模型输出接受无歧义的 relation 字符串及同级 target；落地前立即恢复唯一正式 Relation。 */
    public static ExtractionOutput decodeModelOutput(String encoded) {
        try {
            JsonNode wire = JSON.readTree(encoded);
            JsonNode candidates = wire == null ? null : wire.get(ModelFields.CANDIDATES);
            if (candidates != null && candidates.isArray()) candidates.forEach(MemoryJsonCodec::normalizeModelRelation);
            return decodeParsed(wire, ExtractionOutput.class, "memory_extraction_json_invalid");
        } catch (Exception failure) {
            throw rejected(failure, "memory_extraction_json_invalid");
        }
    }

    private static void normalizeModelRelation(JsonNode candidate) {
        if (!candidate.isObject()) return;
        JsonNode relation = candidate.get(ModelFields.RELATION);
        JsonNode target = candidate.get(ModelFields.TARGET);
        if (relation != null && relation.isTextual()) {
            if (target != null && !target.isNull() && !target.isTextual())
                throw new IllegalArgumentException("memory_extraction_relation_invalid");
            Relation normalized = new Relation(relation.textValue(), target == null || target.isNull() ? null : target.textValue());
            ObjectNode object = (ObjectNode) candidate;
            object.set(ModelFields.RELATION, node(normalized));
            object.remove(ModelFields.TARGET);
        } else if (target != null) {
            // 正式 relation 已有 target 时不接受第二个位置，即使两个值相同也不能默默选取。
            throw new IllegalArgumentException("memory_extraction_relation_invalid");
        }
    }

    private static <T> T decodeParsed(JsonNode wire, Class<T> type, String reasonCode) throws JsonProcessingException {
        if (wire == null || wire.isNull()) throw new IllegalArgumentException(reasonCode);
        rejectExplicitNullForOmittedFields(wire, JSON.constructType(type));
        return JSON.treeToValue(wire, type);
    }

    private static IllegalArgumentException rejected(Exception failure, String reasonCode) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null
                    && cause.getMessage().matches("memory_[a-z_]+")) {
                return new IllegalArgumentException(cause.getMessage());
            }
        }
        return new IllegalArgumentException(reasonCode);
    }

    /** 可省略字段与必需的显式 null 不同；字段名只取自 record 元数据，不再维护第二份字符串表。 */
    private static void rejectExplicitNullForOmittedFields(JsonNode wire, JavaType type) {
        if (type.isCollectionLikeType() && wire.isArray()) {
            wire.forEach(item -> rejectExplicitNullForOmittedFields(item, type.getContentType()));
            return;
        }
        if (!type.getRawClass().isRecord() || !wire.isObject()) return;
        for (var component : type.getRawClass().getRecordComponents()) {
            JsonNode child = wire.get(component.getName());
            if (child == null) continue;
            JsonInclude include = component.getAccessor().getAnnotation(JsonInclude.class);
            if (child.isNull() && include != null && include.value() == JsonInclude.Include.NON_NULL)
                throw new IllegalArgumentException("memory_json_optional_null_invalid");
            rejectExplicitNullForOmittedFields(child, JSON.constructType(component.getGenericType()));
        }
    }

    /** JsonNode 仅作为外部 JSON 边界；进入业务前立即转换为完整 record。 */
    public static <T> T decode(JsonNode encoded, Class<T> type, String reasonCode) {
        if (encoded == null) throw new IllegalArgumentException(reasonCode);
        return decode(encoded.toString(), type, reasonCode);
    }

    /** 规范键序与精确十进制数字，重建输入不会因 jsonb 展开指数表示而改变原指纹。 */
    public static String encode(Object value) {
        try {
            return JSON.writeValueAsString(canonical(JSON.valueToTree(value)));
        } catch (Exception failure) {
            throw new IllegalArgumentException("memory_json_encoding_invalid");
        }
    }

    /** 只在需要输出 JSON 的适配边界创建树，业务实体不依赖字段名读写。 */
    public static ObjectNode node(Object value) {
        JsonNode node = canonical(JSON.valueToTree(value));
        if (!node.isObject()) throw new IllegalArgumentException("memory_json_object_required");
        return (ObjectNode) node;
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            names.forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> result.add(canonical(child)));
            return result;
        }
        if (node.isNumber()) return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        return node.deepCopy();
    }
}
