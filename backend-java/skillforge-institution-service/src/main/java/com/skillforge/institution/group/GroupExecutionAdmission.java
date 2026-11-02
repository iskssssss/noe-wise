package com.skillforge.institution.group;

import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import java.util.List;

/**
 * 将已持久化的群触发交给唯一 Runtime 宿主准入，不承担 Run 执行或驱动。
 *
 * <p>候选身份来自群领域持久事实；宿主负责构造正式 Admission，并在 Runtime 自有事务中
 * 调用领域 guard，使触发受理、单槽占用与 typed Run 一起提交。
 */
public interface GroupExecutionAdmission {

    /**
     * 准入原触发，成功后返回其稳定的 input/Run 身份。
     *
     * @param candidate 已持久化触发及其来源序号截止的群历史
     * @param guard 预算根锁之后执行的领域复核；使用同一 SqlSession 锁定 slot 和队首、
     *              核验当前资格，并写入 accepted 与单槽占用，不另开事务或自行提交
     */
    Admitted admit(Candidate candidate, JdbcExecutionStore.AdmissionGuard guard);

    /** 已提交的不可变群来源；作者与有序内容使用领域保存的规范 JSON。 */
    record Source(
            String sourceMessageId,
            long sourceSequence,
            String authorJson,
            String contentJson) { }

    /**
     * 原触发的可信候选，不从外部请求正文重新推断 Tenant、执行域或群 Session。
     *
     * @param requesterEndUserId 原来源作者，同时也是本触发的原请求者
     * @param principalId 受理该触发的稳定服务主体身份；准入时仍须核当前状态与 scope
     * @param credentialVersion 原受理凭据版本，仅作审计；轮换后由宿主解析当前版本验证新 Run
     * @param history 截止 sourceSequence 的已提交来源，后续消息不会无声并入该 Run
     */
    record Candidate(
            String tenantId,
            String integrationId,
            String groupBindingId,
            String sessionId,
            String businessScopeId,
            String triggerId,
            long triggerSequence,
            String sourceMessageId,
            long sourceSequence,
            long requesterEndUserId,
            long agentId,
            String principalId,
            long credentialVersion,
            List<Source> history) {

        public Candidate {
            history = List.copyOf(history);
        }
    }

    /** 已提交准入的身份回执；表示 Run 已受理，不表示模型执行或业务效果已经完成。 */
    record Admitted(String inputId, String runId) { }
}
