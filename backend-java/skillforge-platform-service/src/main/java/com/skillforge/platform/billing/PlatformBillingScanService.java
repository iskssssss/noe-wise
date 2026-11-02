package com.skillforge.platform.billing;

import com.skillforge.core.billing.application.BillingStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 手动扫描只在领取者事务内执行，网络重试返回原回执，不重复改变机构状态。 */
@Service
public class PlatformBillingScanService {
    private final BillingScanMapper mapper;
    private final BillingStateService billing;

    public PlatformBillingScanService(BillingScanMapper mapper, BillingStateService billing) {
        this.mapper = mapper;
        this.billing = billing;
    }

    @Transactional
    public BillingScanReceipt process(long actor, String operation) {
        if (mapper.claim(actor, operation) == 1) {
            var result = billing.processStates();
            if (mapper.complete(actor, operation, result.scanned(), result.warning(), result.active()) != 1) {
                throw new IllegalStateException("扫描回执提交失败");
            }
        }
        var receipt = mapper.find(actor, operation);
        if (receipt == null) throw new IllegalStateException("扫描回执缺失");
        return receipt;
    }

    /** null 表示尚无已提交回执，不能据此推断请求未到达。 */
    public BillingScanReceipt find(long actor, String operation) { return mapper.find(actor, operation); }
}
