package com.skillforge.institution.runtime;

import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.common.context.ServicePrincipalContext;

/** 产品运行控制入口；只接受已由会话服务核实的归属，取消仍须在持久业务边界检查当前权限。 */
public interface AgentRuntimeRunControls {
    /** 持久化取消并通知活动执行停止派发；在途外部结果继续保留。 */
    ChatResult cancelChat(SessionView session, String runId);
    /** 读取已保存状态，不恢复、不领取和不发起外部调用。 */
    ChatResult chatRunStatus(SessionView session, String runId);
    /** 稳定补充输入只进入原Run，不恢复等待或创建新Run。 */
    AgentRuntimeExecutionSteering.Snapshot steerChat(SessionView session, String runId, String inputId, String text);
    /** 精确实际模型请求和已采纳结果拥有消费状态，查询不唤醒执行。 */
    AgentRuntimeExecutionSteering.Snapshot steeringChat(SessionView session, String runId);
    /** 外部接入沿真实integration认证，不借Chat服务主体扩大权限。 */
    AgentRuntimeExecutionSteering.Snapshot steerOpenPlatform(ServicePrincipalContext.Identity principal,
            SessionView session, String runId, String inputId, String text);
}
