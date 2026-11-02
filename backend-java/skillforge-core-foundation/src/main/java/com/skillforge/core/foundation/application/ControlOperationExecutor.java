package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import java.util.List;
import java.util.Objects;

/**
 * Open Platform 资源执行 SPI。
 *
 * <p>适配器只接收已进入 processing 的 ControlOperation，并返回逐项结果；资源副作用不允许从
 * Controller 直接触发。未注册适配器时由 worker 稳定 fail-closed，不把受理账本误报为成功。
 */
public interface ControlOperationExecutor {

    /**
     * 判断当前适配器是否负责给定集成和操作类型。
     *
     * @param integrationId 集成标识
     * @param operationType 操作类型
     * @return 是否支持
     */
    boolean supports(String integrationId, String operationType);

    /**
     * 执行资源副作用并返回状态投影。
     *
     * @param operation processing 状态的操作
     * @return 结果
     */
    Execution execute(ControlOperation operation);

    /** 异步资源执行结果。 */
    record Execution(ControlOperationStatus status, List<ControlOperationItem> items) {
        public Execution {
            Objects.requireNonNull(status, "status");
            if (status == ControlOperationStatus.ACCEPTED || status == ControlOperationStatus.PROCESSING) {
                throw new IllegalArgumentException("executor result must advance operation status");
            }
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
