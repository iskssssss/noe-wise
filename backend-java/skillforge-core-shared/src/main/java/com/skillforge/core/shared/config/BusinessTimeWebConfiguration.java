package com.skillforge.core.shared.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.skillforge.common.time.BusinessTime;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 只在 HTTP 输出边界格式化北京时间，不改变持久化或外部协议的 ObjectMapper。 */
@Configuration
public class BusinessTimeWebConfiguration implements WebMvcConfigurer {
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter json) {
                json.setObjectMapper(httpMapper(json.getObjectMapper()));
            }
        }
    }

    public static ObjectMapper httpMapper(ObjectMapper original) {
        SimpleModule module = new SimpleModule("beijing-http-time");
        module.addSerializer(Instant.class, serializer(value -> value));
        module.addSerializer(OffsetDateTime.class, serializer(OffsetDateTime::toInstant));
        module.addSerializer(ZonedDateTime.class, serializer(ZonedDateTime::toInstant));
        module.addSerializer(LocalDateTime.class,
                serializer(value -> value.atZone(BusinessTime.ZONE).toInstant()));
        return original.copy().registerModule(module);
    }

    private static <T> JsonSerializer<T> serializer(Function<T, Instant> instant) {
        return new JsonSerializer<>() {
            @Override
            public void serialize(T value, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                generator.writeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        instant.apply(value).atZone(BusinessTime.ZONE)));
            }
        };
    }
}
