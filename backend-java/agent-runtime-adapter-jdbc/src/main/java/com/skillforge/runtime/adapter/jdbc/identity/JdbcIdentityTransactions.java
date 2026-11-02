package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.util.Optional;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/** 宿主身份事实参与 JDBC 原业务事务的边界；实现不得重绑租户、另开连接或提交。 */
public interface JdbcIdentityTransactions extends IdentityPolicyPort {
    /** 工厂构建时安装宿主拥有的映射；执行事务中不得变更映射。 */
    void configureTransaction(Configuration configuration);
    /** 在传入事务读取当前授权，不能以历史验证事实替代当前权限。 */
    AuthorizationResult authorize(SqlSession session, AuthorizationRequest request);
    /** 读取原验证全值，不产生新身份也不授予当前权限。 */
    Optional<VerifiedIdentity> originalVerification(SqlSession session, SessionOwner owner, ResultRef reference);
    /** 核对不可变来源与当前已验证调用者，不能以 owner 相同替代原调用者。 */
    boolean matchesOriginalCaller(SqlSession session, SessionOwner owner, ResultRef originalReference,
            VerifiedIdentity current);
}
