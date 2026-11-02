-- 与会话同生命周期的机构后台创建身份，避免为每个会话增加一行回执。
ALTER TABLE nw_chat_sessions ADD COLUMN create_request_id varchar(36);
CREATE UNIQUE INDEX uq_chat_session_create_request ON nw_chat_sessions (tenant_id, create_request_id)
    WHERE create_request_id IS NOT NULL;
