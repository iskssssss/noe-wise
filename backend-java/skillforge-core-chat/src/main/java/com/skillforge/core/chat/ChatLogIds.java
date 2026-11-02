package com.skillforge.core.chat;

/** 日志关联标识工具；只输出不可逆的短标签，避免泄漏原始会话、执行或输入标识。 */
final class ChatLogIds {

    /** 私有构造器防止把日志工具误当作有状态服务实例化。 */
    private ChatLogIds() {
        // 工具类禁止实例化，日志调用方只使用静态标签方法。
    }

    /** 将外部标识转换为稳定的短哈希，便于同一请求内关联而不暴露原值。 */
    static String tag(Object value) {
        // 统一使用短标签，日志中不写入会话、执行或输入原始值。
        return value == null ? "-" : Integer.toHexString(String.valueOf(value).hashCode());
    }
}
