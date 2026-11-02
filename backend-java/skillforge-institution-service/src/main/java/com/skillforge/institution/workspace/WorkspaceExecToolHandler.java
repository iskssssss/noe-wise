package com.skillforge.institution.workspace;

import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 完整Bash入口，全部任务控制复用代码执行管线。 */
@Component
public final class WorkspaceExecToolHandler implements PlatformToolHandler {
    private static final Logger LOG=LoggerFactory.getLogger(WorkspaceExecToolHandler.class);
    private final SessionWorkspaceService workspaces;
    public WorkspaceExecToolHandler(SessionWorkspaceService workspaces){this.workspaces=workspaces;}
    /** 固定平台能力名用于冻结挂载。 */
    @Override public String toolName(){return "workspace_exec";}
    /** 标准流留在模型观察中；公开聊天只展示执行事实。 */
    @Override public Map<String,Object> execute(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区命令工具进入 invocationId={}",context.invocationId());
        try {
            if(context.programChild())return rejected("workspace_program_child_forbidden");
            var result=new LinkedHashMap<>(workspaces.exec(args,context));
            result.put("public_presentation",ProcessPresentation.of("工作区命令",Boolean.TRUE.equals(result.get("success"))?"工作区命令执行完成。":"工作区命令执行未完成。"));
            return result;
        }catch(SecurityException rejected){return rejected("workspace_access_rejected");}
        catch(IllegalArgumentException rejected){return rejected("workspace_arguments_invalid");}
        catch(IllegalStateException rejected){return rejected("workspace_unavailable");}
        finally{LOG.debug("工作区命令工具退出 invocationId={}",context.invocationId());}
    }
    /** 准入失败固定错误码不输出Shell正文和平台身份。 */
    private static Map<String,Object> rejected(String reason) {
        return Map.of("success",false,"error",reason,"public_presentation",ProcessPresentation.of("工作区命令","工作区命令执行未完成。"));
    }
}
