package com.skillforge.core.foundation.assets;

import com.skillforge.core.shared.exception.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** 文件名与内容类型白名单；禁止浏览器可执行内容和伪造扩展名。 */
public final class FoundationFilePolicy {
    private FoundationFilePolicy() { }
    /** 文件名保持原事实；含路径、控制字符或超长名称直接拒绝。 */
    public static void validateName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 255
                || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(c -> c < 32 || c == 127)) reject("文件名无效");
    }
    /** 以内容签名和严格 UTF-8 验证识别 MIME，扩展名必须符合识别结果。 */
    public static String detect(String name, byte[] bytes) {
        validateName(name);
        String lower = name.toLowerCase(Locale.ROOT);
        if (bytes.length == 0) reject("不能上传空文件");
        if (starts(bytes, new byte[]{(byte)137,80,78,71,13,10,26,10}) && lower.endsWith(".png")) return "image/png";
        if (starts(bytes, new byte[]{(byte)255,(byte)216,(byte)255}) && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) return "image/jpeg";
        if (starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII)) && lower.endsWith(".pdf")) return "application/pdf";
        if (bytes.length > 12 && ascii(bytes,0,4).equals("RIFF") && ascii(bytes,8,4).equals("WEBP") && lower.endsWith(".webp")) return "image/webp";
        if (!(lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".json"))) reject("仅支持 CSV、TXT、JSON、PDF、PNG、JPEG、WebP");
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.chars().anyMatch(c -> c == 0 || (c < 32 && c != '\n' && c != '\r' && c != '\t')))
                reject("文本文件包含非法控制字符");
            String prefix=value.stripLeading().toLowerCase(Locale.ROOT);
            if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<svg") || prefix.startsWith("<?xml"))
                reject("不能上传可执行标记内容");
        } catch (CharacterCodingException e) { throw new ApiException(HttpStatus.BAD_REQUEST,"file_type_invalid","文本必须使用 UTF-8",false); }
        return lower.endsWith(".csv") ? "text/csv" : lower.endsWith(".json") ? "application/json" : "text/plain";
    }
    /** 比较有限签名前缀。 */ private static boolean starts(byte[] value,byte[] prefix) {if(value.length<prefix.length)return false;for(int i=0;i<prefix.length;i++)if(value[i]!=prefix[i])return false;return true;}
    /** 只用于固定 ASCII 签名。 */ private static String ascii(byte[] bytes,int offset,int length) { return new String(bytes,offset,length,StandardCharsets.US_ASCII); }
    /** 返回可修正的上传错误。 */ private static void reject(String message) {throw new ApiException(HttpStatus.BAD_REQUEST,"file_type_invalid",message,false);}
}
