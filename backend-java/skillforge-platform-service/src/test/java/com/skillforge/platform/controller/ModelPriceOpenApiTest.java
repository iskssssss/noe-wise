package com.skillforge.platform.controller;

import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.ModelView;
import com.skillforge.core.model.ModelPriceJson;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 生成客户端看到的金额类型必须与真实 JSON 字符串协议一致。 */
class ModelPriceOpenApiTest {
    @Test void exposesAllModelPricesAsStringsInBothDirections() {
        for (Class<?> type : new Class<?>[]{ModelWrite.class, ModelView.class}) {
            var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type));
            var properties = resolved.schema.getProperties();
            for (String field : new String[]{"inputPricePerMillionCents", "outputPricePerMillionCents", "pricePerQuantityCents"}) {
                assertThat(((io.swagger.v3.oas.models.media.Schema<?>) properties.get(field)).getType()).isEqualTo("string");
                assertThat(((io.swagger.v3.oas.models.media.Schema<?>) properties.get(field)).getPattern()).isEqualTo(ModelPriceJson.PATTERN);
            }
        }
    }
}
