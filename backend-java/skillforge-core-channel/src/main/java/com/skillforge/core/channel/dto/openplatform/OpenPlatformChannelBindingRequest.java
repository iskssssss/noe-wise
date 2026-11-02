package com.skillforge.core.channel.dto.openplatform;

import io.swagger.v3.oas.annotations.media.Schema;

/** Open Platform ChannelBinding 请求。 */
public record OpenPlatformChannelBindingRequest(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) String deploymentId) {
}
