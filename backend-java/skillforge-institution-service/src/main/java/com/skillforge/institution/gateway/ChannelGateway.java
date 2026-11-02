package com.skillforge.institution.gateway;

import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.shared.security.TokenHash;
import org.springframework.stereotype.Service;

/**
 * 渠道网关。
 *
 * <p>根据 C 端渠道令牌解析租户与渠道，供渠道身份认证与对话路由使用。
 * 查询经 SECURITY DEFINER 函数（V40）执行：外部认证在解析出租户之前无法
 * 设置 RLS 租户上下文，普通查询会被 channels 表的 FORCE RLS 拦截。
 */
@Service
public class ChannelGateway {

    /** 渠道表映射器。 */
    private final ChannelMapper channelMapper;

    /**
     * 构造渠道网关。
     *
     * @param channelMapper 渠道表映射器
     */
    public ChannelGateway(ChannelMapper channelMapper) {
        this.channelMapper = channelMapper;
    }

    /**
     * 解析渠道令牌。
     *
     * @param channelToken 渠道明文令牌
     * @return 激活状态的渠道；无效返回 null
     */
    public ChannelDO resolveChannel(String channelToken) {
        if (channelToken == null || channelToken.isBlank()) {
            return null;
        }
        return channelMapper.selectForAuthByTokenHash(TokenHash.sha256Hex(channelToken));
    }
}
