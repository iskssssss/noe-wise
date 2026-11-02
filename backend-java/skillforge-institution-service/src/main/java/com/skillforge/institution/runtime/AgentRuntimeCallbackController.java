package com.skillforge.institution.runtime;

import java.util.Locale;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackIngress;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.CallbackDeliveryRef;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.CallbackDeliveryResult;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.CallbackRef;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.VerifiedCallbackDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime HMAC 回调入口。验证签名后只把已证明 delivery 交给 Callback Port，
 * 由 Runtime worker 再调 façade，HTTP 线程不恢复 Workflow 或 Agent。
 */
@RestController
@RequestMapping("/api/v1/runtime/callbacks")
public class AgentRuntimeCallbackController {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeCallbackController.class);

    private final JdbcCallbackIngress ingress;
    private final AgentRuntimeWorkflowCallbackDeliveries workflows;

    public AgentRuntimeCallbackController(
            JdbcCallbackIngress ingress, AgentRuntimeWorkflowCallbackDeliveries workflows) {
        this.ingress = ingress;
        this.workflows = workflows;
    }

    /**
     * 受理一次已签名的 callback delivery。
     *
     * @param tenantId 租户标识，必须与注册事实一致
     * @param callbackId 注册 callback 身份
     * @param deliveryId 本次投递身份
     * @param timestamp Unix 秒时间戳
     * @param signature HMAC 签名
     * @param body 原始请求体
     * @return 已进入 READY 账本或稳定重放的回执
     */
    @PostMapping(
            value = "/deliveries",
            consumes = MediaType.ALL_VALUE)
    public AgentRuntimeCallbackDeliveryResponse deliver(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-Callback-Id") String callbackId,
            @RequestHeader("X-Delivery-Id") String deliveryId,
            @RequestHeader("X-Timestamp") String timestamp,
            @RequestHeader("X-Signature") String signature,
            @RequestBody byte[] body) {
        VerifiedCallbackDelivery verified;
        try {
            verified = ingress.verify(
                    new CallbackRef(new TenantId(tenantId), callbackId),
                    new CallbackDeliveryRef(new TenantId(tenantId), deliveryId),
                    timestamp, signature, body == null ? new byte[0] : body);
        } catch (IllegalArgumentException rejected) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Runtime callback 鉴权失败");
        } catch (RuntimeException failure) {
            log.error("Runtime callback 验证失败", failure);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Runtime callback 不可用");
        }
        CallbackDeliveryResult result;
        try {
            result = workflows.acceptVerified(verified);
        } catch (RuntimeException failure) {
            log.error("Runtime callback 落账失败", failure);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Runtime callback 不可用");
        }
        return switch (result) {
            case CallbackDeliveryResult.Ready ready ->
                    new AgentRuntimeCallbackDeliveryResponse(
                            ready.receipt().status().name().toLowerCase(Locale.ROOT),
                            ready.receipt().deliveryRef().deliveryId());
            case CallbackDeliveryResult.Replayed replayed ->
                    new AgentRuntimeCallbackDeliveryResponse(
                            replayed.receipt().status().name().toLowerCase(Locale.ROOT),
                            replayed.receipt().deliveryRef().deliveryId());
            case CallbackDeliveryResult.Rejected rejected ->
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            rejected.receipt().rejectionReasonCode().orElse("callback_rejected"));
            case CallbackDeliveryResult.Conflict ignored ->
                    throw new ApiException(HttpStatus.CONFLICT, "Runtime callback delivery 冲突");
        };
    }

    /** Runtime callback 受理回执；只暴露 delivery 身份与账本状态。 */
    public record AgentRuntimeCallbackDeliveryResponse(String status, String deliveryId) {
    }
}
