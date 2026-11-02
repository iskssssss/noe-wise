-- 创建身份与连接器同生命周期，不保存请求正文或凭据。
ALTER TABLE nw_connector_instances ADD COLUMN create_request_id varchar(36);
ALTER TABLE nw_connector_instances ADD COLUMN create_actor_id varchar(64);
CREATE UNIQUE INDEX uq_connector_create_request ON nw_connector_instances (tenant_id, create_request_id)
    WHERE create_request_id IS NOT NULL;
