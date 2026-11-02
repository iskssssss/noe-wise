package com.skillforge.core.foundation.catalog;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** catalog 的可信主体和存储作用域；临时平台租户查询始终恢复线程绑定。 */
@Component
public final class CatalogScope {
    private final FoundationAccess access;
    /** 使用统一授权入口，不从请求复制身份。 */
    public CatalogScope(FoundationAccess access) { this.access = access; }
    /** 当前管理范围的稳定键；平台与每个机构隔离。 */
    public String owner() { var s=access.currentSubject(); return s.platform()?"platform":"tenant:"+tenant(); }
    /** 当前主体 ID，供 CAS 写入记录可信修改者。 */
    public String actor() { return access.currentSubject().memberId(); }
    /** 是否为平台认证主体。 */
    public boolean platform() { return access.currentSubject().platform(); }
    /** 当前机构 ID；平台无默认机构，缺失绑定明确拒绝。 */
    public String tenant() { var s=access.currentSubject(); if(s.platform() || s.tenantId()==null) throw forbidden(); return id(s.tenantId()); }
    /** 平台显式目标机构，或机构自己的 ID；机构不能伪造目标。 */
    public String target(String requested) {
        String target=id(requested); access.currentSnapshot(); var s=access.currentSubject();
        if(!s.platform() && !target.equals(s.tenantId())) throw forbidden();
        return target;
    }
    /** 给 RLS 绑定经主体校验的目标，在异常和嵌套调用后恢复原值。 */
    public <T> T inTenant(String requested, Supplier<T> action) {
        String target=target(requested), previous=TenantContext.getTenantId();
        try { TenantContext.setTenantId(target); return action.get(); }
        finally { if(previous==null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
    }
    /** 平台管理入口；动作权限仍由具体服务检查。 */
    public void requirePlatform() { if(!platform()) throw forbidden(); }
    /** 十进制雪花 ID 的解析边界，传输始终保留字符串。 */
    public static String id(String value) {
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw CatalogValidation.invalid("id 必须是正十进制字符串");
        try { Long.parseLong(value); } catch(NumberFormatException e) { throw CatalogValidation.invalid("id 超出范围"); }
        return value;
    }
    /** 不披露跨作用域资源的存在性。 */
    private static ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN,"catalog_scope_forbidden","当前主体不能访问该范围",false); }
}
