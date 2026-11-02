package com.skillforge.institution.browser;

import com.skillforge.common.context.TenantContext;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 短事务保存连接请求；不在数据库事务或行锁中等待浏览器网络。 */
@Service
public class BrowserConnectionStore {
    private final BrowserConnectionMapper mapper;
    private final TransactionTemplate transactions;

    /** 使用宿主 Mapper 与事务管理器，所有操作显式绑定 RLS 机构。 */
    public BrowserConnectionStore(BrowserConnectionMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }

    /** 准确读取请求，缺席返回 null。 */
    public BrowserConnectionRequest find(long tenant, String id) {
        return in(tenant, () -> mapper.find(tenant, id));
    }

    /** 创建并重读稳定请求；重试不能覆写已确认设备。 */
    public BrowserConnectionRequest create(BrowserConnectionRequest row) {
        return in(row.getTenantId(), () -> {
            mapper.insert(row);
            return mapper.find(row.getTenantId(), row.getRequestId());
        });
    }

    /** 推进准确旧版本；返回 false 表示选择或取消已抢先提交。 */
    public boolean save(BrowserConnectionRequest row) {
        return in(row.getTenantId(), () -> mapper.update(row, row.getRevision()) == 1);
    }

    /** 有界活动请求列表，读取不改变恢复身份。 */
    public List<BrowserConnectionRequest> pending(long tenant) {
        return pendingAfter(tenant, "");
    }

    /** 按稳定请求标识分批轮转，长时间未连接的旧等待不能阻塞后续请求。 */
    public List<BrowserConnectionRequest> pendingAfter(long tenant, String after) {
        return in(tenant, () -> mapper.pending(tenant, after, 50));
    }

    /** RLS 的线程上下文与数据库事务必须共同覆盖 SQL。 */
    private <T> T in(long tenant, Supplier<T> action) {
        return TenantContext.callWithTenant(Long.toString(tenant), () -> transactions.execute(ignored -> action.get()));
    }
}
