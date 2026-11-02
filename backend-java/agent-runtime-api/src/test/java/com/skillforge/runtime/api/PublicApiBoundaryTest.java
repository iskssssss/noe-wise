package com.skillforge.runtime.api;

import com.skillforge.runtime.api.media.ImageDetail;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ExecutionBudgetScope;
import com.skillforge.runtime.api.port.MediaPort;
import com.skillforge.runtime.api.port.ModelTokenBounds;
import com.skillforge.runtime.api.port.ContextReference;
import com.skillforge.runtime.api.port.ConversationTaskPort;
import com.skillforge.runtime.api.port.ConversationTask;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.AgentObservation;
import com.skillforge.runtime.api.port.CancellationSignal;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.CallbackRegistrationPort;
import com.skillforge.runtime.api.port.CommittedRuntimeEvent;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.api.port.PlanCommand;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.NativeAgentTools;
import com.skillforge.runtime.api.port.DelegationObservationStatus;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.ExecutionLedgerPort;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.IdentityPolicyPort;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.KnowledgePortal;
import com.skillforge.runtime.api.port.LedgerEvent;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.LeaseMutationStatus;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.ProviderCallContext;
import com.skillforge.runtime.api.port.ProviderDispatch;
import com.skillforge.runtime.api.port.ProviderInvocationException;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.ModelResolutionPort;
import com.skillforge.runtime.api.port.PublicationAcknowledge;
import com.skillforge.runtime.api.port.PublicationClaimRequest;
import com.skillforge.runtime.api.port.PublicationMutationResult;
import com.skillforge.runtime.api.port.PublicationRetry;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.RuntimeWorkIdentity;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionEventDraft;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionProjectionTarget;
import com.skillforge.runtime.api.port.SessionStorePort;
import com.skillforge.runtime.api.port.StateEventClaim;
import com.skillforge.runtime.api.port.StateEventPublication;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowPortal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** 验证首个公开 API 基线的精确 façade、sealed union 与禁止依赖边界。 */
class PublicApiBoundaryTest {

    private static final String API_PACKAGE = "com.skillforge.runtime.api";

