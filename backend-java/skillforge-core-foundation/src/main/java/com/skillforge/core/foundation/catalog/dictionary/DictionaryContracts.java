package com.skillforge.core.foundation.catalog.dictionary;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;

/** 字典管理和受控 CSV 转移共享的传输契约；编码创建后不可改名。 */
public final class DictionaryContracts {
    private DictionaryContracts() { }
    /** @param code 稳定字典编码 @param name 显示名称 @param enabled 是否允许新选择 @param revision CAS 修订 */
    public record DictionaryView(String code,String name,boolean enabled,long revision) { }
    /** @param dictionaryCode 所属字典 @param code 稳定值编码 @param label 显示文本 @param sortOrder 非负排序 @param enabled 新选择开关 @param revision CAS 修订 */
    public record ValueView(String dictionaryCode,String code,String label,int sortOrder,boolean enabled,long revision) { }
    /** @param code 创建或寻址编码 @param name 完整显示名称 @param enabled 新选择开关 @param expectedRevision 0 表示新建，正数表示 CAS 更新 */
    public record SaveDictionary(String code,String name,@JsonProperty(value="enabled",required=true) @JsonSetter(nulls=Nulls.FAIL) boolean enabled,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param dictionaryCode 所属字典 @param code 创建或寻址编码 @param label 完整显示文本 @param sortOrder 排序 @param enabled 新选择开关 @param expectedRevision 0 新建，正数 CAS 更新 */
    public record SaveValue(String dictionaryCode,String code,String label,@JsonProperty(value="sort_order",required=true) @JsonSetter(nulls=Nulls.FAIL) int sortOrder,@JsonProperty(value="enabled",required=true) @JsonSetter(nulls=Nulls.FAIL) boolean enabled,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param dictionaryCode 所属字典 @param code 可空值编码；空时删除整个字典 @param expectedRevision 必须匹配当前修订 */
    public record Delete(String dictionaryCode,String code,@JsonProperty(value="expected_revision",required=true) @JsonSetter(nulls=Nulls.FAIL) long expectedRevision) { }
    /** @param dictionaryCode 导入字典 @param code 导入稳定编码 @param label 显示文本 @param sortOrder 排序 @param enabled 新选择状态 */
    public record ImportEntry(String dictionaryCode,String code,String label,int sortOrder,boolean enabled) { }
    /** @param entries 全部合法输入的规范化行，验证失败整体拒绝 */
    public record ImportInspection(List<ImportEntry> entries) { }
}
