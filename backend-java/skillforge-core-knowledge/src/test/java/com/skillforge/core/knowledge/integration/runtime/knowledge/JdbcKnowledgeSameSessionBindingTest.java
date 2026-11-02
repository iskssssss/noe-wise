package com.skillforge.core.knowledge.integration.runtime.knowledge;


import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.TenantId;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 知识派发同会话入口只核对当前tenant，不创建事务、不重新绑定租户。 */
class JdbcKnowledgeSameSessionBindingTest {
    /** 同mapper重复配置不重复装载，也不接触外部连接。 */
    @Test void mapperRegistrationIsIdempotentAndIncludesTenantCheck() {
        var configuration=new Configuration();
        JdbcKnowledgeBindings.configure(configuration);JdbcKnowledgeBindings.configure(configuration);
        assertTrue(configuration.hasMapper(KnowledgeBindingMapper.class));
        assertTrue(configuration.hasStatement(KnowledgeBindingMapper.class.getName()+".currentTenant"));
        assertTrue(configuration.hasStatement(KnowledgeBindingMapper.class.getName()+".find"));
    }

    /** 已绑定其他tenant时不读目标、不改绑定；正确tenant缺目标则返回empty并由宿主拒绝。 */
    @Test void callerSessionTenantCannotBeReplacedByRequestedTenant() {
        var source=mock(DataSource.class);var bindings=new JdbcKnowledgeBindings(source);
        var session=mock(SqlSession.class);var mapper=mock(KnowledgeBindingMapper.class);
        when(session.getMapper(KnowledgeBindingMapper.class)).thenReturn(mapper);
        when(mapper.currentTenant()).thenReturn("2");
        var target=KnowledgeBindingDatabaseFixture.target();
        var failure=assertThrows(IllegalArgumentException.class,()->bindings.find(session,new TenantId("1"),target));
        assertEquals("knowledge_transaction_tenant_mismatch",failure.getMessage());
        verify(mapper).currentTenant();verifyNoMoreInteractions(mapper);verifyNoInteractions(source);
        clearInvocations(mapper);when(mapper.currentTenant()).thenReturn("1");
        assertTrue(bindings.find(session,new TenantId("1"),target).isEmpty());
        verify(session,never()).commit();verify(session,never()).rollback();verify(session,never()).close();
        verify(mapper,never()).bindTenant(anyString());verifyNoInteractions(source);
    }
}
