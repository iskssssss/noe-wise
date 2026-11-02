package com.skillforge.institution.workspace;

import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 工作区快照工具；接受备份请求不等于云端备份已完成。 */
@Component
public final class WorkspaceCheckpointToolHandler implements PlatformToolHandler {
    private static final Logger LOG=LoggerFactory.getLogger(WorkspaceCheckpointToolHandler.class);
    private final SessionWorkspaceService workspaces;
    public WorkspaceCheckpointToolHandler(SessionWorkspaceService workspaces){this.workspaces=workspaces;}
    /** 固定平台能力名用于冻结挂载。 */
    @Override public String toolName(){return "workspace_checkpoint";}
    /** 返回Sandbox备份事实，公开状态不宣称仅排队的上传已备份。 */
    @Override public Map<String,Object> execute(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区检查点工具进入 invocationId={}",context.invocationId());
        try {
            if(context.programChild())return rejected("workspace_program_child_forbidden");
            var result=new LinkedHashMap<>(workspaces.checkpoint(args,context));
            // 控制请求的完整回执与云端备份终态不同；pending仅表示受理，未知异常不得转换成失败回执。
            boolean completed=!Boolean.FALSE.equals(result.get("success"))&&!result.containsKey("error");
            String summary=completed?switch(String.valueOf(result.get("phase"))) {
                case "backup_pending","backing_up"->"备份请求已受理，云端备份正在处理。";
                case "backup_failed"->"云端备份失败，本地工作区状态以回执为准。";
                case "local_only"->"当前工作区已保存到本地，云端备份未启用。";
                case "ready"->"工作区操作已处理，当前云端备份已完成。";
                default->"工作区快照操作已处理，备份状态以回执为准。";
            }:"工作区快照操作未完成。";
            result.put("public_presentation",ProcessPresentation.of("工作区快照",summary));
            return result;
        }catch(SecurityException rejected){return rejected("workspace_access_rejected");}
        catch(IllegalArgumentException rejected){return rejected("workspace_arguments_invalid");}
        catch(IllegalStateException rejected){return rejected("workspace_unavailable");}
        finally{LOG.debug("工作区检查点工具退出 invocationId={}",context.invocationId());}
    }
    /** 状态错误码固定，备份异常不泄露云端请求正文。 */
    private static Map<String,Object> rejected(String reason) {
        return Map.of("success",false,"error",reason,"public_presentation",ProcessPresentation.of("工作区快照","工作区快照操作未完成。"));
    }
}
