package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionSteering;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** 可信产品/团队入口向准确原Run补充原文；实际外调与模型消费仍由执行主链拥有。 */
public final class AgentRuntimeExecutionSteering {
    private final ExecutionStore store;
    private final AgentRuntimeExecutionWorker worker;
    private final AgentExecutionSteering codec;
    private final AgentExecutionCodec modelCodec;

    public AgentRuntimeExecutionSteering(ExecutionStore store, AgentRuntimeExecutionWorker worker, CodecLimits limits) {
        this.store = Objects.requireNonNull(store); this.worker = Objects.requireNonNull(worker);
        codec = new AgentExecutionSteering(limits); modelCodec = new AgentExecutionCodec(limits);
    }

    /** Store同事务核当前IAM、原owner和稳定输入；接受不改在途Attempt、lease或原因果身份。 */
    public ExecutionStore.InputOffer offer(ExecutionStore.Run run, VerifiedIdentity actor, InputId input, String text) {
        if (!actor.owner().equals(run.identity().owner())) throw new SecurityException("execution_supplement_owner_mismatch");
        var content = codec.encode(input, text);
        String fingerprint = digest(content.schemaId() + "\0" + content.canonicalJson());
        var material = new ExecutionStore.Material(new ResultRef("execution-supplement:" + digest(run.identity().runRef() + "\0" + input.value())), content);
        var offered = store.offerInput(new ExecutionStore.OfferInput(actor, run.identity().runRef(), input, material, fingerprint));
        worker.wake(run.identity().runRef()); return offered;
    }

    /** 控制查询按精确实际请求/已采纳Attempt判定状态，不能将本地context采纳等同模型消费。 */
    public Snapshot snapshot(ExecutionStore.Run run) {
        var items = new LinkedHashMap<String, Item>(); var messages = new LinkedHashMap<String, com.skillforge.runtime.api.port.ModelPort.ModelMessage>();
        var ordinals = new LinkedHashMap<String, Long>();
        long after = 0;
        while (true) {
            var page = store.inputs(run.identity().runRef(), after, false, 100);
            for (var input : page) {
                if (!input.run().equals(run.identity().runRef()) || input.ordinal() <= after) throw invalid();
                var material = store.material(run.identity().runRef().tenantId(), input.content()).orElseThrow(AgentRuntimeExecutionSteering::invalid);
                var parsed = codec.decode(material.content()); if (!parsed.input().equals(input.input())) throw invalid();
                String id = AgentExecutionSteering.messageId(input.input()); messages.put(id, codec.message(input.input(), material.content()));
                ordinals.put(id,input.ordinal());
                items.put(id, new Item(input.input().value(), parsed.text(), Status.PENDING)); after = input.ordinal();
            }
            if (page.size() < 100) break;
        }
        if (!items.isEmpty()) {
            after = 0;
            while (true) {
                var page = store.actions(run.identity().runRef(), after, 100);
                for (var action : page) {
                    if (!action.run().equals(run.identity().runRef()) || action.ordinal() <= after) throw invalid();
                    after = action.ordinal(); if (action.kind() != ExecutionStore.Kind.MODEL) continue;
                    var material = store.material(run.identity().runRef().tenantId(), action.request()).orElseThrow(AgentRuntimeExecutionSteering::invalid);
                    var prepared = modelCodec.decodeModelRequest(material.content(), refs -> store.materials(run.identity().runRef().tenantId(), refs)); var request = prepared.request();
                    boolean consumed = action.selectedAttempt().map(id -> {
                        var attempt = store.attempt(run.identity().runRef(), id).orElseThrow(AgentRuntimeExecutionSteering::invalid);
                        if (!attempt.action().equals(action.reference()) || !attempt.run().equals(run.identity().runRef())) throw invalid();
                        return attempt.adopted() && attempt.resultKind().isPresent() && attempt.resultKind().orElseThrow() != ExecutionStore.ResultKind.UNKNOWN;
                    }).orElse(false);
                    for (var message : request.messages()) {
                        var item = items.get(message.messageId()); if (item == null) continue;
                        if (!message.equals(messages.get(message.messageId())) || prepared.supplementalIncludedThrough()<ordinals.get(message.messageId())) throw invalid();
                        var status = consumed ? Status.CONSUMED : Status.INCLUDED;
                        if (status.ordinal() > item.status().ordinal()) items.put(message.messageId(), new Item(item.commandId(), item.text(), status));
                    }
                }
                if (page.size() < 100) break;
            }
        }
        return new Snapshot(run.identity().runRef().runId(), accepting(run), new ArrayList<>(items.values()));
    }

    public static boolean accepting(ExecutionStore.Run run) {
        return !run.cancelled() && switch (run.status()) {
            case QUEUED, RUNNING, SUSPENDED, RESUMING -> true;
            default -> false;
        };
    }

    public enum Status { PENDING, INCLUDED, CONSUMED }
    public record Item(String commandId, String text, Status status) {
        public Item { Objects.requireNonNull(commandId); Objects.requireNonNull(text); Objects.requireNonNull(status); }
        @Override public String toString() { return "ExecutionSteeringItem[status=" + status + "]"; }
    }
    public record Snapshot(String runId, boolean accepting, List<Item> items) {
        public Snapshot { items = List.copyOf(items); }
        @Override public String toString() { return "ExecutionSteeringSnapshot[accepting=" + accepting + ",count=" + items.size() + "]"; }
    }

    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("sha256_unavailable", failure); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("execution_supplement_fact_mismatch"); }
}
