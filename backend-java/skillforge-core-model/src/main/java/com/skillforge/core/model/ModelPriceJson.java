package com.skillforge.core.model;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/** 模型单价以小数分传输；HTTP 十进制字符串避免浮点精度损失。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@JacksonAnnotationsInside
@JsonSerialize(using = ToStringSerializer.class)
@JsonDeserialize(using = ModelPriceJson.Reader.class)
@Schema(type = "string", pattern = ModelPriceJson.PATTERN, description = ModelPriceJson.DESCRIPTION)
public @interface ModelPriceJson {
    String PATTERN = "^(0|[1-9][0-9]*)(\\.[0-9]{1,6})?$";
    String DESCRIPTION = "非负小数分的十进制字符串，最多6位小数，上限9223372036854775807；null表示未配置";

    final class Reader extends StdDeserializer<BigDecimal> {
        public Reader() { super(BigDecimal.class); }

        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                String text = parser.getText();
                if (text.length() <= 26 && text.matches(PATTERN)) {
                    BigDecimal value = new BigDecimal(text);
                    if (ModelPrices.valid(value)) return ModelPrices.normalize(value);
                }
            }
            return context.reportInputMismatch(BigDecimal.class,
                    "价格必须为0至9223372036854775807的十进制字符串，最多6位小数分");
        }
    }
}
