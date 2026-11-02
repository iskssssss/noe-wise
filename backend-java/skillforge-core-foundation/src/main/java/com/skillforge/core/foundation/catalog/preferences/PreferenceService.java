package com.skillforge.core.foundation.catalog.preferences;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.CatalogValidation;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.skillforge.core.foundation.catalog.preferences.PreferenceContracts.*;

/** 机构展示品牌和本人偏好；刻意不写运营版本配置或租户 settings JSON。 */
@Service
public class PreferenceService {
    private final PreferenceMapper mapper; private final FoundationAccess access; private final CatalogScope scope;
    /** 统一主体拥有写权，存储键不接受客户端 member 或 owner。 */
    public PreferenceService(PreferenceMapper mapper,FoundationAccess access,CatalogScope scope) { this.mapper=mapper;this.access=access;this.scope=scope; }
    /** 机构品牌实时展示，无行表示使用机构名称及默认色。 */
    public Branding branding() { access.require("foundation.settings.read"); var row=mapper.branding(scope.tenant()); if(row!=null) return row;String name=mapper.tenantName(scope.tenant());if(name==null) throw CatalogValidation.missing();return new Branding(name,null,"#2563eb",null,null,0); }
    /** 保存完整展示品牌，失败保留原有效行；null 链接明确清除。 */
    @Transactional public Branding saveBranding(SaveBranding input) {
        access.require("foundation.settings.manage"); if(input==null || input.expectedRevision()<0 || input.primaryColor()==null || !input.primaryColor().matches("#[0-9a-fA-F]{6}")) throw CatalogValidation.invalid("品牌色或修订不合法");
        String email=input.contactEmail(); if(email!=null && (email.length()>254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) throw CatalogValidation.invalid("联系邮箱格式错误");
        var command=new SaveBranding(CatalogValidation.text(input.displayName(),128),CatalogValidation.url(input.logoUrl()),input.primaryColor().toLowerCase(Locale.ROOT),email,CatalogValidation.url(input.supportUrl()),input.expectedRevision());
        int changed=command.expectedRevision()==0?mapper.insertBranding(scope.tenant(),command,scope.actor()):mapper.updateBranding(scope.tenant(),command,scope.actor());
        if(changed!=1) throw CatalogValidation.conflict(); return mapper.branding(scope.tenant());
    }
    /** 当前本人偏好；首次默认只属于展示，不创建存储写副作用。 */
    public Personal personal() { access.require("foundation.preferences.manage"); var row=mapper.personal(scope.owner(),scope.actor()); return row==null?new Personal("system","zh-CN",20,false,0):row; }
    /** 完整本人 CAS 更新，跨账号 ID 从未由请求提供。 */
    @Transactional public Personal savePersonal(SavePersonal input) {
        access.require("foundation.preferences.manage");
        if(input==null || input.expectedRevision()<0 || input.theme()==null || !Set.of("system","light","dark").contains(input.theme()) || !"zh-CN".equals(input.locale()) || !Set.of(10,20,50,100).contains(input.pageSize())) throw CatalogValidation.invalid("个人偏好不在允许范围");
        int changed=input.expectedRevision()==0?mapper.insertPersonal(scope.owner(),scope.platform()?null:scope.tenant(),scope.actor(),input):mapper.updatePersonal(scope.owner(),scope.actor(),input);
        if(changed!=1) throw CatalogValidation.conflict(); return mapper.personal(scope.owner(),scope.actor());
    }
}
