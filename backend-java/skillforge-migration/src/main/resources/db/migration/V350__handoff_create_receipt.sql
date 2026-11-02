-- 工单与创建回执共同行，确保一次提交与同键去重。
ALTER TABLE nw_human_handoff_tickets
    ADD COLUMN create_command_id varchar(36),
    ADD COLUMN created_by bigint,
    ADD COLUMN create_fingerprint char(64),
    ADD CONSTRAINT handoff_create_receipt_complete CHECK (
        (create_command_id IS NULL AND created_by IS NULL AND create_fingerprint IS NULL)
        OR (create_command_id IS NOT NULL AND created_by IS NOT NULL AND create_fingerprint IS NOT NULL)
    ),
    ADD CONSTRAINT handoff_create_command_unique UNIQUE (tenant_id, create_command_id),
    ADD CONSTRAINT handoff_creator_tenant FOREIGN KEY (tenant_id, created_by)
        REFERENCES nw_tenant_members(tenant_id, id);
COMMENT ON COLUMN nw_human_handoff_tickets.create_command_id IS '客户端创建命令 UUID，原回执身份';
COMMENT ON COLUMN nw_human_handoff_tickets.created_by IS '创建时认证的机构成员';
COMMENT ON COLUMN nw_human_handoff_tickets.create_fingerprint IS '创建请求与认证主体的 SHA-256 指纹';
