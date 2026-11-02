package com.skillforge.core.shared.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** 宿主雪花ID只接受正十进制JSON字符串，拒绝数字隐式转型及溢出。 */
public final class DecimalStringIdDeserializer extends JsonDeserializer<Long> {
    @Override public Long deserialize(JsonParser parser,DeserializationContext context)throws IOException {
        if(!parser.hasToken(JsonToken.VALUE_STRING))return (Long)context.handleUnexpectedToken(Long.class,parser);
        String value=parser.getText();
        if(!value.matches("[1-9][0-9]{0,18}"))return context.reportInputMismatch(Long.class,"ID必须为正十进制字符串");
        try{return Long.valueOf(value);}
        catch(NumberFormatException invalid){return context.reportInputMismatch(Long.class,"ID超出范围");}
    }
}