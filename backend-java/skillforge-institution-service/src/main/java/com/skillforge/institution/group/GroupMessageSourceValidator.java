package com.skillforge.institution.group;
import com.skillforge.core.chat.contract.ChatContent;
import java.util.List;
/** 仅准许当前正式公开投影已提交的来源；校验同群Session、真实作者及完整有序内容。 */
public interface GroupMessageSourceValidator {
 void require(String tenantId,String integrationId,String groupBindingId,String sessionId,GroupMessageContracts.Author author,List<ChatContent> content);
}
