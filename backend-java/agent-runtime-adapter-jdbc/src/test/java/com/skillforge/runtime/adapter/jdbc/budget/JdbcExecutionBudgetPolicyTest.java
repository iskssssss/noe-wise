package com.skillforge.runtime.adapter.jdbc.budget;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort.*;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 平台存储只接受有限预算；拒绝必须发生在取得连接或写入调用方事务之前。 */
class JdbcExecutionBudgetPolicyTest {
    @Test void observeOnlyIsRejectedBeforeStandaloneOrJoinedTransactionAccess() {
        var source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> { throw new AssertionError("unexpected_datasource_access:" + method.getName()); });
        var session = (SqlSession) Proxy.newProxyInstance(SqlSession.class.getClassLoader(), new Class<?>[]{SqlSession.class},
                (proxy, method, args) -> { throw new AssertionError("unexpected_session_access:" + method.getName()); });
        var budgets = new JdbcExecutionBudgets(source);
        var run = new RunRef(new TenantId("1"), "observed-platform");
        var registration = new Registration(run, run, Role.PRIMARY, new Policy.ObserveOnly(Scenario.STANDARD, "v1"));
        assertEquals("budget_policy_not_supported", assertThrows(IllegalArgumentException.class,
                () -> budgets.register(registration)).getMessage());
        assertEquals("budget_policy_not_supported", assertThrows(IllegalArgumentException.class,
                () -> budgets.register(session, registration)).getMessage());
    }
}
