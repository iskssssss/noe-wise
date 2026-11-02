package com.skillforge.institution.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.institution.artifact.ArtifactAccess;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 授权控制调用只派发一次；独立提交审计意图，未知结果不推断撤销成功。 */
@Service
public class BrowserDeviceAuditService {
    private final AuditService audit;
    private final BrowserClient client;
    private final ArtifactAccess access;
    private final TransactionTemplate transaction;

    public BrowserDeviceAuditService(AuditService audit,BrowserClient client,ArtifactAccess access,PlatformTransactionManager manager){
        this.audit=audit;this.client=client;this.access=access;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public JsonNode pair(String tenant,String user)throws Exception{return dispatch(tenant,user,null,"pair",null);}
    /** 有准确工具等待时，控制审计与连接关联在同一次官方配对中传递。 */
    public JsonNode pair(String tenant,String user,String connectionRequestId)throws Exception{return dispatch(tenant,user,null,"pair",connectionRequestId);}
    public JsonNode revoke(String tenant,String user,String device)throws Exception{
        if(device==null||device.isBlank()||device.startsWith("-"))throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"须提供有效设备标识");
        return dispatch(tenant,user,device,"revoke",null);
    }
    private JsonNode dispatch(String tenant,String user,String device,String operation,String connectionRequestId)throws Exception{
        String channel=ChannelContext.getChannelId();String actor=channel==null?access.member():"channel:"+channel;
        String request=UUID.randomUUID().toString(),owner=tag(tenant+":"+user),target=device==null?"none":tag(tenant+":"+user+":"+device);
        record(tenant,actor,operation,request,owner,target,"started");
        JsonNode result;
        try{result=device==null?(connectionRequestId==null?client.pair(tenant,user,request):client.pair(tenant,user,request,connectionRequestId)):client.revoke(tenant,user,device,request);}
        catch(Exception failure){
            try{record(tenant,actor,operation,request,owner,target,"unknown");}catch(RuntimeException persistence){failure.addSuppressed(persistence);}
            throw failure;
        }
        record(tenant,actor,operation,request,owner,target,"succeeded");return result;
    }
    private void record(String tenant,String actor,String operation,String request,String owner,String device,String status){
        transaction.executeWithoutResult(ignored->audit.record(tenant,actor,"browser.authorization."+operation,"browser_authorization",request,
                "个人浏览器授权控制",Map.of("request_id",request,"owner_tag",owner,"device_tag",device,"status",status)));
    }
    private static String tag(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
