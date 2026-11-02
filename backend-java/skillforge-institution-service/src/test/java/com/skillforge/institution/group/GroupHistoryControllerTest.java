package com.skillforge.institution.group;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.shared.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 查询参数按白名单及单值严格绑定，不支持存量分页别名。 */
class GroupHistoryControllerTest {
    @Test void unknownAndDuplicateQueryParametersAreRejectedWithoutReadingHistory() {
        var service=mock(GroupHistoryService.class);var controller=new GroupHistoryController(service);
        var unknown=new MockHttpServletRequest();unknown.addParameter("limit","10");
        assertEquals(400,assertThrows(ApiException.class,()->controller.history("41","31",null,null,unknown)).getStatus().value());
        var duplicate=new MockHttpServletRequest();duplicate.addParameter("page_size","1","2");
        assertEquals(400,assertThrows(ApiException.class,()->controller.events("41","31",null,"1",duplicate)).getStatus().value());
        verifyNoInteractions(service);
    }
}