    private static final Set<Class<?>> PUBLIC_TOP_LEVEL_TYPES = Set.of(
            SessionTarget.class,
            com.skillforge.runtime.api.dag.DagPlan.class,
            com.skillforge.runtime.api.dag.DagWork.class,
            com.skillforge.runtime.api.dag.DagWorkPort.class,
            ContextReference.class,
            ConversationTask.class,
            ConversationTaskPort.class,
            AccessEnvelope.class,
            ActionRef.class,
            AgentObservation.class,
            AgentId.class,
            AuthenticationRef.class,
            CancelCommand.class,
            CancellationSignal.class,
            CapabilityRef.class,
            CapabilitySnapshotRef.class,
            CapabilityType.class,
            CallbackRegistrationPort.class,
            CommittedRuntimeEvent.class,
            CommittedSessionEvent.class,
            DelegationCommand.class,
            PlanCommand.class,
            PlanPort.class,
            NativeAgentTools.class,
            DelegationObservationStatus.class,
            DelegationRef.class,
            EndUserId.class,
            EvidenceBundle.class,
            EvidenceItem.class,
            EvidenceRef.class,
            EvidenceSufficiency.class,
            EventId.class,
            EventStreamRequest.class,
            ExecutionLedgerPort.class,
            ExecutionBudgetPort.class,
            ExecutionBudgetScope.class,
            ExecutionMode.class,
            ExecutionRef.class,
            ExportedValue.class,
            FactStatus.class,
            IdempotencyConflictException.class,
            IdempotencyKey.class,
            IdentityPolicyPort.class,
            InboundEnvelope.class,
            InboundKind.class,
            InputId.class,
            InputReceipt.class,
            InputReceiptStatus.class,
            InvocationControl.class,
            LedgerEvent.class,
            LeaseFence.class,
            LeaseMutationStatus.class,
            LoopSuspendReason.class,
            LoopTerminationReason.class,
            KnowledgeBudget.class,
            KnowledgeCapacity.class,
            KnowledgeDocumentResult.class,
            KnowledgeMode.class,
            KnowledgeOperation.class,
            KnowledgeOperationLimits.class,
            KnowledgeOperatorType.class,
            KnowledgePlan.class,
            KnowledgeRequest.class,
            KnowledgeSourceVersion.class,
            KnowledgeStep.class,
            KnowledgeTraceStep.class,
            MemoryOwner.class,
            MemoryTaskConstraint.class,
            MemoryRetrievalPolicy.class,
            MemoryRetrievalDiagnostics.class,
            MemoryFact.class,
            MemoryFactRef.class,
            MemoryQuery.class,
            MemoryQueryProfile.class,
            MemoryRetrievalTrace.class,
            MemoryScope.class,
            MemoryTypeRef.class,
            MemoryPort.class,
            ImageDetail.class,
            MediaRef.class,
            ModelContent.class,
            ModelImagePolicy.class,
            MediaPort.class,
            ModelTokenBounds.class,
            ToolCallLimits.class,
            ModelPort.class,
            ModelResolutionPort.class,
            PortCallContext.class,
            PortConflict.class,
            PortFailure.class,
            PortalCallKind.class,
            PortalOutcomeSnapshot.class,
            PortalResolution.class,
            Presentation.class,
            PrincipalRef.class,
            PublicationAcknowledge.class,
            PublicationClaimRequest.class,
            PublicationMutationResult.class,
            PublicationRetry.class,
            ProviderCallContext.class,
            ProviderDispatch.class,
            ProviderInvocationException.class,
            RequestId.class,
            ResultRef.class,
            RetentionClassRef.class,
            RetryMode.class,
            RunOutcome.class,
            RunQuery.class,
            RunRef.class,
            RunIdentity.class,
            RunOriginRef.class,
            SourceMessageRef.class,
            com.skillforge.runtime.api.RunRequester.class,
            RunSnapshot.class,
            RunStatus.class,
            RunTarget.class,
            RuntimeAccessException.class,
            RuntimeEvent.class,
            RuntimeReport.class,
            RuntimeWorkIdentity.class,
            SessionEventDraft.class,
            SessionLogPort.class,
            SessionQuery.class,
            SessionRef.class,
            GroupBindingRef.class,
            SessionSubject.class,
            SessionSnapshot.class,
            SessionOwner.class,
            SessionProjectionTarget.class,
            SessionStorePort.class,
            StateEventClaim.class,
            StateEventPublication.class,
            TenantId.class,
            ToolEffect.class,
            ToolPortal.class,
            TraceEnvelope.class,
            TypedPayload.class,
            VerifiedIdentity.class,
            WorkflowExecutionState.class,
            com.skillforge.runtime.api.port.WorkflowNodeCallIdentity.class,
            WorkflowPortal.class,
            KnowledgePortal.class);

    private static final Set<Class<?>> ALLOWED_JDK_TYPES = Set.of(
            Double.class,
            Integer.class,
            AutoCloseable.class,
            java.util.function.Supplier.class,
            byte[].class,
            long[].class,
            Object.class,
            String.class,
            Record.class,
            Enum.class,
            IllegalArgumentException.class,
            IllegalStateException.class,
            SecurityException.class,
            FunctionalInterface.class,
            java.time.Instant.class,
            Duration.class,
            List.class,
            Map.class,
            Optional.class,
            OptionalDouble.class,
            OptionalInt.class,
            OptionalLong.class,
            SecureRandom.class,
            Set.class,
            Flow.Publisher.class);

