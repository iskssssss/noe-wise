package com.skillforge.core.capability.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Open Platform 资源 draft 正文；只接受对象，避免暴露内部实体。 */
public record OpenPlatformResourceContentRequest(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) Map<String, Object> content) {
}
