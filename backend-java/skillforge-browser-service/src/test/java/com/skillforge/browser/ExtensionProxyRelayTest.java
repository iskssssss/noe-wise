package com.skillforge.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;

class ExtensionProxyRelayTest {
    private WebSocketSession downstream(){
        var session=mock(WebSocketSession.class);
        when(session.getId()).thenReturn("downstream");
        when(session.getUri()).thenReturn(URI.create("ws://127.0.0.1/extension/owner"));
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(session.isOpen()).thenReturn(true);return session;
    }
    @Test void daemonUnauthorizedClosesDownstreamWithoutRetryOrForwarding()throws Exception{
        var engine=mock(BrowserEngine.class);when(engine.proxyPort("owner")).thenReturn(12345);
        var builder=mock(WebSocket.Builder.class);var response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(builder.buildAsync(any(),any())).thenReturn(CompletableFuture.failedFuture(new WebSocketHandshakeException(response)));
        var relay=new ExtensionProxy(engine).new Relay(()->builder);var downstream=downstream();
        assertDoesNotThrow(()->relay.afterConnectionEstablished(downstream));
        verify(downstream).close(CloseStatus.POLICY_VIOLATION);
        assertDoesNotThrow(()->relay.handleTextMessage(downstream,new TextMessage("late message")));
        assertDoesNotThrow(()->relay.handleBinaryMessage(downstream,new BinaryMessage(new byte[]{1})));
        verify(builder,times(1)).buildAsync(any(),any());
    }
    @Test void successfulBindingForwardsAndClosesOnlyItsOwnSocket()throws Exception{
        var engine=mock(BrowserEngine.class);when(engine.proxyPort("owner")).thenReturn(12345);
        var builder=mock(WebSocket.Builder.class);var socket=mock(WebSocket.class);
        when(builder.buildAsync(any(),any())).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendText(anyString(),anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendBinary(any(),anyBoolean())).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendClose(anyInt(),anyString())).thenReturn(CompletableFuture.completedFuture(socket));
        var relay=new ExtensionProxy(engine).new Relay(()->builder);var downstream=downstream();
        relay.afterConnectionEstablished(downstream);
        relay.handleTextMessage(downstream,new TextMessage("normal"));
        relay.handleBinaryMessage(downstream,new BinaryMessage(new byte[]{1}));
        verify(socket).sendText("normal",true);verify(socket).sendBinary(any(ByteBuffer.class),eq(true));
        relay.afterConnectionClosed(downstream,CloseStatus.NORMAL);
        verify(socket).sendClose(1000,"");verify(socket,never()).abort();verify(downstream,never()).close(any());
    }
    @Test void disconnectedDownstreamCannotRetainCompletedUpstreamHandshake()throws Exception{
        var engine=mock(BrowserEngine.class);when(engine.proxyPort("owner")).thenReturn(12345);
        var builder=mock(WebSocket.Builder.class);var socket=mock(WebSocket.class);
        when(builder.buildAsync(any(),any())).thenReturn(CompletableFuture.completedFuture(socket));
        var relay=new ExtensionProxy(engine).new Relay(()->builder);var downstream=downstream();when(downstream.isOpen()).thenReturn(false);
        relay.afterConnectionEstablished(downstream);verify(socket).abort();
        relay.afterConnectionClosed(downstream,CloseStatus.NORMAL);verify(socket,never()).sendClose(anyInt(),anyString());
    }
}
