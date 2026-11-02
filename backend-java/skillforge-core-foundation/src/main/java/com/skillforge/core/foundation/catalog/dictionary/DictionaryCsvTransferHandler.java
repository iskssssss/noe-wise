package com.skillforge.core.foundation.catalog.dictionary;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.catalog.CatalogValidation;
import com.skillforge.core.foundation.jobs.CsvTransferHandler;
import com.skillforge.core.foundation.jobs.FoundationJobContracts.RowError;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 字典值的受控 CSV 导入导出；后台任务沿用当前主体授权和字典公开写入口。 */
@Component
public class DictionaryCsvTransferHandler implements CsvTransferHandler {
    private final DictionaryService dictionaries;
    /** 使用领域服务，不依赖资产任务内部存储。 */
    public DictionaryCsvTransferHandler(DictionaryService dictionaries) {this.dictionaries=dictionaries;}
    /** {@inheritDoc} */ @Override public String code() {return "dictionary_values";}
    /** {@inheritDoc} */ @Override public String title() {return "字典值";}
    /** 任务页面提供明确领域字段，不要求用户填写原始 JSON。 */
    @Override public List<com.skillforge.core.foundation.jobs.FoundationJobContracts.OptionField> optionFields() {return List.of(new com.skillforge.core.foundation.jobs.FoundationJobContracts.OptionField("dictionary_code","字典编码",true,"请输入已存在的字典编码"));}
    /** {@inheritDoc} */ @Override public List<String> columns() {return List.of("code","label","sort_order","enabled");}
    /** {@inheritDoc} */ @Override public String importPermission() {return "foundation.dictionary.manage";}
    /** {@inheritDoc} */ @Override public String exportPermission() {return "foundation.dictionary.read";}
    /** 字典选择是唯一可选项；查公开服务确认当前主体可读该字典。 */
    @Override public void validateOptions(Map<String,String> options) {
        if(options==null || !options.keySet().equals(Set.of("dictionary_code"))) throw CatalogValidation.invalid("字典转移只接受 dictionary_code 选项");
        dictionaries.listValues(CatalogValidation.code(options.get("dictionary_code")));
    }
    /** 严格解析四列并调用领域预检；不提供任何写副作用。 */
    @Override public List<RowError> validateRow(Map<String,String> options,int rowNumber,List<String> cells) {
        try {dictionaries.inspectImport(List.of(entry(options,cells)));return List.of();}
        catch(ApiException | IllegalArgumentException e) {return List.of(new RowError(rowNumber,"row",e instanceof ApiException?e.getMessage():"排序必须为非负整数"));}
    }
    /** 工作者提供的事务内执行幂等领域 upsert，回执由任务组件同事务保存。 */
    @Override public void importRow(Map<String,String> options,String jobId,int rowNumber,List<String> cells) {var e=entry(options,cells);dictionaries.importValue(e.dictionaryCode(),e.code(),e.label(),e.sortOrder(),e.enabled());}
    /** 保留稳定编码和停用状态，导出包括历史展示需要的值。 */
    @Override public List<List<String>> exportRows(Map<String,String> options) {validateOptions(options);return dictionaries.listValues(options.get("dictionary_code")).stream().map(v->List.of(v.code(),v.label(),Integer.toString(v.sortOrder()),Boolean.toString(v.enabled()))).toList();}
    /** 不把非法布尔文本默默转换成 false，字段数必须精确匹配表头。 */
    private DictionaryContracts.ImportEntry entry(Map<String,String> options,List<String> cells) {
        if(cells==null || cells.size()!=4 || !("true".equals(cells.get(3)) || "false".equals(cells.get(3)))) throw CatalogValidation.invalid("CSV必须含四列，enabled只接受true/false");
        return new DictionaryContracts.ImportEntry(CatalogValidation.code(options.get("dictionary_code")),cells.get(0),cells.get(1),Integer.parseInt(cells.get(2)),Boolean.parseBoolean(cells.get(3)));
    }
}
