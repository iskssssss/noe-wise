package com.skillforge.core.foundation.catalog.dictionary;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.skillforge.core.foundation.catalog.dictionary.DictionaryContracts.*;

/** 字典关系存储端口；所有查询显式限定可信 owner，数据库另有 RLS。 */
@Mapper
public interface DictionaryMapper {
    /** 所有者目录。 */ List<DictionaryView> dictionaries(@Param("owner") String owner);
    /** 字典读写共用父行锁，保护引用和删除竞争。 */ DictionaryView lockDictionary(@Param("owner") String owner,@Param("code") String code);
    /** 值目录，包括已停用项以便管理和历史展示。 */ List<ValueView> values(@Param("owner") String owner,@Param("code") String code);
    /** 单个值的当前修订。 */ ValueView value(@Param("owner") String owner,@Param("dictionary") String dictionary,@Param("code") String code);
    /** 新建字典。 */ int insertDictionary(@Param("owner") String owner,@Param("tenant") String tenant,@Param("command") SaveDictionary command,@Param("actor") String actor);
    /** CAS 更新不改变稳定编码。 */ int updateDictionary(@Param("owner") String owner,@Param("command") SaveDictionary command,@Param("actor") String actor);
    /** 新建字典值。 */ int insertValue(@Param("owner") String owner,@Param("tenant") String tenant,@Param("command") SaveValue command,@Param("actor") String actor);
    /** CAS 更新显示和可选状态。 */ int updateValue(@Param("owner") String owner,@Param("command") SaveValue command,@Param("actor") String actor);
    /** 字典或具体值的受控引用数。 */ long references(@Param("owner") String owner,@Param("dictionary") String dictionary,@Param("code") String code);
    /** 在无引用后 CAS 删除。 */ int deleteValue(@Param("owner") String owner,@Param("command") Delete command);
    /** 删除父对象；子项级联，引用外键限制不可绕过。 */ int deleteDictionary(@Param("owner") String owner,@Param("command") Delete command);
    /** 声明业务引用；幂等键为受控资源类型和资源 ID。 */ int reference(@Param("owner") String owner,@Param("tenant") String tenant,@Param("dictionary") String dictionary,@Param("code") String code,@Param("resourceType") String resourceType,@Param("resourceId") String resourceId);
    /** 资源释放所属引用。 */ int unreference(@Param("owner") String owner,@Param("resourceType") String resourceType,@Param("resourceId") String resourceId);
}
