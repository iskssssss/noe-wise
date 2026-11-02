package com.skillforge.institution.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.runtime.session.SessionGoal;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 只有领域事务成功才返回冻结回执；缺项可补齐，不暴露内部文件系统路径。 */
@Component
public final class FinalizeTaskOutputsTool implements PlatformToolHandler {
    private final DeliverableService service; private final ObjectMapper json;
    public FinalizeTaskOutputsTool(DeliverableService service,ObjectMapper json){this.service=service;this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);}
    @Override public String toolName(){return "finalize_task_outputs";}
    @Override public Map<String,Object> execute(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception{
        return DeliverableMetrics.invoke("finalize",()->{
            final DeliverableContract.Finalize request;
            try { request=json.convertValue(args,DeliverableContract.Finalize.class); }
            catch(IllegalArgumentException invalid){return Map.of("success",false,"error","INVALID_FINALIZE_ARGUMENTS");}
            try{return service.finalizeOutputs(request,ArtifactClosingBudget.finalizationContext(context));}
            catch(OutputContractRejection rejected){return rejected.feedback();}
            catch(SessionGoal.Rejected rejected){return Map.of("success",false,"error",rejected.code());}
            catch(IllegalArgumentException rejected){return Map.of("success",false,"error",rejected.getMessage());}
            catch(org.springframework.dao.PessimisticLockingFailureException retry){return Map.of("success",false,"error","OUTPUT_CONCURRENT_RETRY");}
            catch(ArtifactPublicInspection.Rejection blocked){return Map.of("success",false,"error",PublicOutputGuard.REJECTION_CODE);}
            catch(java.io.IOException unreadable){return Map.of("success",false,"error","OUTPUT_FILE_UNREADABLE");}
            catch(SecurityException|com.skillforge.core.shared.exception.ApiException denied){return Map.of("success",false,"error","OUTPUT_FILE_ACCESS_DENIED");}
        });
    }
}
