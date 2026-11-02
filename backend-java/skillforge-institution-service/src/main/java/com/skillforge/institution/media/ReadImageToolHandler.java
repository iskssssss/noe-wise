package com.skillforge.institution.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformMediaToolHandler;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.media.MediaRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 历史图片和裁剪都使用当前可信会话中的媒体身份，不提供文件路径或 URL 读取。 */
@Component
public class ReadImageToolHandler implements PlatformMediaToolHandler {
    private final SessionMediaService media;
    private final ObjectMapper json;
    public ReadImageToolHandler(SessionMediaService media, ObjectMapper json) { this.media = media; this.json = json; }
    @Override public String toolName() { return "read_image"; }
    @Override public Map<String, Object> execute(Map<String, Object> args, PlatformToolExecutionContext context) {
        if (args == null || !Set.of("media_id", "crop").containsAll(args.keySet())
                || !(args.get("media_id") instanceof String id))
            return rejected("read_image_arguments_invalid", "图片读取参数无效；需要当前会话的真实 media_id，可选像素裁剪 crop。");
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalidId) {
            return rejected("read_image_media_id_invalid", "media_id 必须是当前会话受管图片的完整 UUID；render_artifact 的 artifact_id 和文件路径不是 media_id。没有真实媒体标识时应说明视觉检查未验证。");
        }
        SessionMediaService.Crop crop;
        try {
            crop = parseCrop(args.get("crop"));
        } catch (IllegalArgumentException | ArithmeticException invalidCrop) {
            return rejected("read_image_crop_invalid", "crop 必须仅包含 x、y、width、height 四个整数像素坐标，且宽高大于零。");
        }
        var owner = context.run().owner().sessionRef();
        MediaRef source;
        try {
            source = media.reference(owner, id);
        } catch (ApiException failure) {
            return knownRejection(failure);
        }
        // 在纯参数边界判定范围；不捕获存储读取、图片解码或结果序列化的未知异常。
        if (crop != null && (crop.x() < 0 || crop.y() < 0 || crop.width() <= 0 || crop.height() <= 0
                || (long) crop.x() + crop.width() > source.width()
                || (long) crop.y() + crop.height() > source.height()))
            return rejected("read_image_crop_invalid", "crop 必须位于当前图片范围内，且宽高大于零。");
        SessionMediaService.CropResult result;
        try {
            result = media.crop(owner, source, crop);
        } catch (ApiException failure) {
            return knownRejection(failure);
        }
        var output = new ImageResult(List.of(SessionMediaService.content(result.image())), result.image().id(),
                source.id(), result.crop(), result.image().width(), result.image().height(),
                new Coordinates("crop_image_pixels", result.crop().x(), result.crop().y(), 1, 1));
        return json.convertValue(output, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    /** 只解析模型提供的裁剪数值，禁止字符串、缺项与整数溢出被隐式补齐。 */
    private static SessionMediaService.Crop parseCrop(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> fields)
                || !fields.keySet().equals(Set.of("x", "y", "width", "height")))
            throw new IllegalArgumentException("read_image_crop_invalid");
        return new SessionMediaService.Crop(cropInteger(fields.get("x")), cropInteger(fields.get("y")),
                cropInteger(fields.get("width")), cropInteger(fields.get("height")));
    }

    private static int cropInteger(Object value) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException("read_image_crop_invalid");
        return new BigDecimal(number.toString()).intValueExact();
    }

    /** 已知业务拒绝返回安全文本，不将底层异常正文或当前会话外的信息带入模型。 */
    private static Map<String, Object> knownRejection(ApiException failure) {
        if (failure.getStatus() == HttpStatus.NOT_FOUND)
            return rejected("read_image_media_not_found", "当前会话中不存在该受管图片。请使用真实 media_id；artifact_id 和文件路径不能代替媒体标识，无法读取时应说明视觉检查未验证。");
        if (failure.getStatus() == HttpStatus.BAD_REQUEST)
            return rejected("read_image_request_invalid", "图片读取或裁剪参数无效，请核对真实 media_id 与图片内的整数像素范围。");
        throw failure;
    }

    private static Map<String, Object> rejected(String code, String message) {
        return Map.of("isError", true, "error_code", code,
                "content", List.of(Map.of("type", "text", "text", message)));
    }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ImageResult(List<SessionMediaService.ImageContent> content, String mediaId, String sourceMediaId,
            SessionMediaService.Crop crop, int width, int height, Coordinates coordinateMapping) { }
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Coordinates(String coordinateSpace, int sourceX, int sourceY, int scaleX, int scaleY) { }
}
