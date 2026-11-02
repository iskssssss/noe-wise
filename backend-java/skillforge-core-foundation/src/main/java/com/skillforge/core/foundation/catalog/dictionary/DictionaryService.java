package com.skillforge.core.foundation.catalog.dictionary;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.CatalogValidation;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.shared.exception.ApiException;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryContracts.*;

/** 平台和机构字典的权威写入口；父锁串行化值、引用和删除。 */
@Service
public class DictionaryService {
    private final DictionaryMapper mapper; private final FoundationAccess access; private final CatalogScope scope; private final FeatureGate features;
    /** 绑定统一授权及所属存储。 */
    public DictionaryService(DictionaryMapper mapper,FoundationAccess access,CatalogScope scope,FeatureGate features) { this.mapper=mapper;this.access=access;this.scope=scope;this.features=features; }
    /** 管理目录不隐藏停用字典。 */
    public List<DictionaryView> list() { access.require("foundation.dictionary.read"); return mapper.dictionaries(scope.owner()); }
    /** 读取全部值，历史资源可显示已停用的稳定编码。 */
    @Transactional public List<ValueView> listValues(String dictionaryCode) { access.require("foundation.dictionary.read"); dictionary(dictionaryCode); return mapper.values(scope.owner(),dictionaryCode); }
    /** 当前选择器只返回字典与值都启用的项。 */
    @Transactional public List<ValueView> options(String dictionaryCode) { access.require("foundation.dictionary.read"); if(!dictionary(dictionaryCode).enabled()) return List.of(); return mapper.values(scope.owner(),dictionaryCode).stream().filter(ValueView::enabled).toList(); }
    /** 完整保存，0 修订创建；稳定 code 从未被更新 SQL 修改。 */
    @Transactional public DictionaryView save(SaveDictionary input) {
        requireManage(); String code=CatalogValidation.code(input.code());
        var command=new SaveDictionary(code,CatalogValidation.text(input.name(),128),input.enabled(),input.expectedRevision());
        if(command.expectedRevision()<0) throw CatalogValidation.invalid("修订不能为负数");
        DictionaryView current=mapper.lockDictionary(scope.owner(),code);
        if(current==null) { if(command.expectedRevision()!=0) throw CatalogValidation.conflict(); if(mapper.insertDictionary(scope.owner(),tenant(),command,scope.actor())!=1) throw CatalogValidation.conflict(); }
        else if(current.revision()!=command.expectedRevision() || mapper.updateDictionary(scope.owner(),command,scope.actor())!=1) throw CatalogValidation.conflict();
        return mapper.lockDictionary(scope.owner(),code);
    }
    /** 值保存必须先获得父锁；停用不删除历史引用。 */
    @Transactional public ValueView saveValue(SaveValue input) {
        requireManage(); var command=validated(input); dictionary(command.dictionaryCode());
        var current=mapper.value(scope.owner(),command.dictionaryCode(),command.code());
        if(current==null) { if(command.expectedRevision()!=0 || mapper.insertValue(scope.owner(),tenant(),command,scope.actor())!=1) throw CatalogValidation.conflict(); }
        else if(current.revision()!=command.expectedRevision() || mapper.updateValue(scope.owner(),command,scope.actor())!=1) throw CatalogValidation.conflict();
        return mapper.value(scope.owner(),command.dictionaryCode(),command.code());
    }
    /** 无引用且修订匹配才删除；数据库外键提供最后一道竞争保护。 */
    @Transactional public void delete(Delete input) {
        requireManage(); dictionary(input.dictionaryCode());
        if(input.code()!=null) CatalogValidation.code(input.code());
        if(mapper.references(scope.owner(),input.dictionaryCode(),input.code())>0) throw new ApiException(HttpStatus.CONFLICT,"dictionary_referenced","字典仍被业务资源引用，可停用但不能删除",false);
        int changed=input.code()==null?mapper.deleteDictionary(scope.owner(),input):mapper.deleteValue(scope.owner(),input);
        if(changed!=1) throw CatalogValidation.conflict();
    }
    /** 导入预检没有写副作用，拒绝重复编码及越界；异步执行仍重新检查当前权限。 */
    public ImportInspection inspectImport(List<ImportEntry> entries) {
        requireManage(); if(entries==null || entries.size()>10000) throw CatalogValidation.invalid("导入最多 10000 行");
        var unique=new HashSet<String>(); var normalized=entries.stream().map(e->{var v=validated(new SaveValue(e.dictionaryCode(),e.code(),e.label(),e.sortOrder(),e.enabled(),0)); if(!unique.add(v.dictionaryCode()+":"+v.code())) throw CatalogValidation.invalid("导入包含重复编码"); return new ImportEntry(v.dictionaryCode(),v.code(),v.label(),v.sortOrder(),v.enabled());}).toList();
        return new ImportInspection(normalized);
    }
    /** CSV 单行幂等 upsert；只有父锁下读取的当前修订可用于更新。 */
    @Transactional public ValueView importValue(String dictionaryCode,String code,String label,int sortOrder,boolean enabled) {
        requireManage(); dictionary(dictionaryCode); var existing=mapper.value(scope.owner(),dictionaryCode,code);
        return saveValue(new SaveValue(dictionaryCode,code,label,sortOrder,enabled,existing==null?0:existing.revision()));
    }
    /** 业务保存声明引用；现行禁用字典和值拒绝新引用，引用键只能由可信消费者提供。 */
    @Transactional public void reference(String dictionaryCode,String code,String resourceType,String resourceId) {
        access.require("foundation.dictionary.read"); var parent=dictionary(dictionaryCode); var value=mapper.value(scope.owner(),dictionaryCode,CatalogValidation.code(code));
        if(!parent.enabled() || value==null || !value.enabled()) throw CatalogValidation.invalid("字典值未启用");
        mapper.reference(scope.owner(),tenant(),dictionaryCode,code,CatalogValidation.code(resourceType),CatalogValidation.text(resourceId,128));
    }
    /** 消费者在删除资源的同一事务释放引用，不提供公开 HTTP 绕过入口。 */
    @Transactional public void unreference(String resourceType,String resourceId) { access.require("foundation.dictionary.read"); mapper.unreference(scope.owner(),CatalogValidation.code(resourceType),CatalogValidation.text(resourceId,128)); }
    /** 父锁保护同一目录生命周期。 */
    private DictionaryView dictionary(String code) { CatalogValidation.code(code); var row=mapper.lockDictionary(scope.owner(),code); if(row==null) throw CatalogValidation.missing(); return row; }
    /** 构造完整值命令，不能以缺失值变成默认有效值。 */
    private SaveValue validated(SaveValue input) { if(input==null || input.sortOrder()<0 || input.expectedRevision()<0) throw CatalogValidation.invalid("排序和修订必须非负"); return new SaveValue(CatalogValidation.code(input.dictionaryCode()),CatalogValidation.code(input.code()),CatalogValidation.text(input.label(),128),input.sortOrder(),input.enabled(),input.expectedRevision()); }
    /** 平台资源没有租户归属；机构资源从统一主体取得 ID。 */
    private String tenant() { return scope.platform()?null:scope.tenant(); }
    /** 当前停用功能拒绝新的字典维护；历史展示与引用释放继续遵循读取权限。 */
    private void requireManage() {access.require("foundation.dictionary.manage");if(!scope.platform()) features.require("foundation.dictionary");}
}
