package com.skillforge.core.chat.application;

import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.skillforge.core.chat.persistence.HumanHandoffTicketMapper;
import com.skillforge.core.chat.persistence.HumanHandoffTicketDO;
import org.springframework.stereotype.Service;

/**
 * 转人工工单 MyBatis-Plus 服务。
 *
 * <p>继承 {@link ServiceImpl} 获得工单 CRUD 与条件查询能力，
 * 供转人工工作台接口使用。
 */
@Service
public class HumanHandoffTicketService
        extends ServiceImpl<HumanHandoffTicketMapper, HumanHandoffTicketDO>
        implements IService<HumanHandoffTicketDO> {
}
