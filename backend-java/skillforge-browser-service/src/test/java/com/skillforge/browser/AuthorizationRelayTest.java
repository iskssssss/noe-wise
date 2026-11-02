package com.skillforge.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthorizationRelayTest {
    @Test void successfulOfficialPairResponseIsDeliveredEvenWhenResultAuditFails()throws Exception{
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] response="{\"device_id\":\"owned\",\"expires_at\":\"2100-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/",exchange->{try(exchange){exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}});server.start();
        var engine=mock(BrowserEngine.class);String key="a".repeat(64),id=UUID.randomUUID().toString();
        when(engine.proxyPort(key)).thenReturn(server.getAddress().getPort());when(engine.authorizationExchangeStarted(key,"pair")).thenReturn(id);
        doThrow(new java.io.IOException("disk full")).when(engine).authorizationExchangeFinished(eq(key),eq(id),eq("pair"),eq(200),any());
        var relay=new ExtensionProxy.AuthorizationRelay(engine);
        try{
            var result=new MockHttpServletResponse();
            relay.authorize(key,"{\"action\":\"pair\",\"next_token\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8),new MockHttpServletRequest(),result);
            assertEquals(200,result.getStatus());assertArrayEquals(response,result.getContentAsByteArray());
            verify(engine).authorizationExchangeFinished(eq(key),eq(id),eq("pair"),eq(200),eq(response));
        }finally{server.stop(0);relay.close();}
    }

    @Test void successfulOfficialPairResponseIsDeliveredEvenWhenConnectionAssociationFails()throws Exception{
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        byte[] response="{\"device_id\":\"exact-device\",\"next_token\":\"PRIVATE\"}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/",exchange->{try(exchange){exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}});server.start();
        var engine=mock(BrowserEngine.class);String key="a".repeat(64),id=UUID.randomUUID().toString(),connectionId=UUID.randomUUID().toString();
        String authorization="Bearer "+"A".repeat(43);
        when(engine.proxyPort(key)).thenReturn(server.getAddress().getPort());when(engine.authorizationExchangeStarted(key,"pair")).thenReturn(id);
        when(engine.connectionExchangeStarted(key,"pair",authorization)).thenReturn(connectionId);
        doThrow(new java.io.IOException("disk full")).when(engine).connectionExchangeFinished(eq(key),eq(connectionId),eq(200),any());
        var relay=new ExtensionProxy.AuthorizationRelay(engine);
        try{
            var request=new MockHttpServletRequest();request.addHeader("Authorization",authorization);var result=new MockHttpServletResponse();
            relay.authorize(key,"{\"action\":\"pair\",\"next_token\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8),request,result);
            assertEquals(200,result.getStatus());assertArrayEquals(response,result.getContentAsByteArray());
            verify(engine).connectionExchangeFinished(eq(key),eq(connectionId),eq(200),eq(response));
            verify(engine).authorizationExchangeFinished(eq(key),eq(id),eq("pair"),eq(200),eq(response));
        }finally{server.stop(0);relay.close();}
    }
}
