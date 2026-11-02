package com.skillforge.core.knowledge.integration.runtime.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.integration.runtime.CurrentHostDatabase;
import com.skillforge.core.foundation.integration.runtime.CurrentHostFixtures;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.KnowledgePortal.*;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.codec.spi.KnowledgeDocumentCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Q06同源语料在完整正式迁移与受限PG角色下核验目录/原文；不替代Runtime派发IAM验收。 */
@Testcontainers(disabledWithoutDocker=false)
class JdbcKnowledgeDocumentReadTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String ROLE="q06_document_reader",PASSWORD="q06-fixture-only-password";
    private static final KnowledgeDocumentCodec DOCUMENTS=CoreRuntime.knowledgeDocuments(new CodecLimits(1_048_576,10_000,16));
    @Container static final PostgreSQLContainer<?> DATABASE=new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("q06_document_reads");
    private static Suite suite;
    private static DataSource admin,restricted;
    private static JdbcKnowledgeBindings bindings;
    private static JdbcKnowledgePortal portal;
    private static FrozenKnowledgeTarget target;
    private static JdbcKnowledgeBindings.Binding frozen;
    private static Instant asOf;

    @BeforeAll static void database()throws Exception {
        Path file=Path.of("..","tools","knowledge_evaluation","suite.json");
        if(!Files.exists(file))file=Path.of("..","..","tools","knowledge_evaluation","suite.json");
        suite=JSON.readValue(Files.readString(file),Suite.class);
        assertEquals(1,suite.schema_version());assertEquals(26,suite.documents().size());
        CurrentHostDatabase.migrate(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword());
        admin=dataSource(DATABASE.getUsername(),DATABASE.getPassword());
        try(var connection=admin.getConnection()) {
            for(long tenant:suite.documents().stream().mapToLong(value->Long.parseLong(value.tenant_id())).distinct().toArray())
                CurrentHostFixtures.tenant(connection,tenant);
        }
        CurrentKnowledgeFixtures.model(admin);
        seed();
        KnowledgeBindingDatabaseFixture.sql(admin,"CREATE ROLE "+ROLE+" LOGIN PASSWORD '"+PASSWORD+"' NOSUPERUSER NOBYPASSRLS");
        KnowledgeBindingDatabaseFixture.sql(admin,"GRANT USAGE ON SCHEMA public TO "+ROLE);
        KnowledgeBindingDatabaseFixture.sql(admin,"GRANT SELECT,INSERT ON ALL TABLES IN SCHEMA public TO "+ROLE);
        // 正式绑定发布以FOR UPDATE锁定知识库，需要UPDATE权限；仍由受限角色及原RLS约束读写范围。
        KnowledgeBindingDatabaseFixture.sql(admin,"GRANT UPDATE ON public.nw_knowledge_bases TO "+ROLE);
        restricted=dataSource(ROLE,PASSWORD);bindings=new JdbcKnowledgeBindings(restricted);
        Set<KnowledgeSourceVersion> versions=suite.documents().stream().filter(value->value.mounted()
                &&value.tenant_id().equals(suite.oracles().catalog_tenant_id())).map(value->new KnowledgeSourceVersion(value.source_id(),value.source_version()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        target=new FrozenKnowledgeTarget(new KnowledgeHandle(suite.oracles().catalog_handle()),
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,"91069001","91069002"),"910610",1,versions,
                Set.of(KnowledgeOperatorType.KEYWORD,KnowledgeOperatorType.DOCUMENT_LIST,KnowledgeOperatorType.DOCUMENT_READ),Duration.ofSeconds(10));
        frozen=bindings.publish(new TenantId(suite.oracles().catalog_tenant_id()),target);asOf=Instant.now();
        portal=JdbcKnowledgePortal.registered(restricted,bindings,bundle->true,JdbcKnowledgePortal.LinkReader.unavailable(),Clock.systemUTC(),DOCUMENTS);
    }

    @Test void catalogPagesCoverAllFrozenDocumentsWithoutBodyOrCitationEvidence() {
        var ids=new ArrayList<String>();Optional<String> cursor=Optional.empty();int pages=0;
        do {
            var result=retrieve(new KnowledgeOperation.ListDocuments("",cursor,7),262_144);
            var page=assertInstanceOf(KnowledgeDocumentResult.CatalogPage.class,result.documentResult().orElseThrow());
            assertTrue(result.items().isEmpty());assertEquals(EvidenceSufficiency.INSUFFICIENT,result.sufficiency());
            assertTrue(result.trace().getFirst().selectedItemIds().isEmpty());
            ids.addAll(page.documents().stream().map(KnowledgeDocumentResult.Document::documentId).toList());
            cursor=page.nextCursor();assertTrue(++pages<=5);
        }while(cursor.isPresent());
        assertEquals(suite.oracles().mounted_document_ids(),ids);assertEquals(24,new HashSet<>(ids).size());assertEquals(4,pages);
    }

    @Test void titleFilterUsesLiteralFrozenTitlesAndCursorCannotChangeItsScope() {
        var result=retrieve(new KnowledgeOperation.ListDocuments(suite.oracles().title_filter(),Optional.empty(),50),262_144);
        var page=(KnowledgeDocumentResult.CatalogPage)result.documentResult().orElseThrow();
        assertEquals(suite.oracles().title_filtered_document_ids(),page.documents().stream().map(KnowledgeDocumentResult.Document::documentId).toList());
        var first=(KnowledgeDocumentResult.CatalogPage)retrieve(new KnowledgeOperation.ListDocuments("",Optional.empty(),1),262_144).documentResult().orElseThrow();
        assertReason("knowledge_document_cursor_rejected",()->retrieve(new KnowledgeOperation.ListDocuments("差旅",first.nextCursor(),1),262_144));
        assertReason("knowledge_document_cursor_rejected",()->retrieve(new KnowledgeOperation.ListDocuments("",Optional.of("broken"),1),262_144));
        var empty=(KnowledgeDocumentResult.CatalogPage)retrieve(new KnowledgeOperation.ListDocuments("不存在的固定标题",Optional.empty(),20),262_144).documentResult().orElseThrow();
        assertTrue(empty.documents().isEmpty());assertTrue(empty.nextCursor().isEmpty());
    }

    @Test void longDocumentResumesUnicodeRangesAndPreservesExactFullOriginal()throws Exception {
        var source=longDocument();int offset=0,pages=0;var text=new StringBuilder();
        while(true) {
            var bundle=retrieve(read(source,offset,suite.oracles().read_max_characters()),262_144);
            var slice=(KnowledgeDocumentResult.DocumentSlice)bundle.documentResult().orElseThrow();
            String actual=JSON.readTree(bundle.items().getFirst().content().canonicalJson()).get("text").textValue();
            assertEquals(offset,slice.startOffset());assertEquals(actual.codePointCount(0,actual.length()),slice.endOffset()-slice.startOffset());
            assertEquals(suite.oracles().long_total_characters(),slice.document().totalCharacters());
            assertEquals(KnowledgeOperatorType.DOCUMENT_READ,bundle.items().getFirst().operator());
            assertEquals(JdbcKnowledgePortal.DOCUMENT_CONTENT_SCHEMA,bundle.items().getFirst().content().schemaId());
            text.append(actual);assertTrue(++pages<=8);
            if(slice.nextOffset().isEmpty())break;
            offset=slice.nextOffset().getAsInt();
        }
        assertEquals(source.text(),text.toString());
        assertTrue(text.substring(text.offsetByCodePoints(0,suite.oracles().long_total_characters()-4000)).contains(suite.oracles().long_end_marker()));
        for(int position:suite.oracles().non_bmp_offsets()) {
            var bundle=retrieve(read(source,position,1),262_144);
            String actual=JSON.readTree(bundle.items().getFirst().content().canonicalJson()).get("text").textValue();
            assertEquals(1,actual.codePointCount(0,actual.length()));assertEquals(2,actual.length());
        }
    }

    @Test void foreignTenantAndUnmountedDocumentsHaveSameUnavailableResultUnderRls()throws Exception {
        for(var document:suite.documents().stream().filter(value->suite.oracles().denied_document_ids().contains(value.document_id())).toList())
            assertReason("knowledge_document_unavailable",()->retrieve(read(document,0,10),262_144));
        var allowed=longDocument();
        assertReason("knowledge_document_unavailable",()->retrieve(new KnowledgeOperation.ReadDocument(allowed.source_id(),"999999999",allowed.source_version(),0,10),262_144));
        try(var connection=restricted.getConnection()) {
            connection.setAutoCommit(false);
            try(var query=connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)")) {
                query.setString(1,suite.oracles().catalog_tenant_id());query.execute();
            }
            try(var query=connection.prepareStatement("SELECT count(*) FROM nw_knowledge_document_versions WHERE tenant_id<>?::bigint")) {
                query.setString(1,suite.oracles().catalog_tenant_id());try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            }
            connection.rollback();
        }
    }

    @Test void immutableVersionTitleAndBodySurviveCurrentDocumentPointerChange()throws Exception {
        var original=suite.documents().getFirst();
        insertVersion(original,"q06-current-unapproved","当前未冻结标题","current body must not replace frozen text",91_069_100L,false);
        var page=(KnowledgeDocumentResult.CatalogPage)retrieve(new KnowledgeOperation.ListDocuments("",Optional.empty(),50),262_144).documentResult().orElseThrow();
        assertEquals(original.title(),page.documents().getFirst().title());
        var result=retrieve(read(original,0,16_000),262_144);
        assertEquals(original.text(),JSON.readTree(result.items().getFirst().content().canonicalJson()).get("text").textValue());
        assertReason("knowledge_document_unavailable",()->retrieve(new KnowledgeOperation.ReadDocument(original.source_id(),original.document_id(),"q06-current-unapproved",0,100),262_144));
    }

    @Test void serializedMetadataAndBodyShareBudgetAndRangesNeverClampInvalidOffsets()throws Exception {
        assertReason("knowledge_document_budget_exceeded",()->retrieve(new KnowledgeOperation.ListDocuments("",Optional.empty(),20),1));
        var source=longDocument();
        var bundle=retrieve(read(source,0,4000),1000);var slice=(KnowledgeDocumentResult.DocumentSlice)bundle.documentResult().orElseThrow();
        String metadata=DOCUMENTS.encode(slice).canonicalJson();String body=bundle.items().getFirst().content().canonicalJson();
        assertTrue(metadata.codePointCount(0,metadata.length())+body.codePointCount(0,body.length())<=1000);
        assertTrue(slice.endOffset()>0&&slice.endOffset()<4000);assertEquals(slice.endOffset(),slice.nextOffset().orElseThrow());
        assertReason("knowledge_document_range_rejected",()->retrieve(read(source,Integer.MAX_VALUE,1),262_144));
        assertReason("knowledge_document_budget_exceeded",()->retrieve(read(source,0,1),1));
    }

    @Test void legalEmptyAndBlankFrozenTitlesRemainExactAndDoNotBlockOriginalReads()throws Exception {
        var replacements=new LinkedHashMap<String,String>();replacements.put("q06-empty-title","");replacements.put("q06-blank-title"," \t ");
        var documents=suite.documents().subList(0,2);var versions=new HashSet<>(target.sourceVersions());int index=0;
        for(var replacement:replacements.entrySet()) {
            var source=documents.get(index);long id=91_069_200L+index++;
            try(var connection=admin.getConnection()) {
                connection.setAutoCommit(false);
                try(var query=connection.prepareStatement("INSERT INTO nw_knowledge_document_versions(id,tenant_id,kb_id,document_id,source_version,title,source_type,content_id,chunk_set_id,status,chunk_count,created_at,embedding_model_config_id,embedding_model_revision,embedding_dimensions) SELECT ?,tenant_id,kb_id,document_id,?,?,source_type,content_id,chunk_set_id,'processing',chunk_count,created_at,embedding_model_config_id,embedding_model_revision,embedding_dimensions FROM nw_knowledge_document_versions WHERE tenant_id=?::bigint AND document_id=?::bigint AND source_version=?")) {
                    query.setLong(1,id);query.setString(2,replacement.getKey());query.setString(3,replacement.getValue());query.setString(4,source.tenant_id());query.setString(5,source.document_id());query.setString(6,source.source_version());assertEquals(1,query.executeUpdate());
                }
                try(var query=connection.prepareStatement("UPDATE nw_knowledge_document_versions SET status='ready' WHERE id=?")){query.setLong(1,id);query.executeUpdate();}
                try(var query=connection.prepareStatement("UPDATE nw_knowledge_documents SET current_source_version=? WHERE id=?::bigint")){query.setString(1,replacement.getKey());query.setString(2,source.document_id());query.executeUpdate();}
                connection.commit();
            }
            versions.remove(new KnowledgeSourceVersion(source.source_id(),source.source_version()));versions.add(new KnowledgeSourceVersion(source.source_id(),replacement.getKey()));
        }
        String manifest=JSON.writeValueAsString(versions.stream().map(KnowledgeSourceVersion::sourceVersion).sorted().toList());
        String policy="{\"model_invocable\":true,\"user_invocable\":true,\"allowed_operators\":[\"KEYWORD\",\"DOCUMENT_LIST\",\"DOCUMENT_READ\"],\"freshness_policy\":\"version_pinned\",\"citation_required\":true}";
        try(var connection=admin.getConnection();var query=connection.prepareStatement("INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) VALUES(91069502,91069001,910601,'KNOWLEDGE_SOURCE','revision:2','Q06 blank titles','TENANT',?::jsonb || jsonb_build_object('source_id','910610','source_versions',?::jsonb),'q06-blank-title-hash','PUBLISHED','2026-01-01','2026-01-01')")) {
            query.setString(1,policy);query.setString(2,manifest);query.executeUpdate();
        }
        try(var connection=admin.getConnection();var query=connection.prepareStatement("INSERT INTO nw_knowledge_base_revisions(id,tenant_id,kb_id,revision,status,source_versions,capability_policy,capability_version_id,ready_at,published_at) VALUES(91069503,910601,910610,2,'published',?::jsonb,?::jsonb,91069502,'2026-01-01','2026-01-01')")) {
            connection.setAutoCommit(false);
            // 与正式发布一致：同一事务归档旧published版本、发布新版本并切换active指针，不绕过唯一约束。
            try(var archive=connection.prepareStatement("UPDATE nw_knowledge_base_revisions SET status='archived' WHERE tenant_id=910601 AND kb_id=910610 AND status='published'")){assertEquals(1,archive.executeUpdate());}
            query.setString(1,manifest);query.setString(2,policy);query.executeUpdate();
            try(var activate=connection.prepareStatement("UPDATE nw_knowledge_bases SET active_revision_id=91069503 WHERE tenant_id=910601 AND id=910610")){assertEquals(1,activate.executeUpdate());}
            connection.commit();
        }
        var selected=new FrozenKnowledgeTarget(target.handle(),new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE,"91069001","91069502"),target.descriptorId(),2,versions,target.allowedOperators(),target.timeout());
        var binding=bindings.publish(new TenantId(suite.oracles().catalog_tenant_id()),selected);
        var catalog=(KnowledgeDocumentResult.CatalogPage)retrieve(new KnowledgeOperation.ListDocuments("",Optional.empty(),50),262_144,selected,binding).documentResult().orElseThrow();
        index=0;
        for(var replacement:replacements.entrySet()) {
            var source=documents.get(index++);var metadata=catalog.documents().stream().filter(value->value.documentId().equals(source.document_id())).findFirst().orElseThrow();
            assertEquals(replacement.getValue(),metadata.title());
            var result=retrieve(new KnowledgeOperation.ReadDocument(source.source_id(),source.document_id(),replacement.getKey(),0,16_000),262_144,selected,binding);
            assertEquals(source.text(),JSON.readTree(result.items().getFirst().content().canonicalJson()).get("text").textValue());
        }
    }

    @Test void exactEofReturnsOnlyMetadataAndPassesFrozenRequestResultClosure() {
        var document=longDocument();int end=suite.oracles().long_total_characters();
        var original=retrieve(read(document,end,1),262_144);
        var slice=(KnowledgeDocumentResult.DocumentSlice)original.documentResult().orElseThrow();
        assertEquals(end,slice.startOffset());assertEquals(end,slice.endOffset());assertTrue(slice.nextOffset().isEmpty());
        assertTrue(original.items().isEmpty());assertEquals(EvidenceSufficiency.INSUFFICIENT,original.sufficiency());
        String encoded=DOCUMENTS.encode(slice).canonicalJson();
        var exact=retrieve(read(document,end,1),encoded.codePointCount(0,encoded.length()));
        assertEquals(slice,exact.documentResult().orElseThrow());assertTrue(exact.items().isEmpty());
    }

    @Test void platformDocumentAccessRejectsMultipleKnowledgeBasesBeforeQuery() {
        var versions=new HashSet<>(target.sourceVersions());versions.add(new KnowledgeSourceVersion("910611","q06-unmounted"));
        var multi=new FrozenKnowledgeTarget(target.handle(),target.capability(),target.descriptorId(),target.descriptorRevision(),versions,target.allowedOperators(),target.timeout());
        var binding=new JdbcKnowledgeBindings.Binding(multi,frozen.descriptorJson(),frozen.publishedAt(),frozen.snapshotId());
        assertReason("knowledge_request_scope_rejected",()->retrieve(new KnowledgeOperation.ListDocuments("",Optional.empty(),20),262_144,multi,binding));
    }

    private static EvidenceBundle retrieve(KnowledgeOperation operation,int characters) {
        return retrieve(operation,characters,target,frozen);
    }
    private static EvidenceBundle retrieve(KnowledgeOperation operation,int characters,FrozenKnowledgeTarget selected,JdbcKnowledgeBindings.Binding binding) {
        var tenant=new TenantId(suite.oracles().catalog_tenant_id());
        KnowledgeOperatorType operator=operation instanceof KnowledgeOperation.ListDocuments?KnowledgeOperatorType.DOCUMENT_LIST:KnowledgeOperatorType.DOCUMENT_READ;
        var request=new KnowledgeRequest("q06-request",tenant,new RunRequester.EndUser(new EndUserId(1)),new AgentId("q06-agent"),new SessionRef(tenant,"q06-session"),
                new TraceEnvelope("q06-trace","q06-span",new InputId("q06-input"),Optional.empty()),KnowledgeMode.RESEARCH,"q06-document-access",operation,
                selected.sourceVersions().stream().map(KnowledgeSourceVersion::sourceId).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                selected.sourceVersions(),Set.of(operator),new KnowledgeBudget(1,50,65_536,characters,5000),asOf,Optional.empty(),true);
        var result=portal.retrieve(request,selected,binding,"q06-fingerprint-"+selected.descriptorRevision()+":"+operation, new InvocationControl(Instant.now().plusSeconds(30),()->false),()->true);
        new com.skillforge.runtime.core.FrozenKnowledgeRequest(selected,request).requireResult(result);
        return result;
    }
    private static KnowledgeOperation.ReadDocument read(CorpusDocument document,int offset,int characters) {
        return new KnowledgeOperation.ReadDocument(document.source_id(),document.document_id(),document.source_version(),offset,characters);
    }
    private static CorpusDocument longDocument(){return suite.documents().stream().filter(value->value.document_id().equals(suite.oracles().long_document_id())).findFirst().orElseThrow();}
    private static void assertReason(String reason,org.junit.jupiter.api.function.Executable operation) {
        assertEquals(reason,assertThrows(JdbcKnowledgePortal.RetrievalRejected.class,operation).reasonCode());
    }
    private static DataSource dataSource(String user,String password){var source=new PGSimpleDataSource();source.setURL(DATABASE.getJdbcUrl());source.setUser(user);source.setPassword(password);return source;}
    private static void seed()throws Exception {
        for(var document:suite.documents().stream().collect(java.util.stream.Collectors.toMap(CorpusDocument::source_id,value->value,(first,next)->first,LinkedHashMap::new)).values()) {
            try(var connection=admin.getConnection();var query=connection.prepareStatement("INSERT INTO nw_knowledge_bases(id,tenant_id,name,status,embedding_model_config_id,embedding_model_revision,embedding_dimensions) VALUES(?::bigint,?::bigint,'Q06 fixed corpus','active',81,3,2)")) {
                query.setString(1,document.source_id());query.setString(2,document.tenant_id());query.executeUpdate();
            }
        }
        long identity=91_060_100L;
        for(var document:suite.documents())insertVersion(document,document.source_version(),document.title(),document.text(),identity+=10,true);
        String versions=JSON.writeValueAsString(suite.documents().stream().filter(value->value.mounted()&&value.tenant_id().equals(suite.oracles().catalog_tenant_id())).map(CorpusDocument::source_version).toList());
        String policy="{\"model_invocable\":true,\"user_invocable\":true,\"allowed_operators\":[\"KEYWORD\",\"DOCUMENT_LIST\",\"DOCUMENT_READ\"],\"freshness_policy\":\"version_pinned\",\"citation_required\":true}";
        KnowledgeBindingDatabaseFixture.sql(admin,"INSERT INTO nw_capability_definitions(id,tenant_id,capability_type,capability_key,name,trust_level,status,created_at,updated_at) VALUES(91069001,910601,'KNOWLEDGE_SOURCE','knowledge-base:910610','Q06 corpus','TENANT','PUBLISHED','2026-01-01','2026-01-01')");
        try(var connection=admin.getConnection();var query=connection.prepareStatement("INSERT INTO nw_capability_versions(id,definition_id,tenant_id,capability_type,version_key,name,trust_level,descriptor,content_hash,status,created_at,published_at) VALUES(91069002,91069001,910601,'KNOWLEDGE_SOURCE','revision:1','Q06 corpus','TENANT',?::jsonb || jsonb_build_object('source_id','910610','source_versions',?::jsonb),'q06-corpus-hash','PUBLISHED','2026-01-01','2026-01-01')")) {
            query.setString(1,policy);query.setString(2,versions);query.executeUpdate();
        }
        try(var connection=admin.getConnection();var query=connection.prepareStatement("INSERT INTO nw_knowledge_base_revisions(id,tenant_id,kb_id,revision,status,source_versions,capability_policy,capability_version_id,ready_at,published_at) VALUES(91069003,910601,910610,1,'published',?::jsonb,?::jsonb,91069002,'2026-01-01','2026-01-01')")) {
            query.setString(1,versions);query.setString(2,policy);query.executeUpdate();
        }
        KnowledgeBindingDatabaseFixture.sql(admin,"UPDATE nw_knowledge_bases SET active_revision_id=91069003 WHERE id=910610");
    }
    /** 合成语料经正式不可变正文/集合/版本关系保存，版本ready后不再修改正文。 */
    private static void insertVersion(CorpusDocument document,String version,String title,String body,long id,boolean newDocument)throws Exception {
        try(var connection=admin.getConnection()) {
            connection.setAutoCommit(false);
            if(newDocument)try(var query=connection.prepareStatement("INSERT INTO nw_knowledge_documents(id,tenant_id,kb_id,current_source_version,created_at) VALUES(?::bigint,?::bigint,?::bigint,NULL,'2026-01-02')")) {
                query.setString(1,document.document_id());query.setString(2,document.tenant_id());query.setString(3,document.source_id());query.executeUpdate();
            }
            try(var query=connection.prepareStatement("INSERT INTO nw_knowledge_document_contents(id,tenant_id,document_id,format,digest,content) VALUES(?,?::bigint,?::bigint,'text/plain; charset=utf-8',encode(sha256(convert_to(?,'UTF8')),'hex'),?)")) {
                query.setLong(1,id);query.setString(2,document.tenant_id());query.setString(3,document.document_id());query.setString(4,body);query.setString(5,body);query.executeUpdate();
            }
            try(var query=connection.prepareStatement("INSERT INTO nw_knowledge_chunk_sets(id,tenant_id,kb_id,document_id) VALUES(?,?::bigint,?::bigint,?::bigint)")) {
                query.setLong(1,id);query.setString(2,document.tenant_id());query.setString(3,document.source_id());query.setString(4,document.document_id());query.executeUpdate();
            }
            try(var query=connection.prepareStatement("INSERT INTO nw_knowledge_document_versions(id,tenant_id,kb_id,document_id,source_version,title,source_type,content_id,chunk_set_id,status,chunk_count,created_at,embedding_model_config_id,embedding_model_revision,embedding_dimensions) VALUES(?,?::bigint,?::bigint,?::bigint,?,?,'file',?,?,'processing',1,'2026-01-02',81,3,2)")) {
                query.setLong(1,id);query.setString(2,document.tenant_id());query.setString(3,document.source_id());query.setString(4,document.document_id());query.setString(5,version);query.setString(6,title);query.setLong(7,id);query.setLong(8,id);query.executeUpdate();
            }
            String chunk=body.substring(0,body.offsetByCodePoints(0,Math.min(4000,body.codePointCount(0,body.length()))));
            try(var query=connection.prepareStatement("INSERT INTO nw_knowledge_chunk_contents(id,tenant_id,document_id,format,digest,content) VALUES(?,?::bigint,?::bigint,'text/plain; charset=utf-8',encode(sha256(convert_to(?,'UTF8')),'hex'),?)")) {
                query.setLong(1,id);query.setString(2,document.tenant_id());query.setString(3,document.document_id());query.setString(4,chunk);query.setString(5,chunk);query.executeUpdate();
            }
            try(var query=connection.prepareStatement("INSERT INTO nw_knowledge_chunks(id,tenant_id,kb_id,document_id,chunk_index,content_id,created_at,chunk_set_id) VALUES(?,?::bigint,?::bigint,?::bigint,0,?,'2026-01-02',?)")) {
                query.setLong(1,id);query.setString(2,document.tenant_id());query.setString(3,document.source_id());query.setString(4,document.document_id());query.setLong(5,id);query.setLong(6,id);query.executeUpdate();
            }
            try(var query=connection.prepareStatement("UPDATE nw_knowledge_document_versions SET status='ready' WHERE id=?")){query.setLong(1,id);query.executeUpdate();}
            try(var query=connection.prepareStatement("UPDATE nw_knowledge_documents SET current_source_version=? WHERE id=?::bigint")){query.setString(1,version);query.setString(2,document.document_id());query.executeUpdate();}
            connection.commit();
        }
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    record Suite(int schema_version,List<CorpusDocument> documents,Oracles oracles) { }
    record CorpusDocument(String tenant_id,String knowledge_handle,String source_id,String document_id,String source_version,String title,String text,boolean mounted) { }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    record Oracles(String catalog_tenant_id,String catalog_handle,List<String> mounted_document_ids,String title_filter,List<String> title_filtered_document_ids,
            String long_document_id,int long_total_characters,int read_max_characters,List<Integer> non_bmp_offsets,String long_end_marker,List<String> denied_document_ids) { }
}