    /** 验证三个 sealed union 的 permits 集合精确且可穷尽。 */
    @Test
    void freezesExactSealedUnionMembers() {
        assertEquals(
                Set.of(RunTarget.NewRun.class, RunTarget.ExistingRun.class),
                Set.of(RunTarget.class.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        RunOutcome.Completed.class,
                        RunOutcome.Suspended.class,
                        RunOutcome.Terminated.class,
                        RunOutcome.Failed.class,
                        RunOutcome.Cancelled.class),
                Set.of(RunOutcome.class.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        RuntimeEvent.Queued.class,
                        RuntimeEvent.Started.class,
                        RuntimeEvent.Progressed.class,
                        RuntimeEvent.Suspended.class,
                        RuntimeEvent.Resumed.class,
                        RuntimeEvent.Completed.class,
                        RuntimeEvent.Failed.class,
                        RuntimeEvent.Terminated.class,
                        RuntimeEvent.Cancelled.class),
                Set.of(RuntimeEvent.class.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        PublicationMutationResult.Applied.class,
                        PublicationMutationResult.Replayed.class,
                        PublicationMutationResult.StaleLease.class,
                        PublicationMutationResult.NotFound.class,
                        PublicationMutationResult.Terminal.class),
                Set.of(PublicationMutationResult.class.getPermittedSubclasses()));
        assertEquals(
                Set.of(
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Queued.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Started.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Progressed.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Suspended.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Resumed.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Completed.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Failed.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Terminated.class,
                        CommittedRuntimeEvent.DecodedRuntimeEventBody.Cancelled.class),
                Set.of(CommittedRuntimeEvent.DecodedRuntimeEventBody.class.getPermittedSubclasses()));
    }

    /** 验证挂起、终止和 Inbound 枚举与事实源精确一致。 */
    @Test
    void freezesExactEnumMembers() {
        assertEquals(
                List.of("MESSAGE", "ACTION", "CALLBACK", "RECOVERY", "COMMAND", "SCHEDULED"),
                Arrays.stream(InboundKind.values()).map(Enum::name).toList());
        assertEquals(
                List.of("AGENT_HITL", "WORKFLOW_HITL", "WORKFLOW_EXTERNAL", "TOOL_INPUT", "DELEGATED"),
                Arrays.stream(LoopSuspendReason.values()).map(Enum::name).toList());
        assertEquals(
                List.of(
                        "REPEAT_DETECTED",
                        "BUSINESS_MODEL_CALL_BUDGET_EXCEEDED",
                        "WORKFLOW_MAX_STEPS_REACHED",
                        "WORKFLOW_NODE_REVISIT",
                        "INPUT_TOKEN_BUDGET_EXCEEDED",
                        "OUTPUT_TOKEN_BUDGET_EXCEEDED",
                        "TOTAL_TOKEN_BUDGET_EXCEEDED",
                        "TOOL_CALL_BUDGET_EXCEEDED",
                        "KNOWLEDGE_CALL_BUDGET_EXCEEDED",
                        "WALL_TIME_BUDGET_EXCEEDED",
                        "CONTEXT_POLICY_DENIED",
                        "CONTEXT_GUARD_NOT_CONFIGURED",
                        "CONTEXT_SNAPSHOT_PERSIST_FAILED",
                        "KNOWLEDGE_REFERENCE_PERSIST_FAILED"),
                Arrays.stream(LoopTerminationReason.values()).map(Enum::name).toList());
    }

