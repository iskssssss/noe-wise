package com.skillforge.core.foundation.security;
import com.skillforge.core.foundation.access.AccessChangeListener;
import com.skillforge.core.foundation.access.FoundationAccess;
import org.springframework.stereotype.Component;
/** IAM提交后幂等撤销设备；监听器依赖方向与授权查询分离。 */
@Component
public class SecurityAccessChangeListener implements AccessChangeListener {
 private final FoundationSecurityService security;
 /** 注册安全生命周期消费者。 */ public SecurityAccessChangeListener(FoundationSecurityService security){this.security=security;}
 @Override public void onChanged(FoundationAccess.Subject subject){security.revokeSubject(subject.platform(),subject.tenantId(),subject.memberId());}
}
