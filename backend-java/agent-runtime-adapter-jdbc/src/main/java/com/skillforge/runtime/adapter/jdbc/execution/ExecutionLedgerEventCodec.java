package com.skillforge.runtime.adapter.jdbc.execution;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.DelegationRef;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.CreateExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLease;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionWait;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ProgressExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.RequestExecutionCancel;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ResumeExecution;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.TransitionExecution;
import com.skillforge.runtime.api.port.LedgerEvent;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.StateEventPublication;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Execution state/cancel 注册 schema 的唯一 JDBC 侧验证与规范编码。 */
final class ExecutionLedgerEventCodec {

    static final String STATE_SCHEMA = "skillforge.runtime.execution-state.v1";
    static final String MUTATION_SCHEMA =
            "skillforge.runtime.execution-ledger-mutation.v1";
    static final String CANCEL_SCHEMA =
            "skillforge.runtime.execution-cancel-requested.v1";
    static final String CANCEL_MUTATION_SCHEMA =
            "skillforge.runtime.execution-ledger-cancel.v1";

    private ExecutionLedgerEventCodec() {}

    /** 生产命令工厂共享验证器的唯一规范编码；不会向调用者暴露 schema 拼装。 */
    static LedgerEvent eventFor(Object command, ExecutionRecord output, EventId eventId, Instant at) {
        String type;
        String body;
        if (command instanceof CreateExecution value) {
            type = "execution.queued";
            body = privatePayload("CREATE", createCommand(value));
        } else if (command instanceof ProgressExecution value) {
            type = "execution.step";
            body = privatePayload("PROGRESS", progressCommand(value));
        } else if (command instanceof TransitionExecution value) {
            type = value.event().sessionEvent().eventType();
            body = privatePayload("TRANSITION", transitionCommand(value));
        } else if (command instanceof ResumeExecution value) {
            type = output.state() == com.skillforge.runtime.api.port.WorkflowExecutionState.RESUMING
                    ? "execution.resumed" : "execution.started";
            body = privatePayload("RESUME", resumeCommand(value));
        } else if (command instanceof RequestExecutionCancel value) {
            return event(eventId, at, output.owner(), "execution.cancel_requested", CANCEL_SCHEMA,
                    cancelPayload(value, output), CANCEL_MUTATION_SCHEMA,
                    privatePayload("CANCEL", cancelCommand(value)));
        } else {
            throw new IllegalArgumentException("unsupported_execution_command");
        }
        return event(eventId, at, output.owner(), type, STATE_SCHEMA, statePayload(output), MUTATION_SCHEMA, body);
    }

    private static LedgerEvent event(EventId id, Instant at, RunIdentity owner, String type,
            String publicSchema, String publicBody, String privateSchema, String privateBody) {
        return new LedgerEvent(new com.skillforge.runtime.api.port.SessionEventDraft(id, type, "1",
                sha256(publicSchema + "\n" + publicBody), owner.trace(),
                new TypedPayload(publicSchema, publicBody), at), type,
                sha256(privateSchema + "\n" + privateBody), new TypedPayload(privateSchema, privateBody));
    }

    static boolean validCreate(CreateExecution command, ExecutionRecord output) {
        return validStateEvent(
                command.queuedEvent(), output, "execution.queued",
                privatePayload("CREATE", createCommand(command)));
    }

    static boolean validProgress(
            ProgressExecution command, ExecutionRecord output) {
        return validStateEvent(
                command.stepEvent(), output, "execution.step",
                privatePayload("PROGRESS", progressCommand(command)));
    }

    static boolean validTransition(
            TransitionExecution command, ExecutionRecord output) {
        return validStateEvent(
                command.event(), output, command.event().sessionEvent().eventType(),
                privatePayload("TRANSITION", transitionCommand(command)));
    }

    static boolean validResume(
            ResumeExecution command,
            ExecutionRecord intermediate,
            ExecutionRecord output) {
        String privateBody = privatePayload("RESUME", resumeCommand(command));
        return validStateEvent(
                        command.resumedEvent(), intermediate,
                        "execution.resumed", privateBody)
                && validStateEvent(
                        command.startedEvent(), output,
                        "execution.started", privateBody);
    }

