package com.skillforge.core.chat.session;

/**
 * 同一次序号行锁取得的投影事实，仅在所属投影事务内使用。
 * 回执和成功水位分别保留数据库空值语义，不能从一者推导另一者已成功。
 * 初次投影的八个状态列全部为空，非空序号使MyBatis仍能构造存在的锁行，避免误判为缺行。
 *
 * @param nextSequence 锁行的非空输出序号，仅用于完整行映射；新输出序号仍由reserve分配
 * @param sourceSequence 处理来源序号；为空表示尚无处理回执
 * @param sourceEventId 处理来源事件身份；无回执时为空
 * @param policyVersion 处理回执政策，与成功水位的checkpointPolicy独立
 * @param errorCode 当前来源的永久拒绝原因；成功或无回执时为空
 * @param checkpointSequence 成功水位序号；为空表示尚未成功投影
 * @param checkpointEventId 成功水位事件身份；无成功水位时为空
 * @param checkpointPolicy 成功水位政策，与处理回执的policyVersion独立
 * @param checkpointStateJson 成功投影状态；无成功水位时为空
 */
public record SessionOutboxProjectionState(long nextSequence, Long sourceSequence, String sourceEventId,
        String policyVersion, String errorCode, Long checkpointSequence, String checkpointEventId,
        String checkpointPolicy, String checkpointStateJson) {
    /** 尚无处理来源时不构造成功或拒绝回执。 */
    public SessionOutboxProjectionRecord projectionRecord() {
        return sourceSequence == null ? null
                : new SessionOutboxProjectionRecord(sourceSequence, sourceEventId, policyVersion, errorCode);
    }

    /** 尚未成功投影时无checkpoint；存在时继续使用原完整性校验。 */
    public SessionOutboxProjectionCheckpoint projectionCheckpoint() {
        return checkpointSequence == null ? null
                : new SessionOutboxProjectionCheckpoint(checkpointSequence, checkpointEventId, checkpointPolicy, checkpointStateJson);
    }
}
