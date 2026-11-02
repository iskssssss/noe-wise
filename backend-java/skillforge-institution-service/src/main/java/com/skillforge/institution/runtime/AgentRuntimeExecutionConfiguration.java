package com.skillforge.institution.runtime;

import com.skillforge.core.billing.metering.OpenPlatformRequestMetering;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.core.workflow.execution.WorkflowParser;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.institution.group.GroupControlService;
import com.skillforge.institution.group.GroupControlMapper;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.institution.team.TeamWorkInputs;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.runtime.adapter.jdbc.budget.JdbcExecutionBudgets;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.callback.JdbcCallbackRegistrationPort;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgePortal;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionDelegations;
import com.skillforge.runtime.adapter.jdbc.session.JdbcSessionLogPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.core.capability.integration.runtime.AgentRuntimeMcpToolProvider;
import com.skillforge.runtime.adapter.model.OpenAiModelPort;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.CoreRuntime;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.SessionExecutionPlans;
import com.skillforge.runtime.session.WorkflowNodeProgram;
import com.skillforge.runtime.session.ProgramToolProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionTokenCounter;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import com.skillforge.runtime.starter.ExecutionHostScheduler;
import com.skillforge.runtime.starter.ExecutionHostWorker;
import com.skillforge.runtime.starter.ExecutionTenantDiscovery;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * typed执行主链的唯一生产装配根；受理、程序与Workflow节点共用Store及五十槽Worker。
 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeExecutionConfiguration {
    @Bean
    AgentExecutionCodec executionCodec(AgentRuntimeProperties properties) { return new AgentExecutionCodec(limits(properties)); }

    @Bean
    SessionExecutionWorkflows executionWorkflows(AgentRuntimeProperties properties) { return new SessionExecutionWorkflows(limits(properties)); }

    @Bean
    com.skillforge.institution.workflowtest.WorkflowTestCancellation workflowTestCancellation(ObjectProvider<AgentRuntimeExecutionGateway> gateways,AgentRuntimeProperties properties) {
        return session->gateways.getObject().cancelIsolatedTest(session,properties.getWorkerId());
    }

    @Bean
    SessionExecutionPlans executionPlansCodec(AgentRuntimeProperties properties) { return new SessionExecutionPlans(limits(properties)); }

    /** 准入冻结唯一能力配置材料，恢复时只读取原record，不重新选择路由或知识版本。 */
    @Bean
    AgentRuntimeExecutionCatalog executionCatalog(AgentRuntimePublishedCapabilities capabilities, JdbcKnowledgeBindings knowledge,
            ObjectMapper json, AgentRuntimePayloadValidation payloads, AgentRuntimeProperties properties,
            ObjectProvider<InstitutionPlanHost> plans, SessionLogPort log,WorkflowTestRuntimePolicy tests) {
        return new AgentRuntimeExecutionCatalog(capabilities, knowledge, json, payloads, properties, plans, log,tests);
    }

    @Bean
    AgentRuntimeGroupControl executionGroupControl(GroupControlService controls,
            AgentRuntimeProperties properties) {
        return new AgentRuntimeGroupControl(controls, new AgentExecutionCodec(limits(properties)));
    }

    @Bean
    JdbcExecutionStore executionStore(DataSource source, JdbcIdentityPolicyPort identities, ExecutionBudgetPort budgets, ObjectProvider<AgentRuntimeExecutionSources> sources,
            ObjectProvider<AgentRuntimeExecutionProjection> projection, ObjectProvider<AgentRuntimeWorkflowNodeExecutions> nodes,
            ObjectProvider<AgentRuntimeProgramTools> programs, ObjectProvider<AgentRuntimeGoalDriver> goals,
            AgentRuntimeGroupControl groupControl,WorkflowTestRuntimePolicy tests,AgentExecutionCodec codec,SessionExecutionWorkflows workflows) {
        if (!(budgets instanceof JdbcExecutionBudgets jdbc)) throw new IllegalStateException("execution_transactional_budget_required");
        return new JdbcExecutionStore(source, identities, jdbc, (session, run, actor, prepared) -> tests.allowed(session,run,prepared,codec,workflows) && (AgentRuntimeProgramTools.owns(run)
                ? programs.getObject().allowed(session, run, actor, prepared) : AgentRuntimeWorkflowNodeExecutions.owns(run)
                ? nodes.getObject().allowed(session, run, actor, prepared)
                : (run.identity().owner().target().kind()==com.skillforge.runtime.api.SessionTarget.Kind.GROUP || goals.getObject().dispatchAllowed(session,run.identity())) && sources.getObject().allowed(session, run, actor, prepared)),
                configuration -> { OpenPlatformRequestMetering.configure(configuration); WorkflowTestRuntimePolicy.configure(configuration);com.skillforge.institution.group.GroupMessageMapper.configure(configuration);
                    GroupControlMapper.configure(configuration);
                    com.skillforge.core.foundation.integration.IntegrationProvenanceService.configure(configuration);
                    AgentRuntimePublishedCapabilities.configureExecution(configuration); JdbcSessionDelegations.configure(configuration);
                    SessionMailboxService.configureBorrowed(configuration);
                    com.skillforge.core.chat.message.MemoryService.configureBorrowed(configuration);
                    AgentRuntimeWorkflowNodeExecutions.configure(configuration); JdbcSessionLogPort.configure(configuration); JdbcDagWorkPort.configure(configuration);
                    TeamWorkInputs.configure(configuration); AgentRuntimePlanDependencies.configure(configuration); JdbcCallbackRegistrationPort.configure(configuration); },
                (session, run) -> { if (programs.getObject().active(run)) programs.getObject().confirmActivity(session, run);
                    else sources.getObject().confirmActivity(session, run); },
                (session, run, actor, operation, input) -> tests.control(session,run,operation) && sources.getObject().allowedControl(session, run, actor, operation, input),
                session -> projection.getObject().outputsCommitted(session),
                (session, owner, events, readAfter) -> projection.getObject().appendBorrowed(session, owner, events, readAfter),
                groupControl);
    }

    @Bean
    GroupMemoryRecall groupMemoryRecall(com.skillforge.runtime.memory.MemoryRuntime runtime,
            com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort memories, java.time.Clock clock,
            MemorySearchProperties settings) {
        return new GroupMemoryRecall(runtime, memories, clock, settings);
    }

    @Bean
    AgentRuntimeExecutionSources executionSources(AgentRuntimeModelConfiguration configuration, JdbcModelBindings models, JdbcMcpBindings mcp,
            JdbcKnowledgeBindings knowledge,
            JdbcWorkflowBindings workflows,
            MediaPort media,
            AgentRuntimePublishedCapabilities capabilities, AgentRuntimeExecutionCatalog catalogs, JdbcIdentityPolicyPort identities, SessionTokenCounter tokens,
            ExecutionBudgetPort budgets, ExecutionStore store, AgentRunBindings runs, AgentRuntimeProperties properties,
            ObjectProvider<AgentDelegationHost> delegations, ObjectProvider<InstitutionPlanHost> plans, ObjectProvider<TeamWorkService> teams,
            ObjectProvider<InstitutionOutputContract> outputs, java.time.Clock clock, GroupMemoryRecall memoryRecall,
            com.skillforge.runtime.session.spi.SessionContextSummary summaries, ObjectMapper json, AgentRuntimeCompactionMapper compactions) {
        return new AgentRuntimeExecutionSources(configuration, models, mcp, knowledge, workflows, media, capabilities, catalogs, identities, tokens, budgets, store, runs, properties, delegations, plans, teams, outputs, clock, memoryRecall, summaries, json, compactions);
    }

    @Bean
    AgentExecutionProgram executionProgram(AgentRuntimeExecutionSources sources, CoreRuntime core, AgentRuntimeProperties properties) {
        return new AgentExecutionProgram(sources, core, limits(properties));
    }

    @Bean
    RegisteredExecutionProviderCalls executionProviderCalls(OpenAiModelPort models,
            AgentRuntimeModelInvocationAudit audit, AgentRuntimeToolInvocationAudit toolAudit, AgentRuntimePlatformToolProvider tools, AgentRuntimeMcpToolProvider mcp,
            JdbcKnowledgePortal knowledge, ExecutionStore store, CoreRuntime core, AgentRuntimeProperties properties,WorkflowTestRuntimePolicy tests) {
        return new RegisteredExecutionProviderCalls(models, audit, toolAudit, tools, mcp, knowledge, store, core, limits(properties),tests);
    }

    @Bean
    AgentRuntimeExecutionCapabilities executionCapabilities(AgentRuntimeExecutionSources sources, RegisteredExecutionProviderCalls calls,
            AgentExecutionCodec codec, SessionExecutionWorkflows workflows, ObjectProvider<SdkWorkflowPortal> portals, JdbcExecutionLedgerPort executions,
            WorkflowInteractionView interactions, AgentRuntimeBodyPreview previews) {
        return new AgentRuntimeExecutionCapabilities(sources, calls, codec, workflows, portals, executions, interactions, previews);
    }

    @Bean
    WorkflowNodeRequests executionWorkflowNodeRequests(ObjectMapper json, AgentRuntimePayloadValidation payloads,
            AgentRuntimeModelConfiguration profiles, JdbcModelBindings models, AgentRuntimeProperties properties,
            AgentRuntimeWorkflowScripts scripts, WorkflowScriptNodeCodec scriptCodec, java.time.Clock clock, SessionTokenCounter tokens) {
        return new WorkflowNodeRequests(json, payloads, profiles, models, limits(properties), scripts, scriptCodec, clock, tokens);
    }

    @Bean
    WorkflowScriptNodeCodec executionWorkflowScriptCodec(ObjectMapper json, AgentRuntimePayloadValidation payloads) {
        return new WorkflowScriptNodeCodec(json, payloads);
    }

    @Bean
    RegisteredWorkflowScriptCalls executionWorkflowScriptCalls(JdbcExecutionLedgerPort executions, JdbcWorkflowBindings bindings,
            WorkflowParser parser, AgentRuntimeWorkflowScripts scripts, WorkflowScriptNodeCodec scriptCodec, RegisteredExecutionProviderCalls calls) {
        return new RegisteredWorkflowScriptCalls(executions, bindings, parser, scripts, scriptCodec, calls);
    }

    @Bean
    AgentRuntimeWorkflowNodeExecutions executionWorkflowNodes(JdbcExecutionStore store, ExecutionBudgetPort budgets,
            JdbcIdentityPolicyPort identities, JdbcModelBindings models, JdbcMcpBindings mcp, JdbcKnowledgeBindings knowledge,
            WorkflowNodeRequests requests, WorkflowParser parser, RegisteredExecutionProviderCalls providers, AgentExecutionCodec codec, RegisteredWorkflowScriptCalls scripts) {
        return new AgentRuntimeWorkflowNodeExecutions(store, budgets, identities, models, mcp, knowledge, requests, parser, providers, codec, scripts);
    }

    @Bean
    WorkflowNodeProgram executionWorkflowNodeProgram(AgentRuntimeWorkflowNodeExecutions sources, CoreRuntime core, AgentRuntimeProperties properties) {
        return new WorkflowNodeProgram(sources, core, limits(properties));
    }

    @Bean
    AgentRuntimeProgramTools executionProgramTools(JdbcExecutionStore store, ExecutionBudgetPort budgets, JdbcIdentityPolicyPort identities,
            AgentRuntimeExecutionCatalog catalogs, AgentRuntimePublishedCapabilities capabilities, AgentRunBindings runs, JdbcMcpBindings mcp,
            RegisteredExecutionProviderCalls providers, ObjectProvider<AgentRuntimeExecutionWorker> workers, AgentRuntimePayloadValidation payloads,
            ObjectMapper json, AgentRuntimeProperties properties) {
        return new AgentRuntimeProgramTools(store, budgets, identities, catalogs, capabilities, runs, mcp, providers, workers, payloads, json, properties);
    }

    @Bean
    ProgramToolProgram executionProgramToolProgram(AgentRuntimeProgramTools sources, AgentRuntimeProperties properties) {
        return new ProgramToolProgram(sources, limits(properties));
    }

    /** 一个活动调度线程覆盖所有槽位的租约、预算活动及到期通知，不执行Provider业务。 */
    @Bean(destroyMethod = "close")
    AgentRuntimeExecutionActivity executionActivity(ExecutionStore store, AgentRuntimeExecutionSources sources,
            AgentRuntimeWorkflowNodeExecutions nodes, AgentRuntimeProperties properties, ExecutionHostScheduler scheduler,
            ObjectProvider<AgentRuntimeProgramTools> programs) {
        return new AgentRuntimeExecutionActivity(store, sources, nodes, properties.getWorkerLeaseDuration(), scheduler.controls(), false, programs::getObject);
    }

    @Bean
    AgentRuntimeBodyPreview executionBodyPreview(SessionMailboxService mailbox, PublicOutputGuard guard, ObjectMapper json, AgentRuntimeProperties properties) {
        return new AgentRuntimeBodyPreview(mailbox, guard, json, properties.getMaxPayloadCodeUnits(), Duration.ofMillis(100), System::nanoTime);
    }

    @Bean @Primary
    AgentRuntimeExecutionProjection executionProjection(ExecutionStore store, SessionMailboxService mailbox, PublicOutputGuard guard, ObjectMapper json, AgentRuntimeProperties properties) {
        return new AgentRuntimeExecutionProjection(store, mailbox, guard, json,
                properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth());
    }

    @Bean
    AgentRuntimeExecutionPlans executionPlans(JdbcExecutionStore store, AgentRuntimeExecutionSources sources,
            ObjectProvider<InstitutionPlanHost> plans, ObjectProvider<TeamWorkService> teams, ObjectMapper json, AgentRuntimeProperties properties) {
        return new AgentRuntimeExecutionPlans(store, sources, plans, teams, json, properties);
    }

    @Bean
    AgentRuntimePlanDependencies executionPlanDependencies(JdbcExecutionStore store, ObjectProvider<InstitutionPlanHost> plans, AgentRunBindings bindings,
            AgentRuntimeHostProvisioner provisioner, JdbcIdentityPolicyPort identities, ObjectProvider<AgentRuntimeExecutionWorker> workers,
            AgentRuntimePlanDependencies.Mapper mapper, ObjectMapper json, SessionExecutionPlans codec, AgentRuntimeProperties properties) {
        return new AgentRuntimePlanDependencies(store, plans, bindings, provisioner, identities, workers, mapper, json, codec, properties.getWorkerId());
    }

    @Bean
    AgentRuntimeExecutionHost institutionExecutionHost(ExecutionStore store, AgentExecutionProgram program, AgentRuntimeExecutionCapabilities capabilities,
            AgentRuntimeExecutionSources sources, ExecutionBudgetPort budgets,
            AgentRuntimeProperties properties, PublicOutputGuard guard, ObjectMapper json,
            AgentRuntimeExecutionActivity activity, WorkflowNodeProgram nodeProgram, ProgramToolProgram programTools,
            AgentRuntimeWorkflowNodeExecutions nodes, ObjectProvider<AgentDelegationHost> delegations, AgentRuntimeExecutionPlans plans,
            AgentRuntimePlanDependencies dependencies, ObjectProvider<AgentRuntimeWorkflowRecoveryWorker> recovery, AgentRuntimeWorkflowCallbackDeliveries callbacks,
            ObjectProvider<TeamWorkService> teams, ObjectProvider<AgentRuntimeWorkflowCancellations> cancellations,
            AgentRuntimeExecutionCatalog catalogs, WorkflowNodeRequests requests,
            ObjectProvider<com.skillforge.institution.scheduler.SessionOutboxDispatcher> outputs, ObjectProvider<AgentRuntimeProgramTools> programs,
            ObjectProvider<AgentRuntimeGoalDriver> goals, ObjectProvider<InstitutionOutputContract> publications,
            ObjectProvider<com.skillforge.institution.artifact.DeliverablePublicationJob> publicationRecovery,ObjectProvider<com.skillforge.institution.workflowtest.WorkflowTestSessionService> workflowTests) {
        recovery.getObject();
        var routed = new AgentRuntimeExecutionPrograms(program, nodeProgram, programTools);
        ExecutionCoordinator.Capabilities routedCapabilities = new ExecutionCoordinator.Capabilities() {
            @Override public ExecutionCoordinator.CapabilityResult invoke(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
                return AgentRuntimeProgramTools.owns(registered.run()) ? programs.getObject().invoke(registered, prepared)
                        : AgentRuntimeWorkflowNodeExecutions.owns(registered.run()) ? nodes.invoke(registered, prepared)
                        : prepared.kind() == ExecutionStore.Kind.DELEGATION ? delegations.getObject().invoke(registered, prepared) : capabilities.invoke(registered, prepared);
            }
            @Override public Optional<ExecutionCoordinator.CapabilityResult> observe(ExecutionStore.Run run, ExecutionStore.Prepared prepared, ExecutionStore.Attempt original) {
                return AgentRuntimeProgramTools.owns(run) ? programs.getObject().observe(run, prepared, original)
                        : AgentRuntimeWorkflowNodeExecutions.owns(run) ? nodes.observe(run, prepared, original) : Optional.empty();
            }
        };
        ExecutionCoordinator.Controls controls = run -> {
            if (run.identity().owner().target().kind()!=com.skillforge.runtime.api.SessionTarget.Kind.GROUP && !AgentRuntimeProgramTools.owns(run) && !AgentRuntimeWorkflowNodeExecutions.owns(run)) goals.getObject().requireExecution(run.identity());
            return activity.open(run);
        };
        return new AgentRuntimeExecutionHost(new ExecutionCoordinator<>(store, protectProgram(routed, guard, json, limits(properties)), routedCapabilities, controls, plans),
                store, sources, activity, catalogs, requests, programs::getObject,
                run -> { if(catalogs.testSession(run.owner().sessionRef()).isEmpty())publications.getObject().reconcile(run); },
                run -> { delegations.getObject().committed(run); nodes.committed(run); dependencies.changed(run);
                    teams.getObject().changed(run); cancellations.getObject().changed(run); },
                (tenant, limit) -> { publicationRecovery.getObject().recover(tenant, limit);
                    delegations.getObject().recover(tenant, limit); nodes.recover(tenant, limit);
                    dependencies.recover(tenant, limit); callbacks.recover(tenant, limit);
                    teams.getObject().recover(tenant, limit); cancellations.getObject().recover(tenant, limit); outputs.getObject().recover(tenant, limit); workflowTests.getObject().recover(tenant,limit); });
    }

    @Bean
    ExecutionTenantDiscovery institutionExecutionTenants(RuntimeWorkerRecoveryMapper tenants) { return tenants::activeTenantPage; }

    @Bean(destroyMethod = "close")
    AgentRuntimeExecutionWorker executionWorker(ExecutionHostWorker shared, AgentRuntimeExecutionHost host, AgentRuntimeExecutionActivity activity,
            ObjectProvider<AgentRuntimeProgramTools> programs) {
        return new AgentRuntimeExecutionWorker(shared, host.coordinator(), activity, programs::getObject);
    }

    @Bean
    AgentRuntimeExecutionSteering executionSteering(ExecutionStore store, AgentRuntimeExecutionWorker worker, AgentRuntimeProperties properties) {
        return new AgentRuntimeExecutionSteering(store, worker, limits(properties));
    }

    @Bean @Primary
    AgentRuntimeExecutionGateway executionGateway(ExecutionStore store, AgentExecutionProgram program, AgentRuntimeExecutionWorker worker,
            AgentRuntimeHostProvisioner provisioner, AgentRuntimeTrustedAccess access, JdbcIdentityPolicyPort identities,
            ServicePrincipalMapper principals, AgentRunBindings bindings, ChatInputReferenceResolver references,
            MemoryService memory, AgentExecutionCodec codec, ObjectMapper json, AgentRuntimePayloadValidation payloads,
            AgentRuntimeExecutionProjection history, SessionMediaService media, PublicOutputGuard publicGuard, AgentRuntimeExecutionCatalog catalogs,
            AgentRuntimeExecutionSteering steering, WorkflowInteractionView workflowInteractions, AgentRuntimeWorkflowRecoveryWorker workflows,
            com.skillforge.core.chat.session.SessionService productSessions, ObjectProvider<AgentRuntimeGoalDriver> goals,
            com.skillforge.runtime.api.port.SessionStorePort sessionStore, java.time.Clock clock,WorkflowTestRuntimePolicy tests,OpenPlatformRequestMetering requestMetering) {
        return new AgentRuntimeExecutionGateway(store, program, worker, provisioner, access, identities, principals, bindings, references, memory, codec, json, payloads, history, media, publicGuard, catalogs, steering, workflowInteractions, workflows, productSessions, goals, sessionStore, clock,tests,requestMetering);
    }

    /** 所有程序共用公开输出守卫，包括批次逐项观察与整组采纳。 */
    private static <M> ExecutionCoordinator.Program<M> protectProgram(ExecutionCoordinator.Program<M> program, PublicOutputGuard guard, ObjectMapper json, CodecLimits limits) {
        return new ExecutionCoordinator.Program<>() {
            @Override public ExecutionStore.Prepared restorePrepared(ExecutionStore.Run run, ExecutionStore.Prepared prepared) {
                return program.restorePrepared(run,prepared);
            }
            @Override public M open(ExecutionStore.Run run, ExecutionStore.Material context) { return program.open(run, context); }
            @Override public ExecutionCoordinator.Step continuation(ExecutionStore.Run run, M materials, ExecutionStore.Waiting waiting, ExecutionStore.Material response) {
                return program.continuation(run, materials, waiting, response);
            }
            @Override public ExecutionCoordinator.Adoption<M> acceptResponse(ExecutionStore.Run run, M materials, ExecutionStore.Waiting waiting, ExecutionStore.Material response) {
                return protectAdoption(program.acceptResponse(run, materials, waiting, response), guard, json, limits);
            }
            @Override public ExecutionCoordinator.Adoption<M> acceptInput(ExecutionStore.Run run, M materials, ExecutionStore.SupplementalInput input, ExecutionStore.Material content) {
                return protectAdoption(program.acceptInput(run, materials, input, content), guard, json, limits);
            }
            @Override public Optional<ExecutionCoordinator.Step.Finish> dispatchRejected(ExecutionStore.Run run, M materials, String reason) {
                return program.dispatchRejected(run, materials, reason).map(finish -> {
                    var events = finish.events().stream().map(event -> protect(event, guard, json, limits)).toList();
                    return events.equals(finish.events()) ? finish : new ExecutionCoordinator.Step.Finish(RunStatus.FAILED, events);
                });
            }
            @Override public Optional<ExecutionCoordinator.Adoption<M>> batchDispatchRejectionAdoption(ExecutionStore.Run run, M materials,
                    List<ExecutionCoordinator.BatchOutcome> results, String reason) {
                return program.batchDispatchRejectionAdoption(run, materials, results, reason).map(adoption -> protectAdoption(adoption, guard, json, limits));
            }
            @Override public M inputCommitted(ExecutionStore.InputAcceptance accepted, M materials) {
                return program.inputCommitted(accepted, materials);
            }
            @Override public ExecutionCoordinator.Adoption<M> planAdoption(ExecutionStore.Run run, M materials, ExecutionCoordinator.Step.PlanLocal command, ExecutionStore.Material actualResult) {
                return protectAdoption(program.planAdoption(run, materials, command, actualResult), guard, json, limits);
            }
            @Override public boolean batchParallel(M materials) { return program.batchParallel(materials); }
            @Override public ExecutionCoordinator.BatchResult inspectBatch(ExecutionStore.Run run, M materials, ExecutionCoordinator.BatchCall result, int slot) {
                var inspected = program.inspectBatch(run, materials, result, slot);
                return new ExecutionCoordinator.BatchResult(inspected.waiting(), inspected.events().stream().map(event -> protect(event, guard, json, limits)).toList());
            }
            @Override public ExecutionCoordinator.Adoption<M> adoptBatch(ExecutionStore.Run run, M materials, List<ExecutionCoordinator.BatchOutcome> results) {
                return protectAdoption(program.adoptBatch(run, materials, results), guard, json, limits);
            }
            @Override public ExecutionCoordinator.BatchPlan<M> batchContinuation(ExecutionStore.Run run, M materials,
                    List<ExecutionCoordinator.BatchOutcome> results, List<ExecutionCoordinator.BatchResponse> responses) {
                var plan = program.batchContinuation(run, materials, results, responses);
                if (plan instanceof ExecutionCoordinator.BatchPlan.Terminate<M> terminal)
                    return new ExecutionCoordinator.BatchPlan.Terminate<>(protectAdoption(terminal.adoption(), guard, json, limits), terminal.status());
                return plan;
            }
            @Override public ExecutionCoordinator.Adoption<M> adopt(ExecutionStore.Run run, M materials, ExecutionStore.Prepared prepared, ExecutionCoordinator.CapabilityResult result) {
                return protectAdoption(program.adopt(run, materials, prepared, result), guard, json, limits);
            }
            @Override public ExecutionCoordinator.Step next(ExecutionStore.Run run, M materials) {
                var step = program.next(run, materials);
                if (!(step instanceof ExecutionCoordinator.Step.Finish finish)) return step;
                var events = finish.events().stream().map(event -> protect(event, guard, json, limits)).toList();
                return events.equals(finish.events()) ? finish : new ExecutionCoordinator.Step.Finish(RunStatus.FAILED, events);
            }
        };
    }

    private static <M> ExecutionCoordinator.Adoption<M> protectAdoption(ExecutionCoordinator.Adoption<M> adopted, PublicOutputGuard guard, ObjectMapper json, CodecLimits limits) {
        return new ExecutionCoordinator.Adoption<>(adopted.materials(), adopted.next(), adopted.context(),
                adopted.events().stream().map(event -> protect(event, guard, json, limits)).toList(), adopted.waiting());
    }

    private static ExecutionStore.Event protect(ExecutionStore.Event event, PublicOutputGuard guard, ObjectMapper json, CodecLimits limits) {
        if (com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE.equals(event.type())) {
            com.skillforge.runtime.session.ExecutionObservation.decode(event.data(), limits.maxCodeUnits(), limits.maxNodes(), limits.maxDepth());
            return event;
        }
        // 内部上下文事件必须随原提交保留；它们不承担公开正文或Trace契约。
        if (com.skillforge.runtime.session.AgentExecutionContextViews.EVENT.equals(event.type())
                && com.skillforge.runtime.session.AgentExecutionContextViews.SCHEMA.equals(event.data().schemaId())
                || "agent.context-compaction-attempt".equals(event.type())
                && "skillforge.runtime.execution.context-compaction-attempt.v1".equals(event.data().schemaId()))
            return event;
        if (AgentExecutionProgram.WORKFLOW_STARTED_SCHEMA.equals(event.data().schemaId())
                || AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA.equals(event.data().schemaId()))
            return AgentRuntimeExecutionPublicEvents.protectWorkflow(event, guard, json);
        if (AgentExecutionProgram.WAITING_SCHEMA.equals(event.data().schemaId()))
            return AgentRuntimeExecutionPublicEvents.protectWaiting(event, guard, json);
        if (AgentExecutionProgram.TOOL_RESULT_SCHEMA.equals(event.data().schemaId()))
            return AgentRuntimeExecutionPublicEvents.protectToolResult(event, guard, json);
        if (AgentExecutionProgram.ACCEPTED_SCHEMA.equals(event.data().schemaId()) && AgentExecutionProgram.ACCEPTED_EVENT.equals(event.type())
                || AgentExecutionProgram.RESOLVED_SCHEMA.equals(event.data().schemaId()) && AgentExecutionProgram.RESOLVED_EVENT.equals(event.type()))
            return event;
        if (AgentExecutionProgram.MODEL_RESULT_SCHEMA.equals(event.data().schemaId()) && AgentExecutionProgram.MODEL_RESULT_EVENT.equals(event.type()))
            return AgentRuntimeExecutionPublicEvents.protectModelResult(event, guard, json);
        boolean userMessage = AgentRuntimeExecutionResponses.USER_MESSAGE_SCHEMA.equals(event.data().schemaId())
                && AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT.equals(event.type());
        if (!userMessage && !(AgentExecutionProgram.COMPLETED_SCHEMA.equals(event.data().schemaId())
                && AgentExecutionProgram.COMPLETED_EVENT.equals(event.type())))
            throw new IllegalArgumentException("execution_public_event_unsupported");
        try {
            var data = (ObjectNode) json.readTree(event.data().canonicalJson());
            var scope = guard.capture();
            if (!scope.rejects(data.path("text").asText()) && (userMessage || !AgentRuntimeExecutionPublicEvents.citationsRejected(data, scope))) return event;
            data.put("text", PublicOutputGuard.SAFE_MESSAGE);
            if (!userMessage) { data.put("status", RunStatus.FAILED.name()); data.put("reason", PublicOutputGuard.REJECTION_CODE); data.putArray("evidence"); }
            return new ExecutionStore.Event(event.id(), event.type(), new TypedPayload(event.data().schemaId(), json.writeValueAsString(data)));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("execution_public_event_invalid", invalid); }
    }

    private static CodecLimits limits(AgentRuntimeProperties properties) {
        return new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth());
    }
}
