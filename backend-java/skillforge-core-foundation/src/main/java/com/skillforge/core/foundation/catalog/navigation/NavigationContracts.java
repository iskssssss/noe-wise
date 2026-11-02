package com.skillforge.core.foundation.catalog.navigation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;

/** 菜单结构与当前可见投影；受控路由元数据由代码目录补足。 */
public final class NavigationContracts {
    private NavigationContracts() { }
    /** @param code 稳定节点编码 @param parentCode 可空父编码 @param title 展示标题 @param routeKey 可空受控路由键 @param sortOrder 非负排序 @param enabled 是否启用 @param revision CAS 修订 */
    public record Node(String code,String parentCode,String title,String routeKey,int sortOrder,boolean enabled,long revision) { }
    /** @param code 稳定节点编码 @param parentCode 父节点，null 为根 @param title 标题 @param routeKey 可空受控路由 @param sortOrder 排序 @param enabled 开关 @param expectedRevision 0 新建，正数 CAS 更新 */
    public record SaveNode(String code,String parentCode,String title,String routeKey,@JsonProperty(value="sort_order",required=true) @JsonSetter(nulls=Nulls.FAIL) int sortOrder,@JsonProperty(value="enabled",required=true) @JsonSetter(nulls=Nulls.FAIL) boolean enabled,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param code 删除节点 @param expectedRevision 必须匹配，子节点存在时拒绝 */
    public record DeleteNode(String code,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param code 节点编码 @param title 用户可见标题 @param path 可空受控宿主路径 @param routeKey 可空受控路由键 @param permission 该路由真实权限 @param sortOrder 排序 @param children 已实时过滤的后代 */
    public record Menu(String code,String title,String path,String routeKey,String permission,int sortOrder,List<Menu> children) { }
}
