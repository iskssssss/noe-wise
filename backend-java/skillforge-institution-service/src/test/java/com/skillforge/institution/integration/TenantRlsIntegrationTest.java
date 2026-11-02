package com.skillforge.institution.integration;
import com.skillforge.core.shared.config.SnowflakeIds;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.knowledge.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 多租户 RLS 隔离集成测试。
 *
 * <p>在两个租户各建一条知识库记录后，交叉查询应互不可见，
 * 验证 TenantRlsInterceptor 与 PostgreSQL 行级安全策略生效。
 */
@org.springframework.context.annotation.Import(KnowledgeEmbeddingFixture.Configuration.class)
class TenantRlsIntegrationTest extends AbstractIntegrationTest {

    /** 租户表映射器。 */
    @Autowired
    private TenantMapper tenantMapper;

    /** 套餐表映射器。 */
    @Autowired
    private PlanMapper planMapper;

    /** 知识库服务。 */
    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private PlatformModelCatalogService models;

    /** 租户 A ID。 */
    private String tenantA;

    /** 租户 B ID。 */
    private String tenantB;

    /**
     * 注册两个独立租户。
     */
    @BeforeEach
    void setUp() {
        ensureFreePlan(planMapper);
        tenantA = createTenant(tenantMapper, planMapper, "rls-a-" + SnowflakeIds.nextId());
        tenantB = createTenant(tenantMapper, planMapper, "rls-b-" + SnowflakeIds.nextId());
        TenantContext.clear();
    }

    /**
     * 租户 A 创建知识库后，租户 B 查询不到；回到 A 后可见。
     */
    @Test
    void knowledgeBaseIsIsolatedByTenant() {
        try {
            TenantContext.setTenantId(tenantA);
            KnowledgeEmbeddingFixture.select(models, tenantA, "http://127.0.0.1:9");
            knowledgeService.createBase(tenantA, "A 的知识库", "仅 A 可见");
            assertEquals(1, knowledgeService.listBases(tenantA).total());

            TenantContext.setTenantId(tenantB);
            assertEquals(0, knowledgeService.listBases(tenantB).total());

            TenantContext.setTenantId(tenantA);
            assertEquals(1, knowledgeService.listBases(tenantA).total());
        } finally {
            TenantContext.clear();
        }
    }

}
