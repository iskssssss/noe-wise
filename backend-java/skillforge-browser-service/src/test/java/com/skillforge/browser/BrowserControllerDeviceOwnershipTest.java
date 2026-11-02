package com.skillforge.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BrowserControllerDeviceOwnershipTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @Test void executeReturnsKnownSelectionRejectionAsReceiptAndKeepsServiceAuthentication()throws Exception{
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var engine=new BrowserEngineTest.Fake(root);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BrowserController(engine,"synthetic-service-token"))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        var request=new com.skillforge.execution.browser.BrowserRequest("1","2","no-default",null,"start",
                new com.skillforge.execution.browser.BrowserParameters.StartParameters(null),"session-1","run-1",java.time.Instant.now().plusSeconds(120));
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/browser/execute")
                .header("X-Browser-Service-Token","synthetic-service-token").contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        var receipt=json.readValue(response.getContentAsByteArray(),com.skillforge.execution.browser.BrowserReceipt.class);
        assertEquals("rejected",receipt.status());assertEquals("browser_selection_required",receipt.result().path("error").asText());
        assertNull(receipt.fingerprint());assertNull(receipt.requestToken());assertTrue(engine.calls.isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/browser/execute")
                .header("X-Browser-Service-Token","wrong-token").contentType("application/json").content(json.writeValueAsBytes(request)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        assertEquals(1,engine.receipts("1","2").size());
    }
    @Test void devicePreferenceOwnershipDenialIsForbiddenWithoutChangingOtherErrors()throws Exception{
        var engine=mock(BrowserEngine.class);
        var controller=new BrowserController(engine,"synthetic-service-token");
        var owner=new BrowserController.DeviceRequest("1","2","foreign-device","设备名称",null,null);
        doThrow(new IllegalArgumentException("browser_device_not_owned")).when(engine).preference("1","2","foreign-device",null,true);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.select("synthetic-service-token",owner)).getStatusCode().value());
        doThrow(new IllegalArgumentException("browser_device_not_owned")).when(engine).preference("1","2","foreign-device","设备名称",false);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.rename("synthetic-service-token",owner)).getStatusCode().value());
        doThrow(new IllegalArgumentException("browser_owner_required")).when(engine).preference("1","2","foreign-device",null,true);
        assertEquals("browser_owner_required",assertThrows(IllegalArgumentException.class,()->controller.select("synthetic-service-token",owner)).getMessage());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.select("wrong-token",owner)).getStatusCode().value());
    }
}
