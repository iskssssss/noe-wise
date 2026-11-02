package com.skillforge.core.knowledge.integration.runtime.knowledge;

import javax.sql.DataSource;

/** 完整正式迁移后的知识版本造数，保留旧版、当前版与外租户的原有对照数据。 */
public final class CurrentKnowledgeFixtures {
    private CurrentKnowledgeFixtures() { }

    /** 仅保存测试模型的版本元数据，不装配 provider 或调用实际模型。 */
    public static void model(DataSource admin) throws Exception {
        sql(admin,"INSERT INTO nw_platform_models(id,current_revision,draft) VALUES(81,NULL,'{}')");
        sql(admin,"INSERT INTO nw_platform_model_versions(model_id,revision,config,actor_id) VALUES(81,3,'{}','portal-synthetic-fixture')");
        sql(admin,"UPDATE nw_platform_models SET current_revision=3 WHERE id=81");
    }

    /** 调用方拥有独占测试库并先清空知识数据；模型81/revision3及租户1/2必须已存在。 */
    public static void seed(DataSource admin) throws Exception {
        sql(admin,"INSERT INTO nw_knowledge_bases(id,tenant_id,name,status,active_revision_id,embedding_model_config_id,embedding_model_revision,embedding_dimensions) VALUES(10,1,'Portal','active',40,81,3,2),(20,2,'Foreign','active',NULL,81,3,2)");
        sql(admin,"INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES(20,1,'KNOWLEDGE_SOURCE','knowledge-base:10','Knowledge','TENANT','PUBLISHED','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
        String policy="{\"model_invocable\":true,\"user_invocable\":true,\"allowed_operators\":[\"KEYWORD\",\"VECTOR\",\"LINK\"],\"freshness_policy\":\"version_pinned\",\"citation_required\":true}";
        try(var c=admin.getConnection();var s=c.prepareStatement("INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) VALUES(30,20,1,'KNOWLEDGE_SOURCE','revision:7','Knowledge','TENANT',?::jsonb || jsonb_build_object('source_id','10','source_versions',jsonb_build_array('v1','v2')) || '{\"link_follow_up\":{\"enabled\":true,\"robots_allowed\":true,\"max_depth\":1,\"max_characters\":1024,\"allowed_domains\":[\"docs.example.com\"]}}'::jsonb,'test-source-hash','PUBLISHED','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")) {s.setString(1,policy);s.executeUpdate();}
        try(var c=admin.getConnection();var s=c.prepareStatement("INSERT INTO nw_knowledge_base_revisions(id,tenant_id,kb_id,revision,status,source_versions,capability_policy,capability_version_id,ready_at,published_at) VALUES(40,1,10,7,'published','[\"v1\",\"v2\"]',?::jsonb,30,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")) {s.setString(1,policy);s.executeUpdate();}
        sql(admin,"INSERT INTO nw_knowledge_documents(id,tenant_id,kb_id,current_source_version,created_at) VALUES(100,1,10,NULL,'2026-01-02T00:00:00Z'),(101,1,10,NULL,'2026-01-03T00:00:00Z'),(200,2,20,NULL,'2026-01-02T00:00:00Z')");
        sql(admin,"INSERT INTO nw_knowledge_document_contents(id,tenant_id,document_id,format,digest,content) SELECT id,tenant,document,'text/plain; charset=utf-8',encode(sha256(convert_to(body,'UTF8')),'hex'),body FROM (VALUES(110::bigint,1::bigint,100::bigint,'old body'),(111,1,100,'current body'),(112,1,101,'second body'),(210,2,200,'foreign body')) x(id,tenant,document,body)");
        sql(admin,"INSERT INTO nw_knowledge_chunk_sets(id,tenant_id,kb_id,document_id) VALUES(110,1,10,100),(111,1,10,100),(112,1,10,101),(210,2,20,200)");
        sql(admin,"INSERT INTO nw_knowledge_document_versions(id,tenant_id,kb_id,document_id,source_version,title,source_type,content_id,chunk_set_id,status,chunk_count,created_at,embedding_model_config_id,embedding_model_revision,embedding_dimensions) VALUES(110,1,10,100,'v1','old','file',110,110,'processing',4,'2026-01-02T00:00:00Z',81,3,2),(111,1,10,100,'v3','current','file',111,111,'processing',1,'2026-01-04T00:00:00Z',81,3,2),(112,1,10,101,'v2','second','file',112,112,'processing',2,'2026-01-03T00:00:00Z',81,3,2),(210,2,20,200,'v1','foreign','file',210,210,'processing',1,'2026-01-02T00:00:00Z',81,3,2)");
        sql(admin,"INSERT INTO nw_knowledge_chunk_contents(id,tenant_id,document_id,format,digest,content) SELECT id,tenant,document,'text/plain; charset=utf-8',encode(sha256(convert_to(body,'UTF8')),'hex'),body FROM (VALUES(1000::bigint,1::bigint,100::bigint,'ALPHA literal 100%_match'),(1001,1,100,'alpha'),(1002,1,101,'alpha second'),(1003,1,100,'alpha current unapproved'),(1004,1,100,'linkseed https://docs.example.com/policy#section'),(1005,1,100,E'conflictseed\\npolicy: enabled'),(1006,1,101,E'conflictseed\\npolicy: disabled'),(2000,2,200,'alpha foreign tenant')) x(id,tenant,document,body)");
        sql(admin,"INSERT INTO nw_knowledge_chunks(id,tenant_id,kb_id,document_id,chunk_index,content_id,created_at,chunk_set_id) VALUES(1000,1,10,100,1,1000,'2026-01-02T00:00:00Z',110),(1001,1,10,100,0,1001,'2026-01-03T00:00:00Z',110),(1002,1,10,101,0,1002,'2026-01-04T00:00:00Z',112),(1003,1,10,100,0,1003,'2026-01-04T00:00:00Z',111),(1004,1,10,100,2,1004,'2026-01-03T00:00:00Z',110),(1005,1,10,100,3,1005,'2026-01-03T00:00:00Z',110),(1006,1,10,101,1,1006,'2026-01-04T00:00:00Z',112),(2000,2,20,200,0,2000,'2026-01-02T00:00:00Z',210)");
        sql(admin,"UPDATE nw_knowledge_document_versions SET status='ready'");
        sql(admin,"UPDATE nw_knowledge_documents SET current_source_version=CASE id WHEN 100 THEN 'v3' WHEN 101 THEN 'v2' ELSE 'v1' END");
    }

    /** SQL仅来自本类固定造数，连接随语句完成释放。 */
    private static void sql(DataSource source,String sql) throws Exception {
        try(var connection=source.getConnection();var statement=connection.createStatement()){statement.execute(sql);}
    }
}
