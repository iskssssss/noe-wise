package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.config.TenantRlsInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

/** 独立 PG、强制 RLS 与真实 mapper 验证请求事务中断、复合归属和幂等唯一键。 */
@Testcontainers(disabledWithoutDocker=false)
class HandoffReplyMapperPostgresTest {
 @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("handoff");
 static SqlSessionFactory factory;
 @BeforeAll static void initialize() throws Exception {
  try(var connection=DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());var sql=connection.createStatement()) {
   sql.execute("CREATE TABLE nw_tenants(id bigint PRIMARY KEY); CREATE TABLE nw_tenant_members(id bigint PRIMARY KEY,tenant_id bigint NOT NULL REFERENCES nw_tenants(id)); CREATE TABLE nw_human_handoff_tickets(id bigint PRIMARY KEY,ticket_id varchar(64) UNIQUE NOT NULL,tenant_id bigint NOT NULL REFERENCES nw_tenants(id)); INSERT INTO nw_tenants VALUES(1),(2); INSERT INTO nw_tenant_members VALUES(42,1),(43,2); INSERT INTO nw_human_handoff_tickets VALUES(1,'ticket',1),(2,'other-ticket',2)");
   try(var migration=HandoffReplyMapperPostgresTest.class.getClassLoader().getResourceAsStream("db/migration/V257__handoff_human_assistance.sql")) {
    assertNotNull(migration);sql.execute(new String(migration.readAllBytes(),StandardCharsets.UTF_8));
   }
   sql.execute("CREATE ROLE handoff_app LOGIN PASSWORD 'test-only' NOSUPERUSER NOBYPASSRLS; GRANT USAGE ON SCHEMA public TO handoff_app; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO handoff_app");
  }
  var data=new PGSimpleDataSource();data.setURL(PG.getJdbcUrl());data.setUser("handoff_app");data.setPassword("test-only");
  var configuration=new Configuration(new Environment("handoff",new JdbcTransactionFactory(),data));configuration.setMapUnderscoreToCamelCase(true);configuration.addInterceptor(new TenantRlsInterceptor());
  try(var resource=HandoffReplyMapperPostgresTest.class.getClassLoader().getResourceAsStream("mapper/HandoffReplyMapper.xml")) {
   new XMLMapperBuilder(resource,configuration,"mapper/HandoffReplyMapper.xml",configuration.getSqlFragments()).parse();
  }
  factory=new SqlSessionFactoryBuilder().build(configuration);
 }
 @AfterEach void clear(){TenantContext.clear();}
 @Test void rollbackDoesNotLoseAcceptedRequestAndRebuiltWriterCommitsOnce(){
  TenantContext.setTenantId("1");
  try(var tx=factory.openSession()){assertEquals(1,tx.getMapper(HandoffReplyMapper.class).insert(request(1,"restart",42,"ticket")));tx.commit();}
  try(var tx=factory.openSession()){var mapper=tx.getMapper(HandoffReplyMapper.class);assertEquals("accepted",mapper.claim(1,"restart").getStatus());assertEquals(1,mapper.commit(1,"restart","source",7));tx.rollback();}
  try(var rebuilt=factory.openSession()){var mapper=rebuilt.getMapper(HandoffReplyMapper.class);assertEquals("accepted",mapper.find(1,"restart").getStatus());assertEquals(1,mapper.commit(1,"restart","source",7));rebuilt.commit();}
  try(var tx=factory.openSession()){var mapper=tx.getMapper(HandoffReplyMapper.class);assertEquals("committed",mapper.find(1,"restart").getStatus());assertEquals(0,mapper.commit(1,"restart","another-source",8));}
  TenantContext.setTenantId("2");try(var tx=factory.openSession()){assertNull(tx.getMapper(HandoffReplyMapper.class).find(1,"restart"),"错误租户参数仍受强制RLS拒绝");}
 }
 @Test void tenantCompositeForeignKeyAndUniqueCommandRejectDifferentBody(){
  TenantContext.setTenantId("1");try(var tx=factory.openSession()){var mapper=tx.getMapper(HandoffReplyMapper.class);mapper.insert(request(1,"duplicate",42,"ticket"));tx.commit();}
  try(var tx=factory.openSession()){var changed=request(1,"duplicate",42,"ticket");changed.setContent("另一条正文");assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,()->tx.getMapper(HandoffReplyMapper.class).insert(changed));tx.rollback();}
  TenantContext.setTenantId("2");try(var tx=factory.openSession()){assertThrows(org.apache.ibatis.exceptions.PersistenceException.class,()->tx.getMapper(HandoffReplyMapper.class).insert(request(2,"cross",43,"ticket")));tx.rollback();}
 }
 static HandoffReplyRequest request(long tenant,String key,long operator,String ticket){var row=new HandoffReplyRequest();row.setTenantId(tenant);row.setCommandId(key);row.setTicketId(ticket);row.setSessionId("source-session");row.setEndUserId(99L);row.setOperatorId(operator);row.setOperatorName("坐席");row.setContent("人工协助");row.setRequestFingerprint("a".repeat(64));return row;}
}
