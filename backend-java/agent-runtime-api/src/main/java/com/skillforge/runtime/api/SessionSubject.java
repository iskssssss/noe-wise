package com.skillforge.runtime.api;

/** Session 的固定归属对象；群归属没有 owner EndUser，不能用群主或触发者代替。 */
public sealed interface SessionSubject permits SessionSubject.User, SessionSubject.Group {
    /**
     * 个人会话归属，仍必须保留真实终端用户。
     * @param endUserId 创建时冻结的真实用户
     */
    record User(EndUserId endUserId) implements SessionSubject {
        /** 个人归属不接受缺失用户。 */
        public User { endUserId = ApiContracts.requireNonNull(endUserId, "endUserId"); }
    }

    /**
     * 群共享会话归属，与当前成员、触发者和选用的服务 Agent 无关。
     * @param groupBindingRef 创建时冻结的群生命周期引用
     */
    record Group(GroupBindingRef groupBindingRef) implements SessionSubject {
        /** 群归属不接受没有机构的裸外部群号。 */
        public Group { groupBindingRef = ApiContracts.requireNonNull(groupBindingRef, "groupBindingRef"); }
    }

    /** 从真实用户构造封闭的个人分支。 */
    static User user(EndUserId endUserId) { return new User(endUserId); }
    /** 从机构内生命周期引用构造封闭的群分支。 */
    static Group group(GroupBindingRef groupBindingRef) { return new Group(groupBindingRef); }
}