    static boolean validCancel(
            RequestExecutionCancel command, ExecutionRecord output) {
        return valid(
                command.cancelEvent(), output.owner(), "execution.cancel_requested",
                CANCEL_SCHEMA, cancelPayload(command, output), CANCEL_MUTATION_SCHEMA,
                privatePayload("CANCEL", cancelCommand(command)));
    }

    private static String cancelPayload(RequestExecutionCancel command, ExecutionRecord output) {
        return json(object(
                "cancelRequestId", command.cancelRequestId().value(),
                "executionId", command.executionRef().executionId(),
                "revision", Long.toString(output.executionRevision()),
                "state", output.state().name()));
    }

    /** publication 必须精确来自同 revision 的 immutable mutation 历史事实。 */
    static boolean validStored(
            StateEventPublication publication,
            ExecutionRecord historicalRecord,
            LedgerEvent frozenEvent) {
        LedgerEvent event = publication.event();
        return publication.sourceRevision() == historicalRecord.executionRevision()
                && event.equals(frozenEvent)
                && publication.committedFactRef().equals(
                        committedFactRef(historicalRecord, frozenEvent))
                && validEnvelope(event, historicalRecord.owner());
    }

    static ResultRef committedFactRef(
            ExecutionRecord execution, LedgerEvent event) {
        return new ResultRef("execution-fact:sha256:" + sha256Parts(
                "execution", execution.executionRef().tenantId().value(),
                execution.executionRef().executionId(),
                Long.toString(execution.executionRevision()),
                event.sessionEvent().eventId().value(), event.ledgerFingerprint()));
    }

