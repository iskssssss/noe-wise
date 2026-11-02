package com.skillforge.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.mapper.AgentTemplateMapper;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class AgentTemplatePublicationReadTest {
    @Test void exactSnapshotUsesOnlyRequestedIdentityAndPreservesMissingAndCorruptFailures() {
        var mapper=mock(AgentTemplateMapper.class);
        var service=new AgentTemplateService(null,null,null,new ObjectMapper());
        ReflectionTestUtils.setField(service,"baseMapper",mapper);
        when(mapper.publication(31L,"1.0.0")).thenReturn("{\"id\":31,\"version\":\"1.0.0\",\"name\":\"原发布\"}");
        var target=service.requirePublication(31L,"1.0.0");assertEquals("原发布",target.getName());
        assertEquals("1.0.0",target.getVersion());verify(mapper).publication(31L,"1.0.0");
        verifyNoMoreInteractions(mapper);
        var missing=assertThrows(ApiException.class,()->service.requirePublication(31L,"missing"));
        assertEquals(HttpStatus.NOT_FOUND,missing.getStatus());assertEquals("发布历史不存在",missing.getMessage());
        when(mapper.publication(31L,"bad")).thenReturn("{\"definition\":42}");
        assertEquals("模板发布快照无法读取",assertThrows(IllegalStateException.class,()->service.requirePublication(31L,"bad")).getMessage());
    }
}
