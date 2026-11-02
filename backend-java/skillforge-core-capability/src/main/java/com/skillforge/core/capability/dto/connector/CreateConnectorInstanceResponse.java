package com.skillforge.core.capability.dto.connector;


/**
 * 创建连接器实例响应 DTO。
 *
 * @param status       创建状态
 * @param instanceId   实例业务 ID
 * @param id           实例主键
 * @param authCredential 凭证脱敏值
 */
public record CreateConnectorInstanceResponse(
        String status,
        String instanceId,
        Long id,
        String authCredential) {
}
