package com.skillforge.core.billing.metering;

import com.skillforge.core.billing.export.PlatformUsageMapper;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.RunRequester;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Objects;

/** 仅Open Platform MESSAGE首次受理调用；写权借用受理SqlSession，不独立提交。 */
@Service
public class OpenPlatformRequestMetering {
    private final PlatformUsageRecorder usage;
    public OpenPlatformRequestMetering(PlatformUsageRecorder usage){this.usage=usage;}

    public void record(SqlSession sql,RunIdentity identity,String integrationId) {
        Objects.requireNonNull(sql);Objects.requireNonNull(identity);
        if(!(identity.requester() instanceof RunRequester.EndUser user)||!user.endUserId().equals(identity.owner().requireUser()))
            throw new IllegalArgumentException("usage_request_end_user_required");
        String input=identity.causationInputId().value(),eventId=input+":"+CostType.REQUEST.value();
        var trace=identity.trace();
        var context=CostContext.builder().sessionId(identity.owner().sessionRef().sessionId())
                .endUserId(identity.owner().requireUser().value()).agentId(Long.valueOf(identity.agentId().value()))
                .runId(identity.runRef().runId()).rootInputId(trace.rootInputId().value()).callPoint("open-platform.message")
                .traceContext(new TraceContext(input,trace.rootInputId().value(),trace.traceId(),trace.spanId(),eventId,null)).build();
        var event=CostRecorder.requestFact(identity.runRef().tenantId().value(),eventId,input,context,Instant.now());
        usage.attributeRequest(sql,event,integrationId);
        if(sql.getMapper(RequestCostMapper.class).insert(event)==0)return;
        var mapper=sql.getMapper(PlatformUsageMapper.class);
        if(!event.getGrantChain().isEmpty())mapper.insertGrantHops(event);
        mapper.insertPending(event);
    }

    /** 在Store建厂时安装已有归属与发布映射及唯一请求写入口。 */
    public static void configure(Configuration configuration) {
        IntegrationProvenanceService.configure(configuration);
        for(String resource:new String[]{"mapper/PlatformUsageMapper.xml","mapper/RequestCostMapper.xml"}) {
            if(configuration.isResourceLoaded(resource))continue;
            try(var input=OpenPlatformRequestMetering.class.getClassLoader().getResourceAsStream(resource)) {
                if(input==null)throw new IllegalStateException("usage_request_mapper_missing");
                new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
            }catch(java.io.IOException failure){throw new IllegalStateException("usage_request_mapper_failed",failure);}
        }
    }
}
