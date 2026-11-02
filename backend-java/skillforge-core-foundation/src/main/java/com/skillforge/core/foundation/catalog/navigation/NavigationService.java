package com.skillforge.core.foundation.catalog.navigation;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.CatalogValidation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.skillforge.core.foundation.catalog.navigation.NavigationContracts.*;

/** 可配置的菜单树；用户可见投影实时查权限，前端仍由固定 router 装载组件。 */
@Service
public class NavigationService {
    private final NavigationMapper mapper; private final FoundationAccess access; private final CatalogScope scope;
    /** 使用统一当前主体权限，菜单不授予任何权限。 */
    public NavigationService(NavigationMapper mapper,FoundationAccess access,CatalogScope scope) { this.mapper=mapper;this.access=access;this.scope=scope; }
    /** 管理树显示隐藏节点，机构未配置时读平台提供的只读模板。 */
    public List<Node> list() { access.require("foundation.navigation.read"); return currentNodes(); }
    /** 平台和机构的固定路由目录，不暴露另一宿主路由。 */
    public List<ControlledRoutes.Route> routes() { access.require("foundation.navigation.read"); return ControlledRoutes.list(scope.platform()); }
    /** 当前用户菜单；根组只有至少一个有权限后代时才显示。 */
    public List<Menu> current() { var permissions=Set.copyOf(access.currentSnapshot().permissions()); var nodes=currentNodes(); return children(null,nodes,new HashSet<>(),0,permissions); }
    /** 串行校验整树再 CAS 保存，拒绝环、跨树父引用和任意路径。 */
    @Transactional public Node save(SaveNode input) {
        access.require("foundation.navigation.manage"); initialize();
        if(input==null || input.sortOrder()<0 || input.expectedRevision()<0) throw CatalogValidation.invalid("排序和修订不能为负数");
        String code=CatalogValidation.code(input.code()),parent=input.parentCode()==null?null:CatalogValidation.code(input.parentCode());
        String route=input.routeKey()==null?null:ControlledRoutes.require(input.routeKey(),scope.platform()).key();
        var command=new SaveNode(code,parent,CatalogValidation.text(input.title(),128),route,input.sortOrder(),input.enabled(),input.expectedRevision());
        var nodes=mapper.nodes(scope.owner()); if(nodes.size()>=256 && nodes.stream().noneMatch(n->n.code().equals(code))) throw CatalogValidation.invalid("菜单最多 256 个节点");
        Map<String,String> parents=new HashMap<>(); nodes.forEach(n->parents.put(n.code(),n.parentCode()));
        if(parent!=null && !parents.containsKey(parent)) throw CatalogValidation.invalid("父节点不存在"); parents.put(code,parent);
        // 移动节点会加深整个子树，因此检查每个节点的父链。
        for(String node:parents.keySet()) { var seen=new HashSet<String>(); String cursor=node; int depth=0;
            while(cursor!=null) { if(!seen.add(cursor) || ++depth>6) throw CatalogValidation.invalid("菜单不能成环且最多 6 层"); cursor=parents.get(cursor); }
        }
        var old=nodes.stream().filter(n->n.code().equals(code)).findFirst().orElse(null);
        int changed=old==null?command.expectedRevision()==0?mapper.insert(scope.owner(),tenant(),command,scope.actor()):0:old.revision()==command.expectedRevision()?mapper.update(scope.owner(),command,scope.actor()):0;
        if(changed!=1) throw CatalogValidation.conflict();
        return mapper.nodes(scope.owner()).stream().filter(n->n.code().equals(code)).findFirst().orElseThrow(CatalogValidation::missing);
    }
    /** 子节点必须先移走或删除，不进行隐式级联。 */
    @Transactional public void delete(DeleteNode input) { access.require("foundation.navigation.manage"); initialize(); CatalogValidation.code(input.code()); if(mapper.children(scope.owner(),input.code())>0) throw CatalogValidation.invalid("请先移动或删除子节点"); if(mapper.delete(scope.owner(),input)!=1) throw CatalogValidation.conflict(); }
    /** template 只用于未建立自己导航配置的机构。 */
    private List<Node> currentNodes() { return !scope.platform() && !mapper.initialized(scope.owner())?mapper.nodes("tenant_template"):mapper.nodes(scope.owner()); }
    /** 第一次修改前复制机构模板，advisory 锁同事务保护唯一初始化。 */
    private void initialize() { mapper.lockOwner(scope.owner()); if(!scope.platform() && !mapper.initialized(scope.owner())) {mapper.initialize(scope.owner(),scope.tenant(),scope.actor());mapper.markInitialized(scope.owner(),scope.tenant());} }
    /** 每个路由逐项实时授权；停用父组切断全部后代。 */
    private List<Menu> children(String parent,List<Node> nodes,HashSet<String> path,int depth,Set<String> permissions) {
        if(depth>6) return List.of(); var result=new ArrayList<Menu>();
        for(var n:nodes) {
            if(!n.enabled() || !Objects.equals(parent,n.parentCode()) || path.contains(n.code())) continue;
            var next=new HashSet<>(path); next.add(n.code()); var nested=children(n.code(),nodes,next,depth+1,permissions);
            var route=n.routeKey()==null?null:ControlledRoutes.require(n.routeKey(),scope.platform());
            String visiblePermission=route==null?null:ControlledRoutes.visiblePermission(route,permissions);
            if(route!=null && visiblePermission==null) continue;
            if(route==null && nested.isEmpty()) continue;
            result.add(new Menu(n.code(),n.title(),route==null?null:route.path(),n.routeKey(),visiblePermission,n.sortOrder(),nested));
        }
        return List.copyOf(result);
    }
    /** 当前可信租户或平台空归属。 */
    private String tenant() { return scope.platform()?null:scope.tenant(); }
}
