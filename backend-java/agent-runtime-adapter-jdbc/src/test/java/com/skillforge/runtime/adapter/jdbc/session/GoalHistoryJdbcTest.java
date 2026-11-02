package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.adapter.jdbc.CurrentRuntimeDatabase;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.json.RuntimeJson;
import com.skillforge.runtime.session.SessionGoal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** 独立 PostgreSQL/RLS 的长 Goal 日志与重新创建端口恢复验收。 */
class GoalHistoryJdbcTest {
    @Test
    void longHistoryRetainsReceiptsAndCasAfterRestart() throws Exception {
        try (var pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                .withDatabaseName("skillforge").withUsername("goal_admin").withPassword("goal_admin_password")) {
            pg.start();
            CurrentRuntimeDatabase.migrate(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var admin = new PGSimpleDataSource(); admin.setURL(pg.getJdbcUrl()); admin.setUser(pg.getUsername()); admin.setPassword(pg.getPassword());
            try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE ROLE goal_test LOGIN PASSWORD 'goal_test_password' NOSUPERUSER NOBYPASSRLS");
                statement.execute("GRANT USAGE ON SCHEMA public TO goal_test");
                statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO goal_test");
                statement.execute("GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO goal_test");
            }
            var source = new PGSimpleDataSource(); source.setURL(pg.getJdbcUrl()); source.setUser("goal_test"); source.setPassword("goal_test_password");
            try (var connection=source.getConnection(); var statement=connection.createStatement();
                 var rows=statement.executeQuery("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
            }
            var tenant = new TenantId("goal-tenant"); var agent = new AgentId("goal-agent");
            var owner = SessionOwner.user(new SessionRef(tenant, "goal-session"), agent, new EndUserId(707));
            var input = new InputId("goal-input"); var trace = new TraceEnvelope("goal-trace", "goal-span", input, Optional.empty());
            var run = new RunIdentity(owner, agent, input, new RunRef(tenant, "goal-run"), trace,
                    new ResultRef("identity"), new RunRequester.EndUser(owner.requireUser()), Optional.empty(), new RunOriginRef(tenant, input.value()));
            var log = new JdbcSessionLogPort(source); var goals = new SessionGoal(log); var at = Instant.parse("2026-01-01T00:00:00Z");
            var original = goals.create(run, "create-0", "目标", 1, at);
            var complete = goals.update(run, "complete-0", 1, SessionGoal.State.COMPLETE, null, at);
            var identity = List.of(tenant.value(), owner.sessionRef().sessionId(), owner.target().kind().name(), owner.target().id(), Long.toString(owner.requireUser().value()));
            String predecessor = original.goalId() + ":2"; var batch = new ArrayList<SessionLogPort.AppendSessionEvent>();
            for (int i=1; i<5000; i++) {
                String op="create-"+i; String goal="goal-"+hash("skillforge.runtime.goal-id.v1", RuntimeJson.writeCanonical(List.of(identity, op)));
                batch.add(event(owner, run, at, identity, "create:"+predecessor, op, "create", 2, goal, 1, "ACTIVE"));
                batch.add(event(owner, run, at, identity, goal+":2", "complete-"+i, "update", 1, goal, 2, "COMPLETE"));
                predecessor=goal+":2";
                if (batch.size()==64) { append(log,batch); batch.clear(); }
            }
            if (!batch.isEmpty()) append(log,batch);
            var next=goals.create(run,"create-5000","后续目标",1,at);
            var recovered=new SessionGoal(new JdbcSessionLogPort(source));
            assertEquals(next,recovered.read(owner).orElseThrow());
            assertEquals(original,recovered.create(run,"create-0","目标",1,at.plusSeconds(1)));
            assertEquals(Optional.of(complete),recovered.updateReceipt(run,"complete-0",1));
            assertEquals(SessionGoal.Reason.OPERATION_CONFLICT,assertThrows(SessionGoal.Rejected.class,
                    ()->recovered.create(run,"create-0","不一致",1,at)).reason());
            var paused=recovered.pause(owner,agent,next.revision(),"pause-new",new InputId("pause"),trace,"user_paused",at);
            assertEquals(paused,new SessionGoal(new JdbcSessionLogPort(source)).read(owner).orElseThrow());
            assertEquals(SessionGoal.Reason.REVISION_CONFLICT,assertThrows(SessionGoal.Rejected.class,
                    ()->recovered.reserveRound(owner,agent,next.revision(),"stale",new InputId("round"),trace,at)).reason());
            var other=SessionOwner.user(new SessionRef(tenant,"other-session"),agent,owner.requireUser());
            assertTrue(recovered.read(other).isEmpty());
        }
    }
    private static void append(SessionLogPort log,List<SessionLogPort.AppendSessionEvent> events) {
        var results=log.appendBatch(events); assertEquals(events.size(),results.size());
        results.forEach(result->assertInstanceOf(SessionLogPort.AppendResult.Appended.class,result));
    }
    private static SessionLogPort.AppendSessionEvent event(SessionOwner owner, RunIdentity run, Instant at, List<String> identity,
            String slot,String op,String command,long expected,String goal,long revision,String state) {
        var snapshot=new TreeMap<String,Object>(); snapshot.put("goalId",goal); snapshot.put("revision",Long.toString(revision));
        snapshot.put("objective","目标"); snapshot.put("state",state); snapshot.put("maxRounds","1"); snapshot.put("roundsStarted","0");
        snapshot.put("pendingRoundInputId",null); snapshot.put("lastRunId",run.runRef().runId()); snapshot.put("lastRunTenantId",run.runRef().tenantId().value()); snapshot.put("reason",null);
        var fingerprint=new TreeMap<String,Object>(); fingerprint.put("command",command); fingerprint.put("operationId",op); fingerprint.put("runId",run.runRef().runId());
        if (command.equals("create")) { fingerprint.put("maxRounds","1"); fingerprint.put("objective","目标"); }
        else { fingerprint.put("expectedRevision",Long.toString(expected)); fingerprint.put("reason",null); fingerprint.put("state",state); }
        var payload=new TypedPayload("skillforge.runtime.goal-change.v1",RuntimeJson.writeCanonical(Map.of("command",command,"expectedRevision",Long.toString(expected),"operationFingerprint",hash("skillforge.runtime.goal-operation.v1",RuntimeJson.writeCanonical(fingerprint)),"operationId",op,"snapshot",snapshot)));
        var target=new SessionProjectionTarget(owner,run.agentId(),command.equals("create")?new InputId(op):run.causationInputId(),Optional.of(run.runRef()),Optional.empty(),Optional.empty(),Optional.empty());
        var id=new EventId("goal-slot:"+hash("skillforge.runtime.goal-slot.v1",RuntimeJson.writeCanonical(List.of(identity,slot))));
        var draft=new SessionEventDraft(id,"goal/change","1",hash(payload.schemaId(),payload.canonicalJson()),run.trace(),payload,at);
        return new SessionLogPort.AppendSessionEvent(target,draft);
    }
    private static String hash(String schema,String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((schema+"\n"+value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
