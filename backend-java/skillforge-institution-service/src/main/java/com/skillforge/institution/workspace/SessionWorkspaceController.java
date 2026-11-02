package com.skillforge.institution.workspace;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 产品会话工作区只读投影，授权复用成果访问入口。 */
@RestController
@RequestMapping("/api/v1/chat/sessions")
public final class SessionWorkspaceController {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SessionWorkspaceController.class);
    private final SessionWorkspaceService workspaces;
    /** 装配已拥有会话授权的应用服务。 */
    public SessionWorkspaceController(SessionWorkspaceService workspaces){
        LOG.debug("工作区状态控制器装配进入");this.workspaces=workspaces;LOG.debug("工作区状态控制器装配退出");
    }
    /** 查询不会创建工作区；主子Session状态只显示当前有权披露的摘要。 */
    @Operation(summary="读取会话工作区状态",description="复核产品会话归属和当前成员来源授权；返回本地及备份修订，正文和目录内容不在此接口披露")
    @GetMapping("/workspace")
    public ApiResponse<Map<String,Object>> status(@RequestParam("sessionId") String sessionId)throws Exception {
        LOG.debug("工作区状态接口进入");
        try{return ApiResponses.wrap(workspaces.statuses(sessionId));}finally{LOG.debug("工作区状态接口退出");}
    }
}
