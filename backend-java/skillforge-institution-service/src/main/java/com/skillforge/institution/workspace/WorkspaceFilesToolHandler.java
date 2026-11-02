package com.skillforge.institution.workspace;

import com.skillforge.core.chat.contract.ProcessPresentation;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 持久工作区文件工具；公开过程只有状态说明，正文只返回授权模型观察。 */
@Component
public final class WorkspaceFilesToolHandler implements PlatformToolHandler {
    private static final Logger LOG=LoggerFactory.getLogger(WorkspaceFilesToolHandler.class);
    private final SessionWorkspaceService workspaces;
    public WorkspaceFilesToolHandler(SessionWorkspaceService workspaces){this.workspaces=workspaces;}
    /** 固定平台能力名用于冻结挂载。 */
    @Override public String toolName(){return "workspace_files";}
    /** 受控准入错误返回可修正失败；发送后的未知异常保留Provider未知语义。 */
    @Override public Map<String,Object> execute(Map<String,Object> args,PlatformToolExecutionContext context)throws Exception {
        LOG.debug("工作区文件工具进入 invocationId={}",context.invocationId());
        try {
            if(context.programChild())return rejected("workspace_program_child_forbidden");
            var result=new LinkedHashMap<>(workspaces.files(args,context));
            // Sandbox完整2xx文件回执不含success；显式失败仍保持失败，未知异常继续向Provider传播。
            boolean completed=!Boolean.FALSE.equals(result.get("success"))&&!result.containsKey("error");
            result.put("public_presentation",ProcessPresentation.of("工作区文件",completed?"工作区文件操作完成。":"工作区文件操作未完成。"));
            return result;
        }catch(SecurityException rejected){return rejected("workspace_access_rejected");}
        catch(IllegalArgumentException rejected){return rejected("workspace_arguments_invalid");}
        catch(IllegalStateException rejected){return rejected("workspace_unavailable");}
        finally{LOG.debug("工作区文件工具退出 invocationId={}",context.invocationId());}
    }
    /** 固定错误码和状态说明避免回显文件、身份或来源异常。 */
    private static Map<String,Object> rejected(String reason) {
        return Map.of("success",false,"error",reason,"public_presentation",ProcessPresentation.of("工作区文件","工作区文件操作未完成。"));
    }
}
