package com.skillforge.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.mapper.AgentTemplateMapper;
import com.skillforge.core.agent.model.AgentTemplateDisplay;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AgentTemplateDisplayServiceTest {
    @Test void emptySkipsSqlAndDistinctIdsUseOneNarrowMapperRead() {
        var mapper=mock(AgentTemplateMapper.class);
        var service=new AgentTemplateService(null,null,null,new ObjectMapper(),null);
        ReflectionTestUtils.setField(service,"baseMapper",mapper);
        assertTrue(service.displays(List.of()).isEmpty());verifyNoInteractions(mapper);
        when(mapper.selectDisplayByIds(List.of(31L,32L,33L,99L))).thenReturn(List.of(
                new AgentTemplateDisplay(31L,"一","1"),new AgentTemplateDisplay(32L,"二","2"),new AgentTemplateDisplay(33L,"三","3")));
        var result=service.displays(List.of(31L,32L,33L,31L,99L));
        assertEquals(Map.of(31L,new AgentTemplateDisplay(31L,"一","1"),32L,new AgentTemplateDisplay(32L,"二","2"),33L,new AgentTemplateDisplay(33L,"三","3")),result);
        verify(mapper).selectDisplayByIds(List.of(31L,32L,33L,99L));
    }
}
