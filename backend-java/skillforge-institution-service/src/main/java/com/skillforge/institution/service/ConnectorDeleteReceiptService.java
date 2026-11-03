package com.skillforge.institution.service;

import com.skillforge.core.capability.application.ConnectorInstanceService;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorDeleteReceiptService {
    private final ConnectorDeleteReceiptMapper receipts;
    private final ConnectorInstanceService instances;

    public ConnectorDeleteReceiptService(ConnectorDeleteReceiptMapper receipts, ConnectorInstanceService instances) {
        this.receipts = receipts;
        this.instances = instances;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(long tenant, String request, String actor, String instance) {
        if (receipts.claim(tenant, request, actor, instance) == 0) {
            var existing = receipts.find(tenant, request);
            if (existing == null || !actor.equals(existing.actorRef()) || !instance.equals(existing.instanceId()))
                throw new ApiException(HttpStatus.CONFLICT, "连接器删除请求标识已用于其他操作");
            return;
        }
        instances.deleteUnused(tenant, instance);
    }

    public ConnectorDeleteReceiptMapper.Row find(long tenant, String request, String actor) {
        var row = receipts.find(tenant, request);
        return row != null && actor.equals(row.actorRef()) ? row : null;
    }
}
