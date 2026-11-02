package com.skillforge.institution.service;

import com.skillforge.core.chat.persistence.HumanHandoffTicketDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HandoffCreateMapper {
    int insert(HumanHandoffTicketDO ticket);
}
