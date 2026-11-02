package com.skillforge.core.foundation.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.persistence.OperationsMapper;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 真实Mapper装配元数据及授权后探测的up/down映射；不连接数据库。 */
class FoundationOperationsStatusTest {
    @Test
    @SuppressWarnings("unchecked")
    void readsMapperAfterAuthorizationAndKeepsUnexpectedResultsDown() {
        for (Integer answer : new Integer[]{1, 0, null}) {
            var access = mock(FoundationAccess.class);
            var source = mock(DataSource.class);
            var mapper = mock(OperationsMapper.class);
            when(mapper.probeDatabase()).thenReturn(answer);
            var status = new FoundationOperationsStatus(access, source, mapper,
                    mock(ObjectProvider.class), mock(ObjectProvider.class));
            var probe = status.status().probes().stream().filter(value -> value.name().equals("postgresql")).findFirst().orElseThrow();
            assertEquals(Integer.valueOf(1).equals(answer) ? "up" : "down", probe.status());
            var ordered = inOrder(access, mapper);
            ordered.verify(access).require(FoundationOperationsStatus.READ);
            ordered.verify(mapper).probeDatabase();
            verifyNoInteractions(source);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectionOrMapperFailureStaysDownWithoutLeakingCause() {
        var mapper = mock(OperationsMapper.class);
        when(mapper.probeDatabase()).thenThrow(new IllegalStateException("private datasource detail"));
        var status = new FoundationOperationsStatus(mock(FoundationAccess.class), mock(DataSource.class), mapper,
                mock(ObjectProvider.class), mock(ObjectProvider.class));
        var probe = status.status().probes().stream().filter(value -> value.name().equals("postgresql")).findFirst().orElseThrow();
        assertEquals("down", probe.status());
        assertEquals("数据库连接或只读探测失败", probe.detail());
    }

    @Test
    void actualXmlHasTwoSecondTimeoutAndNoCachedHealthAnswer() throws Exception {
        var configuration = new Configuration();
        String resource = "mapper/foundation/OperationsMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var statement = configuration.getMappedStatement(OperationsMapper.class.getName() + ".probeDatabase");
        assertEquals(2, statement.getTimeout());
        assertEquals("SELECT 1", statement.getBoundSql(null).getSql().trim());
        assertFalse(statement.isUseCache());
        assertTrue(statement.isFlushCacheRequired());
    }
}
