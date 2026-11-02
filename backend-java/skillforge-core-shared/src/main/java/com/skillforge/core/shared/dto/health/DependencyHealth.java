package com.skillforge.core.shared.dto.health;

/**
 * 单个依赖健康状态 DTO。
 *
 * @param ok    依赖是否正常
 * @param error 异常信息；正常时为 null
 */
public record DependencyHealth(
        boolean ok,
        String error) {
}