    /** 对数据库重建出的 envelope 做自洽校验，避免 codec 正确但事件指纹已损坏。 */
    private static boolean validEnvelope(LedgerEvent event, RunIdentity owner) {
        String eventType = event.sessionEvent().eventType();
        String publicSchema = eventType.equals("execution.cancel_requested")
                ? CANCEL_SCHEMA : STATE_SCHEMA;
        String privateSchema = eventType.equals("execution.cancel_requested")
                ? CANCEL_MUTATION_SCHEMA : MUTATION_SCHEMA;
        return isRegistered(eventType)
                && event.sessionEvent().eventVersion().equals("1")
                && event.sessionEvent().trace().equals(owner.trace())
                && event.sessionEvent().payload().schemaId().equals(publicSchema)
                && event.sessionEvent().eventFingerprint().equals(sha256(
                        publicSchema + "\n"
                                + event.sessionEvent().payload().canonicalJson()))
                && event.ledgerEventType().equals(eventType)
                && event.privatePayload().schemaId().equals(privateSchema)
                && event.ledgerFingerprint().equals(sha256(
                        privateSchema + "\n"
                                + event.privatePayload().canonicalJson()));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private static String sha256Parts(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                if (value == null) {
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
                } else {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES)
                            .putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private static boolean validStateEvent(
            LedgerEvent event,
            ExecutionRecord output,
            String eventType,
            String privateBody) {
        return valid(
                event, output.owner(), eventType,
                STATE_SCHEMA, statePayload(output),
                MUTATION_SCHEMA, privateBody);
    }

    private static boolean valid(
            LedgerEvent event,
            RunIdentity owner,
            String eventType,
            String publicSchema,
            String publicBody,
            String privateSchema,
            String privateBody) {
        TypedPayload publicPayload = new TypedPayload(publicSchema, publicBody);
        TypedPayload privatePayload = new TypedPayload(privateSchema, privateBody);
        return event.sessionEvent().eventType().equals(eventType)
                && event.sessionEvent().eventVersion().equals("1")
                && event.sessionEvent().trace().equals(owner.trace())
                && event.sessionEvent().payload().equals(publicPayload)
                && event.sessionEvent().eventFingerprint().equals(
                        sha256(publicSchema + "\n" + publicBody))
                && event.ledgerEventType().equals(eventType)
                && event.privatePayload().equals(privatePayload)
                && event.ledgerFingerprint().equals(
                        sha256(privateSchema + "\n" + privateBody));
    }

    private static boolean isRegistered(String eventType) {
        return switch (eventType) {
            case "execution.queued", "execution.started", "execution.step",
                    "execution.hitl", "execution.waiting", "execution.resumed", "execution.completed",
                    "execution.failed", "execution.loop_terminated",
                    "execution.cancelled", "execution.cancel_requested" -> true;
            default -> false;
        };
    }

    private static String statePayload(ExecutionRecord record) {
        return json(object(
                "cancelRequestId", record.cancelRequestId()
                        .map(value -> value.value()).orElse(null),
                "executionId", record.executionRef().executionId(),
                "reasonCode", record.reasonCode().orElse(null),
                "resultRef", record.resultRef().map(value -> value.value()).orElse(null),
                "revision", Long.toString(record.executionRevision()),
                "state", record.state().name(),
                "waiting", record.waiting()
                        .map(ExecutionLedgerEventCodec::waiting).orElse(null)));
    }

    private static String privatePayload(String commandType, Object command) {
        return json(object("command", command, "commandType", commandType));
    }

    private static Object createCommand(CreateExecution command) {
        return object(
                "causativeActionRef", command.causativeActionRef()
                        .map(ExecutionLedgerEventCodec::actionRef).orElse(null),
                "definitionVersion", command.definitionVersion(),
                "delegationRef", command.delegationRef()
                        .map(ExecutionLedgerEventCodec::delegationRef).orElse(null),
                "executionRef", executionRef(command.executionRef()),
                "executionUnitId", command.executionUnitId(),
                "initialCheckpoint", payload(command.initialCheckpoint()),
                "mode", command.mode().name(),
                "owner", owner(command.owner()),
                "requestFingerprint", command.requestFingerprint());
    }

    private static Object progressCommand(ProgressExecution command) {
        return object(
                "checkpoint", payload(command.checkpoint()),
                "expectedRevision", Long.toString(command.expectedRevision()),
                "lease", lease(command.lease()));
    }

    private static Object transitionCommand(TransitionExecution command) {
        return object(
                "checkpoint", payload(command.checkpoint()),
                "expectedRevision", Long.toString(command.expectedRevision()),
                "expectedState", command.expectedState().name(),
                "lease", lease(command.lease()),
                "reasonCode", command.reasonCode().orElse(null),
                "resultRef", command.resultRef().map(value -> value.value()).orElse(null),
                "targetState", command.targetState().name(),
                "waiting", command.waiting()
                        .map(ExecutionLedgerEventCodec::waiting).orElse(null));
    }

    private static Object resumeCommand(ResumeExecution command) {
        return object(
                "checkpoint", payload(command.checkpoint()),
                "expectedActionKey", command.expectedActionKey(),
                "expectedRevision", Long.toString(command.expectedRevision()),
                "expectedSuspendRef", command.expectedSuspendRef(),
                "expectedState", command.expectedState().name(),
                "lease", lease(command.lease()));
    }

    private static Object cancelCommand(RequestExecutionCancel command) {
        return object(
                "cancelRequestId", command.cancelRequestId().value(),
                "executionRef", executionRef(command.executionRef()),
                "expectedRevision", Long.toString(command.expectedRevision()),
                "identity", identity(command.identity()));
    }

    private static Object identity(VerifiedIdentity identity) {
        List<String> scopes = new ArrayList<>(identity.scopes());
        scopes.sort(ExecutionLedgerEventCodec::compareUtf8);
        return object(
                "owner", sessionOwner(identity.owner()),
                "policyRevision", Long.toString(identity.policyRevision()),
                "principal", principal(identity.principal()),
                "scopes", scopes,
                "verificationRef", identity.verificationRef().value());
    }

    private static Object principal(PrincipalRef principal) {
        return switch (principal) {
            case PrincipalRef.EndUserPrincipal value -> object(
                    "endUserId", Long.toString(value.endUserId().value()),
                    "kind", "END_USER",
                    "tenantId", value.tenantId().value());
            case PrincipalRef.ServicePrincipal value -> object(
                    "credentialVersion", Long.toString(value.credentialVersion()),
                    "integrationId", value.integrationId(),
                    "kind", "SERVICE",
                    "principalId", value.principalId(),
                    "tenantId", value.tenantId().value());
            case PrincipalRef.SystemPrincipal value -> object(
                    "kind", "SYSTEM",
                    "principalId", value.principalId(),
                    "tenantId", value.tenantId().value());
        };
    }

    private static Object lease(ExecutionLease lease) {
        return object(
                "executionRef", executionRef(lease.executionRef()),
                "executionRevision", Long.toString(lease.executionRevision()),
                "fence", object(
                        "claimId", lease.fence().claimId(),
                        "fencingToken", Long.toString(lease.fence().fencingToken()),
                        "leaseUntil", instant(lease.fence().leaseUntil()),
                        "tenantId", lease.fence().tenantId().value(),
                        "workerId", lease.fence().workerId()));
    }

    private static Object owner(RunIdentity owner) {
        return object(
                "causationIdentityRef", owner.causationIdentityRef().value(),
                "causationInputId", owner.causationInputId().value(),
                "agentId", owner.agentId().value(),
                "requester", owner.requester() instanceof com.skillforge.runtime.api.RunRequester.EndUser user
                        ? object("kind","END_USER","endUserId",Long.toString(user.endUserId().value()))
                        : object("kind","SYSTEM","principal",principal(((com.skillforge.runtime.api.RunRequester.System)owner.requester()).principal())),
                "sourceMessageRef", owner.sourceMessageRef().map(source->object("sessionId",source.sessionRef().sessionId(),
                        "tenantId",source.sessionRef().tenantId().value(),"messageId",source.messageId())).orElse(null),
                "originRef", object("tenantId",owner.originRef().tenantId().value(),"originId",owner.originRef().originId()),
                "owner", sessionOwner(owner.owner()),
                "runRef", object(
                        "runId", owner.runRef().runId(),
                        "tenantId", owner.runRef().tenantId().value()),
                "trace", object(
                        "causedByEventId", owner.trace().causedByEventId()
                                .map(EventId::value).orElse(null),
                        "rootInputId", owner.trace().rootInputId().value(),
                        "spanId", owner.trace().spanId(),
                        "traceId", owner.trace().traceId()));
    }

    private static Object sessionOwner(
            com.skillforge.runtime.api.port.SessionOwner owner) {
        return object(
                "targetKind", owner.target().kind().name(),
                "targetId", owner.target().id(),
                "subject", owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User user
                        ? object("kind","USER","endUserId",Long.toString(user.endUserId().value()))
                        : object("kind","GROUP","groupBindingId",((com.skillforge.runtime.api.SessionSubject.Group)owner.subject()).groupBindingRef().groupBindingId()),
                "sessionId", owner.sessionRef().sessionId(),
                "tenantId", owner.sessionRef().tenantId().value());
    }

    private static Object payload(TypedPayload payload) {
        return object(
                "canonicalJson", payload.canonicalJson(),
                "schemaId", payload.schemaId());
    }

    private static Object executionRef(ExecutionRef ref) {
        return object("executionId", ref.executionId(),
                "tenantId", ref.tenantId().value());
    }

    private static Object actionRef(ActionRef ref) {
        return object("actionId", ref.actionId(),
                "tenantId", ref.tenantId().value());
    }

    private static Object delegationRef(DelegationRef ref) {
        return object("delegationId", ref.delegationId(),
                "tenantId", ref.tenantId().value());
    }

    private static Object waiting(ExecutionWait waiting) {
        return object("actionKey", waiting.actionKey(),
                "suspendRef", waiting.suspendRef());
    }

    private static String instant(Instant instant) {
        return instant.toString();
    }

    private static int compareUtf8(String left, String right) {
        return java.util.Arrays.compareUnsigned(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static String json(Object value) {
        StringBuilder output = new StringBuilder();
        writeJson(value, output);
        return output.toString();
    }

    private static void writeJson(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeString(text, output);
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                writeJson(list.get(index), output);
            }
            output.append(']');
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                writeString((String) entry.getKey(), output);
                output.append(':');
                writeJson(entry.getValue(), output);
            }
            output.append('}');
        } else {
            throw new IllegalArgumentException("invalid_execution_ledger_json");
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index == value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    throw new IllegalArgumentException("invalid_execution_ledger_json");
                }
                output.append(character).append(value.charAt(index));
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("invalid_execution_ledger_json");
            } else if (character == '"' || character == '\\') {
                output.append('\\').append(character);
            } else {
                int escaped = "\b\t\n\f\r".indexOf(character);
                if (escaped >= 0) {
                    output.append('\\').append("btnfr".charAt(escaped));
                } else if (character < 32) {
                    output.append(String.format(
                            java.util.Locale.ROOT, "\\u%04x", (int) character));
                } else {
                    output.append(character);
                }
            }
        }
        output.append('"');
    }
}
