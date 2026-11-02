package com.skillforge.institution.team;

import com.skillforge.common.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.dag.DagWork;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.springframework.http.HttpStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 工作控制输入保留各自幂等键；处理结果引用既有 Run/steering 事实，不改变任务生命周期。 */
@Service
public class TeamWorkInputs {
    private static final org.slf4j.Logger DATABASE_LOG = org.slf4j.LoggerFactory.getLogger(TeamWorkInputs.class);
    private static final int MAX_INPUT_ID_LENGTH = 240;
    private static final int MAX_CONTROL_CONTENT_LENGTH = 65536;
    private static final int RECENT_RECEIPT_LIMIT = 20;
    private static final String ACCEPTED = "ACCEPTED";
    private static final String APPLIED = "APPLIED";
    private static final String REJECTED = "REJECTED";
    private final TeamWorkInputMapper mapper;
    private final ObjectMapper json;

    /** 真实计划采纳在调用方同一连接中读取控制回执，不开启 Spring 事务。 */
    public List<Receipt> recentBorrowed(SqlSession sqlSession, DagWork work) {
        return receipts(sqlSession.getMapper(TeamWorkInputMapper.class), work);
    }

    /** 将现有业务映射加入执行事务配置；未重新定义另一份控制输入存储。 */
    public static void configure(Configuration configuration) {
        if (configuration.hasMapper(TeamWorkInputMapper.class)) return;
        String resource = "mapper/TeamWorkInputMapper.xml";
        try (var stream = TeamWorkInputs.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("team_work_inputs_mapper_missing");
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("team_work_inputs_mapper_invalid", failure); }
    }

