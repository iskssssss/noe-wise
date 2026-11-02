package com.skillforge.institution.runtime;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeSystemGrants;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 启动期从持久事实恢复 worker 轮询租户。
 *
 * <p>worker 只轮询已登记系统授权的租户，而授权是内存态，重启即清空。没有恢复步骤时，在途
 * 输入会停在处理中直到该租户恰好又来新请求。这里按未完成的 Inbox、Workflow和typed Run恢复授权，让在途会话
 * 自行继续，不依赖新流量。</p>
 */
@Service
public class AgentRuntimeWorkerTenantRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeWorkerTenantRecovery.class);

    private final RuntimeWorkerRecoveryMapper mapper;
    private final TransactionTemplate transactions;
    private final AgentRuntimeSystemGrants systemGrants;
    private final AgentRuntimeProperties properties;

    public AgentRuntimeWorkerTenantRecovery(RuntimeWorkerRecoveryMapper mapper, PlatformTransactionManager transactionManager,
            AgentRuntimeSystemGrants systemGrants, AgentRuntimeProperties properties) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.systemGrants = Objects.requireNonNull(systemGrants);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = 0;
        for (String tenant : activeTenants()) {
            long unfinished = unfinishedInputs(tenant);
            if (unfinished == 0) {
                continue;
            }
            // 与 Chat 入口登记同一 worker 主体和同一组权限，避免授权内容漂移导致失败关闭。
            systemGrants.register(new TenantId(tenant), properties.getWorkerId(),
                    AgentRuntimeHostProvisioner.RUNTIME_SCOPES);
            recovered++;
            log.info("Agent Runtime 在途租户授权已恢复 tenant={} unfinishedInputs={}", tenant, unfinished);
        }
        if (recovered == 0) {
            log.info("Agent Runtime 无在途输入需要恢复轮询");
        }
    }

    private List<String> activeTenants() {
        return mapper.activeTenants();
    }

    /** RLS 拦截器逐条 SQL 读取线程租户；线程作用域与事务绑定必须共同覆盖统计。 */
    private long unfinishedInputs(String tenant) {
        Long count = TenantContext.callWithTenant(tenant, () -> transactions.execute(ignored -> {
            mapper.bindTenant(tenant);
            return mapper.unfinished();
        }));
        return count == null ? 0 : count;
    }
}
