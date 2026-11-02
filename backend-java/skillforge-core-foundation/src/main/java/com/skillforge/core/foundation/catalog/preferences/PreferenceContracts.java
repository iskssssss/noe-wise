package com.skillforge.core.foundation.catalog.preferences;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/** 展示设置契约；这些实时 UI 偏好不作为执行配置或 Run 冻结事实。 */
public final class PreferenceContracts {
    private PreferenceContracts() { }
    /** @param displayName 机构品牌名 @param logoUrl 可空公开 HTTPS 链接 @param primaryColor 六位十六进制色 @param contactEmail 可空机构联系邮箱 @param supportUrl 可空公开 HTTPS 支持链接 @param revision CAS 修订，0 尚未保存 */
    public record Branding(String displayName,String logoUrl,String primaryColor,String contactEmail,String supportUrl,long revision) { }
    /** @param displayName 必填品牌名 @param logoUrl null 清除 @param primaryColor 必填主题色 @param contactEmail null 清除 @param supportUrl null 清除 @param expectedRevision 完整更新所需修订 */
    public record SaveBranding(String displayName,String logoUrl,String primaryColor,String contactEmail,String supportUrl,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param theme light/dark/system @param locale 当前后台资源仅支持zh-CN @param pageSize 10/20/50/100 @param compactNavigation 是否紧凑导航 @param revision CAS 修订 */
    public record Personal(String theme,String locale,int pageSize,boolean compactNavigation,long revision) { }
    /** @param theme 主题 @param locale 语言 @param pageSize 受控分页大小 @param compactNavigation 完整导航偏好 @param expectedRevision 0 初次保存，之后精确 CAS */
    public record SavePersonal(String theme,String locale,@JsonProperty(value="page_size",required=true) @JsonSetter(nulls=Nulls.FAIL) int pageSize,@JsonProperty(value="compact_navigation",required=true) @JsonSetter(nulls=Nulls.FAIL) boolean compactNavigation,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
}