    /** 只返回原业务状态及安全说明，不以受理或补充回执证明已批准计划。 */
    private List<Receipt> receipts(TeamWorkInputMapper source, DagWork work) {
        return source.recent(tenant(work), session(work), work.key().rootInputId().value(), RECENT_RECEIPT_LIMIT).stream()
                .map(row -> {
                    var control = read(row.payload);
                    return new Receipt(row.inputId, control.taskId(), control.operation(), control.planRevision(), row.state,
                            switch (row.state) { case APPLIED -> "操作已受理"; case REJECTED -> "操作未被受理"; default -> "正在处理操作"; });
                }).toList();
    }
    /**
     * 装配本领域持久化和协作依赖。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param mapper 领域持久化入口，使用调用方事务
     * @param json 领域序列化器
     */
    public TeamWorkInputs(TeamWorkInputMapper mapper,ObjectMapper json) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "TeamWorkInputs", TenantContext.getTenantId());
        try {
            this.mapper=mapper;this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "TeamWorkInputs", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "TeamWorkInputs", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    public record Control(String inputId,String taskId,String operation,String content,Long planRevision,
                          String pendingId,String parametersVersion,String agentId) {
        public Control {
            if(inputId==null||inputId.isBlank()||inputId.length()>MAX_INPUT_ID_LENGTH||operation==null||operation.isBlank())
                throw new IllegalArgumentException("控制输入必须有独立标识和明确类型");
            taskId=taskId==null?"":taskId;content=content==null?"":content;
            pendingId=pendingId==null?"":pendingId;parametersVersion=parametersVersion==null?"":parametersVersion;
            agentId=agentId==null?"":agentId;
            if(content.length()>MAX_CONTROL_CONTENT_LENGTH)throw new IllegalArgumentException("补充内容过长");
        }
    }
    public record Input(Control control,Instant receivedAt,String state,String detail) { }

    /**
     * 在事务锁下幂等登记控制输入，重复键必须匹配原内容。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param control 具有独立幂等键的控制输入
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional
    public Input accept(DagWork work,Control control) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "accept", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            String payload=write(control);
            mapper.lock("dag:"+tenant(work)+":"+session(work));
            mapper.insert(tenant(work),session(work),work.key().rootInputId().value(),control.inputId(),payload);
            var row=mapper.find(tenant(work),session(work),control.inputId());
            if(row==null) throw new IllegalStateException("dag_input_insert_missing");
            if(!work.key().rootInputId().value().equals(row.rootInputId)||!payload.equals(row.payload))
                throw new ApiException(HttpStatus.CONFLICT,"输入标识已用于其他工作或操作");
            return new Input(control,row.receivedAt,row.state,row.detail);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "accept", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "accept", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 按接收顺序读取待处理控制输入。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly=true)
    public List<Input> pending(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "pending", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            return mapper.pending(tenant(work),session(work),work.key().rootInputId().value(),ACCEPTED).stream()
                    .map(row->new Input(read(row.payload),row.receivedAt,row.state,row.detail)).toList();

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "pending", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "pending", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 仅将待处理输入标记为已受理。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param inputId 控制输入独立幂等键
     * @param detail 内部回执说明，不写入日志
     */
    @Transactional
    public void applied(DagWork work,String inputId,String detail) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "applied", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
     settle(work,inputId,APPLIED,detail);
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "applied", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "applied", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /** 已纳入只表示执行入口接收，不宣称模型已遵循；接口不返回未消费的正文或内部凭据。 */
    public record Receipt(@JsonProperty("input_id") String inputId,
            @JsonProperty("task_id") String taskId,String operation,
            @JsonProperty("plan_revision") Long planRevision,String state,String detail) { }

    /**
     * 返回有限数量的公开回执，隐藏输入正文。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    @Transactional(readOnly=true)
    public List<Receipt> recent(DagWork work) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "recent", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
            return receipts(mapper, work);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "recent", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "recent", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }

    /**
     * 生成或登记受控拒绝结果。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * 入口使用已认证调用或持久化执行事实的租户，完成或异常时恢复线程上下文。
     * @param work 带可信所有者的工作事实
     * @param inputId 控制输入独立幂等键
     * @param detail 内部回执说明，不写入日志
     */
    @Transactional
    public void rejected(DagWork work,String inputId,String detail) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "rejected", TenantContext.getTenantId());
        String databasePreviousTenant = TenantContext.getTenantId();
        String databaseTenant = work.key().owner().sessionRef().tenantId().value();
        try {
            if (databasePreviousTenant != null && !databasePreviousTenant.equals(databaseTenant))
                throw new SecurityException("database_tenant_scope_mismatch");
            TenantContext.setTenantId(databaseTenant);
     settle(work,inputId,REJECTED,detail);
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "rejected", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            if (databasePreviousTenant == null) TenantContext.clear(); else TenantContext.setTenantId(databasePreviousTenant);
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "rejected", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 按预期状态完成控制回执，防止覆盖终态。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @param inputId 控制输入独立幂等键
     * @param state 拟写入的回执状态
     * @param detail 内部回执说明，不写入日志
     */
    private void settle(DagWork work,String inputId,String state,String detail) {
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "settle", TenantContext.getTenantId());
        try {
            mapper.settle(state,detail,tenant(work),session(work),work.key().rootInputId().value(),inputId,ACCEPTED);

        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "settle", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "settle", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 读取所属租户标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String tenant(DagWork work){
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "tenant", TenantContext.getTenantId());
        try {
    return work.key().owner().sessionRef().tenantId().value();
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "tenant", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "tenant", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 读取工作所属会话标识。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param work 带可信所有者的工作事实
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String session(DagWork work){
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "session", TenantContext.getTenantId());
        try {
    return work.key().owner().sessionRef().sessionId();
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "session", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "session", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 生成稳定 JSON 快照供持久化与摘要计算使用。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private String write(Object value){
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "write", TenantContext.getTenantId());
        try {
    try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException(failure);}
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "write", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "write", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
    /**
     * 反序列化内部持久化快照，格式损坏时失败。
     * <p>业务校验与存储异常向调用方传播；诊断日志不记录输入正文和返回内容。
     * @param value 待校验或序列化的领域值
     * @return 按方法类型返回领域结果；空值或空集合语义遵循本操作契约
     */
    private Control read(String value){
        final long databaseStarted = System.nanoTime();
        DATABASE_LOG.debug("数据库业务方法进入 component={} method={} tenant={}", "TeamWorkInputs", "read", TenantContext.getTenantId());
        try {
    try{return json.readValue(value,Control.class);}catch(Exception failure){throw new IllegalArgumentException(failure);}
        } catch (RuntimeException | Error databaseFailure) {
            DATABASE_LOG.debug("数据库业务方法失败 component={} method={} tenant={} failureType={}", "TeamWorkInputs", "read", TenantContext.getTenantId(), databaseFailure.getClass().getSimpleName());
            throw databaseFailure;
        } finally {
            DATABASE_LOG.debug("数据库业务方法退出 component={} method={} tenant={} elapsedMicros={}", "TeamWorkInputs", "read", TenantContext.getTenantId(), (System.nanoTime()-databaseStarted)/1000);
        }
    }
}
