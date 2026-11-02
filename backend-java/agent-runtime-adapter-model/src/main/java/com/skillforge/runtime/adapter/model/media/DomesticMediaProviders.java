package com.skillforge.runtime.adapter.model.media;

import java.net.http.HttpClient;
import java.time.Clock;

/** 媒体 SDK 唯一装配入口；供应商协议与请求映射留在对应适配器中。 */
public final class DomesticMediaProviders {
    private final DomesticMediaProvider bailian;
    private final DomesticMediaProvider minimax;

    public DomesticMediaProviders(HttpClient http, Clock clock, int maximumResponseBytes) {
        bailian = new BailianMediaProvider(http, clock, maximumResponseBytes);
        minimax = new MiniMaxSpeechProvider(http, clock, maximumResponseBytes);
    }

    /** 仅返回已实现适配器；不把未知品牌转交给通用 HTTP 分支。 */
    public DomesticMediaProvider require(String providerId) {
        if (providerId == null) throw new IllegalArgumentException("media_provider_required");
        return switch (providerId) {
            case "aliyun-bailian" -> bailian;
            case "minimax" -> minimax;
            default -> throw new IllegalArgumentException("media_provider_unsupported");
        };
    }
}
