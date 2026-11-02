package com.skillforge.core.chat.contract;

import java.util.Map;

/**
 * 对话 SSE 事件出口。
 *
 * <p>编排引擎与对话服务通过该接口向流式响应写入事件；
 * 同步调用场景可传 null 跳过推送，仅收集返回结果中的事件列表。
 */
public interface ChatEventSink {

    /**
     * 推送一个 SSE 事件。
     *
     * @param event 事件名
     * @param data  事件数据
     */
    void send(String event, Map<String, Object> data);

    /**
     * 推送已经验证归属某个 Runtime Run 的事件。
     *
     * <p>普通显示出口无需持久该关联，默认保持既有传输行为。持久 Outbox 出口必须把
     * {@code runtimeRunId} 写入独立列，不能从事件 data 反向解析。</p>
     */
    default void sendRuntime(String event, Map<String, Object> data, String runtimeRunId) {
        send(event, data);
    }

    /**
     * 推送错误事件并结束流。
     *
     * @param message   错误描述
     * @param errorCode 错误码
     * @param retryable 是否可重试
     */
    void error(String message, String errorCode, boolean retryable);

    /**
     * 正常结束流。
     */
    void complete();
}
