package com.skillforge.core.foundation.messaging.contract;

import java.util.List;

/** 由身份授权域提供当前有效收件人；只能在给定账号域和租户内解析，停用成员不得返回。 */
public interface NotificationRecipientDirectory {
    /** @param scopeKey 可信平台/机构作用域 @param audience all、members、departments、roles @param ids 范围ID @return 当前有效且去重的成员，非法或跨域ID必须拒绝 */
    List<Recipient> resolve(String scopeKey, String audience, List<String> ids);
    /** @param memberId 同作用域成员身份 @param email 已登记邮箱，未登记为空 */
    record Recipient(String memberId, String email) { }
}
