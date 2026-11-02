package com.skillforge.core.channel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.channel.model.ChannelDO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/**
 * 渠道 MyBatis-Plus 映射接口。
 */
public interface ChannelMapper extends BaseMapper<ChannelDO> {

    /** 绑定集合替换与解绑共用渠道行锁，租户身份必须来自可信调用上下文。 */
    Long lockOwnedChannel(@Param("tenantId") long tenantId, @Param("channelId") long channelId);

    int updateApiCredentials(@Param("tenantId") long tenantId, @Param("channelId") long channelId,
            @Param("apiKeyHash") String apiKeyHash, @Param("apiSecretEncrypted") String apiSecretEncrypted);

    List<BindingSummary> bindingSummaries(@Param("tenantId") long tenantId, @Param("channelIds") List<Long> channelIds);

    record BindingSummary(Long channelId, Long agentId, String name, Integer priority,
                          Integer trafficPercentage, Boolean isDefault) { }

    /**
     * 外部认证用：按渠道令牌哈希定位激活渠道。
     *
     * <p>经 SECURITY DEFINER 函数（V40）执行以绕过 RLS——外部认证在解析出租户
     * 之前无法设置租户上下文。仅接受哈希参数，WHERE 固定，属主为超级权限。
     *
     * @param tokenHash 渠道令牌 SHA-256 十六进制
     * @return 激活状态的渠道；不存在返回 null
     */
    ChannelDO selectForAuthByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 外部认证用：按 OpenAPI API Key 哈希定位激活渠道。
     *
     * <p>同 {@link #selectForAuthByTokenHash}，按专用凭据列查询。
     *
     * @param apiKeyHash API Key 的 SHA-256 十六进制
     * @return 激活状态的渠道；不存在返回 null
     */
    ChannelDO selectForAuthByApiKeyHash(@Param("apiKeyHash") String apiKeyHash);
}
