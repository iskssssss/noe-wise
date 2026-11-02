package com.skillforge.core.foundation.catalog.preferences;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.skillforge.core.foundation.catalog.preferences.PreferenceContracts.*;

/** 类型化 UI 设置持久化；个人键同时包含宿主范围和当前账号。 */
@Mapper
public interface PreferenceMapper {
    /** 未设置品牌时使用机构自身名称，不使用虚构平台品牌。 */ String tenantName(@Param("tenant") String tenant);
    /** 当前机构品牌，无存储行返回 null。 */ Branding branding(@Param("tenant") String tenant);
    /** 首次创建品牌并标记可信主体。 */ int insertBranding(@Param("tenant") String tenant,@Param("command") SaveBranding command,@Param("actor") String actor);
    /** 完整 CAS 更新，可空展示字段显式清除。 */ int updateBranding(@Param("tenant") String tenant,@Param("command") SaveBranding command,@Param("actor") String actor);
    /** 只查询本人的偏好。 */ Personal personal(@Param("owner") String owner,@Param("member") String member);
    /** 创建本人偏好。 */ int insertPersonal(@Param("owner") String owner,@Param("tenant") String tenant,@Param("member") String member,@Param("command") SavePersonal command);
    /** 只允许本人键 CAS。 */ int updatePersonal(@Param("owner") String owner,@Param("member") String member,@Param("command") SavePersonal command);
}
