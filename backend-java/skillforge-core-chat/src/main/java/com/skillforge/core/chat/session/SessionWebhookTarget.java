package com.skillforge.core.chat.session;

/** 已领取投递的冻结目标；加密签名材料仅供受控 transport 解密，不进入公开事件或日志。 */
public record SessionWebhookTarget(String endpointUrl, String signingSecretEncrypted) {
}