    /** 从 main classes 自动发现顶层 public 类型并与显式 API 清单双向核对。 */
    @Test
    void registersEveryDiscoveredPublicTopLevelType()
            throws IOException, URISyntaxException, ClassNotFoundException {
        Path classesRoot = Path.of(
                AgentId.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path packageDirectory = classesRoot.resolve(API_PACKAGE.replace('.', '/'));
        Set<Class<?>> discovered = new HashSet<>();

        // 递归读取 API 根包与 port 子包且排除 $，确保 nested sealed 成员不冒充顶层类型。
        try (Stream<Path> classFiles = Files.walk(packageDirectory)) {
            for (Path classFile : classFiles
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .toList()) {
                String relativeName = packageDirectory.relativize(classFile).toString();
                String simpleName = relativeName
                        .substring(0, relativeName.length() - ".class".length())
                        .replace('/', '.')
                        .replace('\\', '.');
                Class<?> candidate = Class.forName(
                        API_PACKAGE + "." + simpleName,
                        false,
                        AgentId.class.getClassLoader());
                if (Modifier.isPublic(candidate.getModifiers())) {
                    discovered.add(candidate);
                }
            }
        }

        assertEquals(PUBLIC_TOP_LEVEL_TYPES, discovered);
    }

    /** 递归扫描所有公开签名，只允许 SDK 自身类型与精确登记的 JDK 类型。 */
    @Test
    void publicSignaturesContainOnlyJdkAndSdkTypes() {
        Set<Type> visited = new HashSet<>();

        for (Class<?> apiType : PUBLIC_TOP_LEVEL_TYPES) {
            assertTrue(Modifier.isPublic(apiType.getModifiers()), apiType.getName());
            assertEquals(null, apiType.getEnclosingClass(), apiType.getName());
            inspectPublicType(apiType, visited);
        }
    }

    /** 验证 API 根包只被 port 消费，根包公开签名不得反向引用 port 类型。 */
    @Test
    void rootApiSignaturesDoNotDependOnPortPackage() {
        for (Class<?> apiType : PUBLIC_TOP_LEVEL_TYPES) {
            // 只有根包类型受单向依赖规则约束，port 类型可以引用根包领域事实。
            if (apiType.getPackageName().equals(API_PACKAGE)) {
                assertNoPortDependency(apiType, new HashSet<>());
            }
        }
    }

    /** 验证整个公开 SDK（含递归 nested 类型）不泄露旧持久化模型或 permit 内部细节。 */
    @Test
    void publicSurfaceRejectsForbiddenPersistenceAndPermitNames() {
        for (Class<?> apiType : PUBLIC_TOP_LEVEL_TYPES) {
            assertNoForbiddenPublicNames(apiType, new HashSet<>());
        }

        // 三类探针分别证明类型名、方法名和 record component 名均会触发递归 Gate。
        assertThrows(AssertionError.class,
                () -> assertNoForbiddenPublicNames(ForbiddenTypeProbe.class, new HashSet<>()));
        assertThrows(AssertionError.class,
                () -> assertNoForbiddenPublicNames(ForbiddenMethodProbe.class, new HashSet<>()));
        assertThrows(AssertionError.class,
                () -> assertNoForbiddenPublicNames(ForbiddenComponentProbe.class, new HashSet<>()));
        assertThrows(AssertionError.class,
                () -> inspectPublicType(ForbiddenSignatureProbe.class, new HashSet<>()));
    }

    /** 用测试类路径中的外部 JUnit 类型证明 allowlist 会拒绝未登记依赖。 */
    @Test
    void strictAllowlistRejectsUnregisteredExternalType() {
        assertThrows(AssertionError.class, () -> assertAllowedType(Test.class, new HashSet<>()));
    }

    /** 证明未被方法或 permits 引用的 public nested DTO 仍会被递归签名扫描发现。 */
    @Test
    void rejectsUnreferencedPublicNestedExternalType() {
        assertThrows(AssertionError.class,
                () -> inspectPublicType(NestedApiProbe.class, new HashSet<>()));
    }

    /** 证明根包未被方法或 permits 引用的 public nested DTO 仍会触发反向依赖检查。 */
    @Test
    void rejectsUnreferencedPublicNestedPortDependency() {
        assertThrows(AssertionError.class,
                () -> assertNoPortDependency(RootDependencyProbe.class, new HashSet<>()));
    }

    /** 收集类层次、公开成员、throws 与 annotation 签名，并递归检查嵌套 SDK 类型。 */
    private static void inspectPublicType(Class<?> apiType, Set<Type> visited) {
        assertAllowedType(apiType, visited);
        inspectAnnotations(apiType, visited);
        if (apiType.getGenericSuperclass() != null) {
            assertAllowedType(apiType.getGenericSuperclass(), visited);
        }
        for (Type genericInterface : apiType.getGenericInterfaces()) {
            assertAllowedType(genericInterface, visited);
        }

        for (RecordComponent component : apiType.getRecordComponents() == null
                ? new RecordComponent[0]
                : apiType.getRecordComponents()) {
            assertAllowedType(component.getGenericType(), visited);
            inspectAnnotations(component, visited);
        }
        for (Constructor<?> constructor : apiType.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                inspectAnnotations(constructor, visited);
                for (Type parameterType : constructor.getGenericParameterTypes()) {
                    assertAllowedType(parameterType, visited);
                }
                for (Type exceptionType : constructor.getGenericExceptionTypes()) {
                    assertAllowedType(exceptionType, visited);
                }
                for (Parameter parameter : constructor.getParameters()) {
                    inspectAnnotations(parameter, visited);
                }
            }
        }
        for (Method method : apiType.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                inspectAnnotations(method, visited);
                assertAllowedType(method.getGenericReturnType(), visited);
                for (Type parameterType : method.getGenericParameterTypes()) {
                    assertAllowedType(parameterType, visited);
                }
                for (Type exceptionType : method.getGenericExceptionTypes()) {
                    assertAllowedType(exceptionType, visited);
                }
                for (Parameter parameter : method.getParameters()) {
                    inspectAnnotations(parameter, visited);
                }
            }
        }
        for (Field field : apiType.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                inspectAnnotations(field, visited);
                assertAllowedType(field.getGenericType(), visited);
            }
        }
        for (Class<?> declaredClass : apiType.getDeclaredClasses()) {
            // public nested DTO 即使未出现在方法或 sealed permits 中也属于公开边界。
            if (Modifier.isPublic(declaredClass.getModifiers())) {
                inspectPublicType(declaredClass, visited);
            }
        }
    }

    /** 检查类、record component、构造器、方法、字段和参数的公开 annotation 类型。 */
    private static void inspectAnnotations(AnnotatedElement element, Set<Type> visited) {
        for (Annotation annotation : element.getAnnotations()) {
            assertAllowedType(annotation.annotationType(), visited);
        }
    }

    /** 遍历任意反射 Type 结构并拒绝公共边界禁止类型。 */
    private static void assertAllowedType(Type root, Set<Type> visited) {
        Deque<Type> pending = new ArrayDeque<>();
        pending.add(root);

        while (!pending.isEmpty()) {
            Type type = pending.removeFirst();
            if (!visited.add(type)) {
                continue;
            }

            // Class 类型同时检查精确 allowlist、数组分量和公开 SDK 成员。
            if (type instanceof Class<?> clazz) {
                assertAllowedClass(clazz);
                if (clazz.isArray()) {
                    pending.add(clazz.getComponentType());
                } else if ((clazz.getPackageName().equals(API_PACKAGE)
                                || clazz.getPackageName().equals(API_PACKAGE + ".port"))
                        && Modifier.isPublic(clazz.getModifiers())) {
                    inspectPublicType(clazz, visited);
                    pending.addAll(Arrays.asList(clazz.getPermittedSubclasses() == null
                            ? new Class<?>[0]
                            : clazz.getPermittedSubclasses()));
                }
            } else if (type instanceof ParameterizedType parameterizedType) {
                pending.add(parameterizedType.getRawType());
                pending.addAll(Arrays.asList(parameterizedType.getActualTypeArguments()));
            } else if (type instanceof GenericArrayType genericArrayType) {
                pending.add(genericArrayType.getGenericComponentType());
            } else if (type instanceof WildcardType wildcardType) {
                pending.addAll(Arrays.asList(wildcardType.getUpperBounds()));
                pending.addAll(Arrays.asList(wildcardType.getLowerBounds()));
            } else if (type instanceof TypeVariable<?> typeVariable) {
                pending.addAll(Arrays.asList(typeVariable.getBounds()));
            }
        }
    }

    /** 递归展开一个根包公开类型的字段、方法与泛型，并拒绝任何 port FQN。 */
    private static void assertNoPortDependency(Type root, Set<Type> visited) {
        if (!visited.add(root)) {
            return;
        }

        // Class 同时承担依赖判定和其公开签名的递归入口。
        if (root instanceof Class<?> clazz) {
            assertFalse(
                    clazz.getPackageName().equals(API_PACKAGE + ".port"),
                    () -> "root API must not depend on port type: " + clazz.getName());
            if (clazz.isArray()) {
                assertNoPortDependency(clazz.getComponentType(), visited);
            } else if (clazz.getPackageName().equals(API_PACKAGE)
                    && Modifier.isPublic(clazz.getModifiers())) {
                inspectNoPortAnnotations(clazz, visited);
                assertNoPortDependency(clazz.getGenericSuperclass(), visited);
                for (Type genericInterface : clazz.getGenericInterfaces()) {
                    assertNoPortDependency(genericInterface, visited);
                }
                for (RecordComponent component : clazz.getRecordComponents() == null
                        ? new RecordComponent[0]
                        : clazz.getRecordComponents()) {
                    assertNoPortDependency(component.getGenericType(), visited);
                    inspectNoPortAnnotations(component, visited);
                }
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    if (Modifier.isPublic(constructor.getModifiers())) {
                        inspectNoPortAnnotations(constructor, visited);
                        for (Type parameterType : constructor.getGenericParameterTypes()) {
                            assertNoPortDependency(parameterType, visited);
                        }
                        for (Type exceptionType : constructor.getGenericExceptionTypes()) {
                            assertNoPortDependency(exceptionType, visited);
                        }
                        for (Parameter parameter : constructor.getParameters()) {
                            inspectNoPortAnnotations(parameter, visited);
                        }
                    }
                }
                for (Method method : clazz.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers())) {
                        inspectNoPortAnnotations(method, visited);
                        assertNoPortDependency(method.getGenericReturnType(), visited);
                        for (Type parameterType : method.getGenericParameterTypes()) {
                            assertNoPortDependency(parameterType, visited);
                        }
                        for (Type exceptionType : method.getGenericExceptionTypes()) {
                            assertNoPortDependency(exceptionType, visited);
                        }
                        for (Parameter parameter : method.getParameters()) {
                            inspectNoPortAnnotations(parameter, visited);
                        }
                    }
                }
                for (Field field : clazz.getDeclaredFields()) {
                    if (Modifier.isPublic(field.getModifiers())) {
                        inspectNoPortAnnotations(field, visited);
                        assertNoPortDependency(field.getGenericType(), visited);
                    }
                }
                for (Class<?> permittedSubclass : clazz.getPermittedSubclasses() == null
                        ? new Class<?>[0]
                        : clazz.getPermittedSubclasses()) {
                    assertNoPortDependency(permittedSubclass, visited);
                }
                for (Class<?> declaredClass : clazz.getDeclaredClasses()) {
                    // 未被成员或 permits 引用的 public nested 类型也属于根包公开依赖面。
                    if (Modifier.isPublic(declaredClass.getModifiers())) {
                        assertNoPortDependency(declaredClass, visited);
                    }
                }
            }
        } else if (root instanceof ParameterizedType parameterizedType) {
            assertNoPortDependency(parameterizedType.getRawType(), visited);
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                assertNoPortDependency(argument, visited);
            }
        } else if (root instanceof GenericArrayType genericArrayType) {
            assertNoPortDependency(genericArrayType.getGenericComponentType(), visited);
        } else if (root instanceof WildcardType wildcardType) {
            for (Type upperBound : wildcardType.getUpperBounds()) {
                assertNoPortDependency(upperBound, visited);
            }
            for (Type lowerBound : wildcardType.getLowerBounds()) {
                assertNoPortDependency(lowerBound, visited);
            }
        } else if (root instanceof TypeVariable<?> typeVariable) {
            for (Type bound : typeVariable.getBounds()) {
                assertNoPortDependency(bound, visited);
            }
        }
    }

    /** 检查根包公开元素及参数 annotation 是否意外引用 port 类型。 */
    private static void inspectNoPortAnnotations(AnnotatedElement element, Set<Type> visited) {
        for (Annotation annotation : element.getAnnotations()) {
            assertNoPortDependency(annotation.annotationType(), visited);
        }
    }

    /** 只接受 primitive、SDK 包或明确登记的 JDK 类型，未登记外部类型一律失败。 */
    private static void assertAllowedClass(Class<?> type) {
        assertNoForbiddenTypeName(type);
        assertTrue(
                type.isPrimitive()
                        || type.getPackageName().equals(API_PACKAGE)
                        || type.getPackageName().equals(API_PACKAGE + ".port")
                        || type.getPackageName().equals(API_PACKAGE + ".dag")
                        || type.getPackageName().equals(API_PACKAGE + ".media")
                        || ALLOWED_JDK_TYPES.contains(type),
                () -> "public API type is not allowlisted: " + type.getName());
    }

    /**
     * 递归检查公开类型、方法与 record component 名称，阻止 SDK 重新暴露基础设施或旧 permit ABI。
     */
    private static void assertNoForbiddenPublicNames(Class<?> apiType, Set<Class<?>> visited) {
        if (!visited.add(apiType) || !Modifier.isPublic(apiType.getModifiers())) {
            return;
        }

        assertNoForbiddenTypeName(apiType);

        for (Method method : apiType.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertFalse(
                        method.getName().equals("tryConsume")
                                || method.getName().equals("forkSession"),
                        () -> "public API exposes forbidden method name: "
                                + apiType.getName() + "#" + method.getName());
            }
        }
        for (RecordComponent component : apiType.getRecordComponents() == null
                ? new RecordComponent[0]
                : apiType.getRecordComponents()) {
            assertFalse(
                    Set.of("permitId", "permitRef", "capabilityHandle")
                            .contains(component.getName()),
                    () -> "public API exposes forbidden record component: "
                            + apiType.getName() + "#" + component.getName());
        }
        for (Class<?> declaredClass : apiType.getDeclaredClasses()) {
            assertNoForbiddenPublicNames(declaredClass, visited);
        }
    }

    /** 检查任意签名类型的名称，不因类型本身为 package-private 而放过泄露。 */
    private static void assertNoForbiddenTypeName(Class<?> type) {
        String simpleName = type.getSimpleName();
        assertFalse(
                simpleName.startsWith("ExternalInvocation")
                        || simpleName.equals("KnownOutcome")
                        || simpleName.endsWith("DO")
                        || simpleName.endsWith("Mapper")
                        || simpleName.endsWith("Repository")
                        || simpleName.equals("BattleReport"),
                () -> "public API exposes forbidden signature type: " + type.getName());
    }

    /** 提供一个不经方法或 sealed permits 暴露 nested DTO 的测试专用外层类型。 */
    public static final class NestedApiProbe {

        /** 禁止实例化只承担反射探针职责的外层类型。 */
        private NestedApiProbe() {
        }

        /** 通过未登记 JUnit component 证明 nested DTO 不会逃过公开签名扫描。 */
        public record UnreferencedNestedDto(Test forbiddenType) {
        }
    }

    /** 提供一个不经方法或 sealed permits 暴露 port DTO 的根包反向依赖探针。 */
    public static final class RootDependencyProbe {

        /** 禁止实例化只承担反向依赖探针职责的外层类型。 */
        private RootDependencyProbe() {
        }

        /** 通过 port component 证明未被引用的 nested DTO 仍进入根包依赖扫描。 */
        public record UnreferencedPortDto(ActionRef forbiddenPortType) {
        }
    }

    /** 用于证明公开 nested 类型名 denylist 会被递归应用。 */
    public static final class ForbiddenTypeProbe {

        /** 禁止实例化只承担反射探针职责的外层类型。 */
        private ForbiddenTypeProbe() {
        }

        /** 模拟旧 ExternalInvocation DTO 泄露。 */
        public record ExternalInvocationProbe(String value) {
        }
    }

    /** 用于证明公开 nested 方法名 denylist 会被递归应用。 */
    public static final class ForbiddenMethodProbe {

        /** 模拟旧的非原子 permit 消费入口。 */
        public void tryConsume() {
        }
    }

    /** 用于证明公开 nested record component denylist 会被递归应用。 */
    public record ForbiddenComponentProbe(String permitId) {
    }

    /** 用公开方法引用 package-private 禁止类型，证明 Type 图扫描不会只检查 public 声明。 */
    public static final class ForbiddenSignatureProbe {

        /** 返回禁止的旧外调类型，专用于验证签名图 Gate。 */
        public ExternalInvocationSignatureLeak leakedResult() {
            return null;
        }
    }
}

/** 模拟未公开但被公开签名引用的旧 ExternalInvocation 类型。 */
final class ExternalInvocationSignatureLeak {
}
