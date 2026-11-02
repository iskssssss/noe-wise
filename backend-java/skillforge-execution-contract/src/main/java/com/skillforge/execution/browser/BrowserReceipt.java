package com.skillforge.execution.browser;
import com.fasterxml.jackson.databind.JsonNode;
/** 固定归属与来源的持久回执；上游业务结果保留其动态 JSON。 */
public record BrowserReceipt(String fingerprint,String invocation,String operation,String task,String session,String run,
        String createdAt,String status,String requestToken,String browser,BrowserBinding binding,JsonNode result) {
    public BrowserReceipt state(String status,JsonNode result){return new BrowserReceipt(fingerprint,invocation,operation,task,session,run,createdAt,status,requestToken,browser,binding,result);}
    public BrowserReceipt bound(BrowserBinding binding){return new BrowserReceipt(fingerprint,invocation,operation,task,session,run,createdAt,"bound",requestToken,browser,binding,result);}
    public BrowserReceipt publicView(){return new BrowserReceipt(null,invocation,operation,task,session,run,createdAt,status,null,browser,binding,result);}
    @Override public String toString(){return "BrowserReceipt[operation="+operation+", status="+status+"]";}
    public record BrowserBinding(@com.fasterxml.jackson.annotation.JsonProperty("session_id")String sessionId,
        @com.fasterxml.jackson.annotation.JsonProperty("browser_instance_id")String browserInstanceId,
        @com.fasterxml.jackson.annotation.JsonProperty("agent_window_id")Long agentWindowId){}
}
