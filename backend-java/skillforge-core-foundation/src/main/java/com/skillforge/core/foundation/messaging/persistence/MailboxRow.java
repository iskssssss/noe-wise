package com.skillforge.core.foundation.messaging.persistence;

import java.time.Instant;

/** 不可变邮箱版本与加密凭据；只能由配置/投递业务读取，禁止直接序列化给HTTP。 */
public record MailboxRow(String id, String scopeKey, String tenantId, String host, Integer port,
        String username, String passwordCipher, String fromAddress, String tlsMode, boolean enabled,
        boolean current, Instant createdAt) { }
