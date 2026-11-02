package com.skillforge.core.foundation.access;

/** 安全会话等消费者在授权事务提交后撤销主体状态，不参与 access 的写事务。 */
public interface AccessChangeListener {
    /** 已提交的角色、状态或组织归属改变；监听者必须幂等。 */
    void onChanged(FoundationAccess.Subject subject);
}
