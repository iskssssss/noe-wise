package com.skillforge.core.knowledge.integration.runtime.knowledge;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;

/** 共享实际知识版本与 Runtime 冻结生产 schema。 */
public final class KnowledgeBindingDatabaseFixture {
    private KnowledgeBindingDatabaseFixture() { }
    public static void install(DataSource admin) throws Exception {
        sql(admin,"CREATE EXTENSION IF NOT EXISTS vector");
        sql(admin,"CREATE TABLE IF NOT EXISTS nw_tenants(id bigint PRIMARY KEY)");
        sql(admin,"INSERT INTO nw_tenants(id) VALUES(1),(2) ON CONFLICT DO NOTHING");
        // V86 的工具部署外键依赖连接器租户键；知识测试只提供原约束所需身份，不取消生产外键。
        sql(admin,"CREATE TABLE IF NOT EXISTS public.nw_connector_instances(tenant_id bigint NOT NULL REFERENCES public.nw_tenants(id),instance_id text NOT NULL,PRIMARY KEY(tenant_id,instance_id))");
        sql(admin,"CREATE TABLE nw_knowledge_bases(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,status text NOT NULL,embedding_model_config_id bigint NOT NULL DEFAULT 81,embedding_model_revision bigint NOT NULL DEFAULT 3,embedding_dimensions integer NOT NULL DEFAULT 2)");
        sql(admin,"CREATE TABLE nw_knowledge_documents(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,kb_id bigint NOT NULL,title varchar(256) NOT NULL,source_type varchar(32) NOT NULL,content text,original_filename varchar(512),extension varchar(32),size_bytes bigint,parsed_by varchar(32),status varchar(32) NOT NULL,created_at timestamptz NOT NULL)");
        sql(admin,"CREATE TABLE nw_knowledge_chunks(id bigint PRIMARY KEY,tenant_id bigint NOT NULL,kb_id bigint NOT NULL,document_id bigint NOT NULL,chunk_index integer NOT NULL,content text NOT NULL,embedding vector,created_at timestamptz NOT NULL)");
        for(String migration:List.of("V76__knowledge_source_version_pinning.sql","V77__knowledge_base_revision_lifecycle.sql","V86__capability_registry.sql",
                "V89__knowledge_source_capability_publication.sql","V110__agent_runtime_knowledge_bindings.sql",
                "V235__immutable_knowledge_source_versions.sql", "V256__knowledge_embedding_rebuild.sql",
                "V285__knowledge_immutable_body_references.sql")) {
            try(var resource=KnowledgeBindingDatabaseFixture.class.getClassLoader().getResourceAsStream("db/migration/"+migration)) {
                if(resource==null)throw new IllegalStateException("migration_missing");sql(admin,new String(resource.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        for(String table:List.of("nw_knowledge_bases","nw_knowledge_documents","nw_knowledge_chunks")) {
            sql(admin,"ALTER TABLE "+table+" ENABLE ROW LEVEL SECURITY");sql(admin,"ALTER TABLE "+table+" FORCE ROW LEVEL SECURITY");
            sql(admin,"CREATE POLICY tenant_isolation ON "+table+" USING (tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK (tenant_id::text=current_setting('app.tenant_id',true))");
        }
    }
    public static void seed(DataSource admin) throws Exception {
        sql(admin,"INSERT INTO nw_knowledge_bases(id,tenant_id,status,active_revision_id) VALUES(10,1,'active',40),(20,2,'active',NULL)");
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
    public static FrozenKnowledgeTarget target(){return new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:mount"),new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,"20","30"),"10",7,
            Set.of(new KnowledgeSourceVersion("10","v1"),new KnowledgeSourceVersion("10","v2")),Set.of(KnowledgeOperatorType.KEYWORD,KnowledgeOperatorType.VECTOR,KnowledgeOperatorType.LINK),Duration.ofSeconds(3));}
    public static void sql(DataSource source,String sql)throws Exception{try(var c=source.getConnection();var s=c.createStatement()){s.execute(sql);}}
}
