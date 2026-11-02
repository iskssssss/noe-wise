package com.skillforge.institution.browser;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 本地真实HTTP边界；不连接Browser服务或设备。 */
class BrowserClientDeviceOwnershipTest {
    @Test void missingServiceConfigurationReturnsExplicitUnavailable() {
        var client=new BrowserClient("http://127.0.0.1:8300","",new ObjectMapper());
        var error=assertThrows(ApiException.class,()->client.devices("1","2"));
        assertEquals(503,error.getStatus().value());
        assertEquals("浏览器服务尚未配置，请联系管理员",error.getMessage());
    }
    @Test void authorizationControlsForwardServerCorrelationId()throws Exception{
        var json=new ObjectMapper();var bodies=new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/browser",exchange->{
            try(var input=exchange.getRequestBody()){bodies.add(json.readTree(input));}
            byte[] result="{\"success\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();
        });server.start();
        try{
            var client=new BrowserClient("http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-token",json);
            String pair=java.util.UUID.randomUUID().toString(),revoke=java.util.UUID.randomUUID().toString();
            client.pair("1","2",pair);client.revoke("1","2","device",revoke);
            assertEquals(pair,bodies.getFirst().path("request_id").asText());assertEquals(revoke,bodies.getLast().path("request_id").asText());
            assertEquals("device",bodies.getLast().path("device").asText());assertFalse(bodies.getFirst().has("actor"));
        }finally{server.stop(0);}
    }
    @Test void onlyPreferenceForbiddenIsMappedToProductOwnershipDenial()throws Exception{
        var status=new AtomicInteger(403);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/browser",exchange->{
            try(var input=exchange.getRequestBody()){input.readAllBytes();}
            exchange.sendResponseHeaders(status.get(),-1);exchange.close();
        });
        server.start();
        try{
            var client=new BrowserClient("http://127.0.0.1:"+server.getAddress().getPort(),"synthetic-token",new ObjectMapper());
            for(boolean select:new boolean[]{true,false})
                assertEquals(403,assertThrows(ApiException.class,()->client.preference("1","2","foreign-device","名称",select)).getStatus().value());
            assertThrows(java.io.IOException.class,()->client.devices("1","2"));
            for(int failure:new int[]{401,500}){
                status.set(failure);
                assertEquals("browser_service_http_"+failure,assertThrows(java.io.IOException.class,()->client.preference("1","2","foreign-device",null,true)).getMessage());
            }
        }finally{server.stop(0);}
    }
}
