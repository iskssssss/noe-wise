package com.skillforge.core.billing.metering;

import org.apache.ibatis.annotations.Param;

/** 受理SqlSession的请求事实写入口；不依赖MyBatis-Plus生成语句。 */
public interface RequestCostMapper {
    int insert(@Param("event") CostEventDO event);
}
