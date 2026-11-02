package com.skillforge.runtime.session;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.session.ExecutionCoordinator.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

/** 有界原生tool_calls组：只发送新许可，准确兄弟与复用结果保留到完整回合采纳。 */
final class ExecutionBatchDriver<M> {
    private final ExecutionStore store;
    private final Program<M> program;
    private final Capabilities capabilities;

    ExecutionBatchDriver(ExecutionStore store, Program<M> program, Capabilities capabilities) {
        this.store = Objects.requireNonNull(store); this.program = Objects.requireNonNull(program); this.capabilities = Objects.requireNonNull(capabilities);
    }

    /** 原MODEL组的完整manifest一次登记；只有Fresh成员取得新发送许可。 */
    Result<M> dispatch(Run run, M materials, Material context, Step.Batch batch, Control control) {
        var source = store.action(batch.sourceModelAction()).orElseThrow(() -> invalid("execution_batch_source_missing"));
        if (source.kind() != Kind.MODEL || !source.run().equals(run.identity().runRef()) || source.selectedAttempt().isEmpty()) throw invalid("execution_batch_source_invalid");
        if (control.cancelled()) return signal(run, materials, context, Stop.CANCELLED);
        if (control.ownershipLost()) return signal(run, materials, context, Stop.OWNERSHIP_LOST);
        var command = new BatchDispatch(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), source.selectedAttempt().orElseThrow(), batch.members(), batch.actor());
        var dispatched = store.dispatchBatch(command);
        if (dispatched instanceof BatchDispatchResult.Rejected rejected) return new Result<>(run, materials, context, false, Stop.REJECTED, rejected.reason());
        if (dispatched instanceof BatchDispatchResult.Existing existing) return recoverLoaded(existing.run(), materials, context, existing.entries(), control);
        var registered = (BatchDispatchResult.Registered) dispatched; var current = registered.run(); requireRun(run, current);
        var reference = current.currentBatch().orElseThrow(() -> invalid("execution_batch_reference_missing"));
        if (!reference.sourceAttempt().equals(command.sourceAttempt()) || reference.size() != batch.members().size()) throw invalid("execution_batch_receipt_mismatch");
        var entries = ordered(reference, registered.entries()); var outcomes = new BatchOutcome[reference.size()];
        for (var entry : entries) {
            var member = batch.members().get(entry.slot().slot() - 1);
            if (member instanceof BatchMember.Reused reuse) {
                requireReuse(entry, reuse); outcomes[reuse.slot() - 1] = new BatchOutcome.Reused(reuse.slot(), reuse);
            } else if (entry.reused()) throw invalid("execution_batch_receipt_mismatch");
        }
        if (quiet(current) || !retainsLease(run, current)) return stopped(current, materials, context);
        return round(current, materials, context, reference, entries, outcomes, registered.permits(), batch.calls(), control);
    }

    /** 接管只采用原Attempt原材料；缺失或UNKNOWN静止且从不重新发送。 */
    Result<M> recover(Run run, M materials, Material context, Control control) {
        if (quiet(run)) return stopped(run, materials, context);
        var reference = run.currentBatch().orElseThrow(() -> invalid("execution_batch_reference_missing"));
        return recoverLoaded(run, materials, context, store.batch(reference), control);
    }

    private Result<M> recoverLoaded(Run run, M materials, Material context, List<BatchEntry> saved, Control control) {
        if (run.currentBatch().isEmpty()) return stopped(run, materials, context);
        var reference = run.currentBatch().orElseThrow(() -> invalid("execution_batch_reference_missing"));
        if (quiet(run)) return stopped(run, materials, context);
        var entries = ordered(reference, saved);
        if (!settled(entries) || hasWaiting(entries) && run.phase() == Phase.AWAITING_RESULT) {
            var state = store.quiesceBatch(new BatchQuiesce(reference, run.revision(), run.lease().orElseThrow(), List.of()));
            requireRun(run, state.run()); run = state.run(); entries = ordered(reference, state.entries());
            if (!settled(entries) || quiet(run)) return stopped(run, materials, context);
        }
        var outcomes = new BatchOutcome[reference.size()];
        for (var entry : entries) {
            int slot = entry.slot().slot(); var attempt = entry.attempt();
            if (entry.reused()) {
                var source = new BatchMember.Reused(slot, entry.action().reference(), attempt.id(), attempt.request(), attempt.result().orElseThrow(), attempt.feedback().orElseThrow());
                outcomes[slot - 1] = new BatchOutcome.Reused(slot, source);
            } else {
                var request = material(run, attempt.request()); var action = entry.action();
                var prepared = new Prepared(action.reference(), action.ordinal(), action.kind(), action.target(), action.capability(), request,
                        AgentExecutionCodec.digest(request.content().schemaId() + "\0" + request.content().canonicalJson()));
                outcomes[slot - 1] = new BatchOutcome.Actual(slot, new BatchCall(prepared, attempt.id(), new CapabilityResult(attempt.resultKind().orElseThrow(), material(run, attempt.result().orElseThrow()),
                        attempt.feedback().map(value -> material(reference.run().tenantId(), value)), attempt.externalOperation(), ExecutionBudgetPort.Usage.unreported())));
            }
        }
        return advance(run, materials, context, reference, entries, outcomes, control);
    }

    /** 回调各自保存；首个等待不改原组lease/revision，故兄弟发送仍使用原有效许可。 */
    private Result<M> round(Run current, M materials, Material context, BatchRef reference, List<BatchEntry> entries, BatchOutcome[] outcomes,
            List<DispatchResult.Registered> permits, List<Step.Dispatch> commands, Control control) {
        var sends = sends(current, reference, entries, permits, commands);
        for (var send : sends) outcomes[send.slot() - 1] = null;
        Run last = current; Adoption<M> adopted = null; RuntimeException failure = null; boolean interrupted = false;
        // 外层Run槽有界；每组至多READ_ONLY_BATCH个平台线程，没有新增全局通用队列或虚拟线程。
        try (var executor = Executors.newFixedThreadPool(program.batchParallel(materials) ? sends.size() : 1, Thread.ofPlatform().daemon(true).name("execution-tool-batch-", 0).factory())) {
            var completed = new ExecutorCompletionService<Finished>(executor);
            for (var send : sends) completed.submit(() -> {
                if (control.cancelled() || control.ownershipLost()) return new Finished(send.slot(), null, null);
                try { return new Finished(send.slot(), new BatchCall(send.command().action(), send.receipt().attempt().id(), Objects.requireNonNull(capabilities.invoke(send.receipt(), send.command().action()))), null); }
                catch (RuntimeException rejected) { return new Finished(send.slot(), null, rejected); }
            });
            for (int count = 0; count < sends.size(); count++) {
                Finished finished;
                while (true) {
                    try { finished = completed.take().get(); break; }
                    catch (InterruptedException stopped) { interrupted = true; }
                    catch (java.util.concurrent.ExecutionException rejected) { finished = new Finished(-1, null, new IllegalStateException("execution_batch_task_failed", rejected.getCause())); break; }
                }
                if (finished.failure() != null && failure == null) failure = finished.failure();
                if (finished.actual() == null) continue;
                outcomes[finished.slot() - 1] = new BatchOutcome.Actual(finished.slot(), finished.actual());
                try {
                    var inspection = program.inspectBatch(current, materials, finished.actual(), finished.slot());
                    boolean complete = count == sends.size() - 1 && failure == null && accurate(outcomes) && !quiet(last) && retainsLease(current, last)
                            && !control.cancelled() && !control.ownershipLost();
                    var adoption = complete ? program.adoptBatch(current, materials, List.of(outcomes)) : null;
                    var actual = finished.actual().result();
                    var result = new Completion(current.identity().runRef(), finished.actual().attemptId(), actual.kind(), actual.result(), actual.feedback(), actual.externalOperation(), actual.usage(),
                            current.revision(), current.lease().orElseThrow(), adoption == null ? current.progress() : adoption.next(), adoption == null ? context : adoption.context(), inspection.events(), inspection.waiting());
                    var committed = store.completeBatch(new BatchCompletion(reference, result, adoption == null ? Optional.empty() : Optional.of(adoption(reference, List.of(outcomes), adoption))));
                    requireRun(current, committed.run()); last = committed.run();
                    if (committed.advanced()) {
                        if (adoption == null || !last.progress().equals(adoption.next()) || last.currentBatch().isPresent() || !retainsLease(current, last)) throw invalid("execution_batch_committed_progress_mismatch");
                        adopted = adoption;
                    }
                } catch (RuntimeException rejected) { if (failure == null) failure = rejected; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (failure != null) {
            if (!quiet(last) && retainsLease(current, last)) store.quiesceBatch(new BatchQuiesce(reference, last.revision(), last.lease().orElseThrow(), List.of()));
            throw failure;
        }
        if (adopted != null && !quiet(last)) return new Result<>(last, adopted.materials(), adopted.context(), true, Stop.YIELDED, "execution_batch_advanced");
        if (quiet(last) || !retainsLease(current, last)) return stopped(last, materials, context);
        var state = store.quiesceBatch(new BatchQuiesce(reference, last.revision(), last.lease().orElseThrow(), List.of()));
        requireRun(current, state.run()); last = state.run();
        if (quiet(last) || Arrays.stream(outcomes).anyMatch(Objects::isNull)) return stopped(last, materials, context);
        return advance(last, materials, context, reference, ordered(reference, state.entries()), outcomes, control);
    }

    private Result<M> advance(Run run, M materials, Material context, BatchRef reference, List<BatchEntry> entries, BatchOutcome[] outcomes, Control control) {
        if (quiet(run)) return stopped(run, materials, context);
        if (control.cancelled()) return signal(run, materials, context, Stop.CANCELLED);
        if (control.ownershipLost()) return signal(run, materials, context, Stop.OWNERSHIP_LOST);
        var results = List.of(outcomes);
        if (accurate(outcomes)) {
            var adopted = program.adoptBatch(run, materials, results);
            var committed = store.adoptBatch(new BatchAdopt(run.identity().runRef(), reference, run.revision(), run.lease().orElseThrow(), adoption(reference, results, adopted)));
            requireRun(run, committed.run());
            if (committed.advanced() && (!committed.run().progress().equals(adopted.next()) || committed.run().currentBatch().isPresent() || !retainsLease(run, committed.run()))) throw invalid("execution_batch_committed_progress_mismatch");
            return committed.advanced() ? new Result<>(committed.run(), adopted.materials(), adopted.context(), true, Stop.YIELDED, "execution_batch_advanced") : stopped(committed.run(), materials, context);
        }
        if (run.phase() != Phase.READY) throw invalid("execution_batch_resume_phase_invalid");
        var waiting = store.batchWaits(reference); var responses = new ArrayList<BatchResponse>();
        for (var item : waiting) {
            if (item.acceptedResponse().isEmpty()) throw invalid("execution_batch_response_missing");
            responses.add(new BatchResponse(item, material(run, item.acceptedResponse().orElseThrow().response())));
        }
        var plan = program.batchContinuation(run, materials, results, responses);
        if (plan instanceof BatchPlan.Terminate<M> terminal) {
            return terminate(run, materials, context, reference, results, terminal);
        }
        var calls = ((BatchPlan.Resume<M>) plan).calls(); var continuations = new ArrayList<BatchContinuation>();
        for (var call : calls) {
            var matching = entries.stream().filter(entry -> !entry.reused() && entry.action().reference().equals(call.action().action())).toList();
            if (matching.size() != 1 || call.continuation().isEmpty()) throw invalid("execution_batch_resume_identity_mismatch");
            continuations.add(new BatchContinuation(matching.getFirst().slot().slot(), call.continuation().orElseThrow(), call.action(), call.attemptId(), call.budget()));
        }
        var dispatched = store.dispatchBatchContinuations(new BatchContinue(run.identity().runRef(), run.revision(), run.lease().orElseThrow(), reference, continuations, calls.getFirst().actor()));
        if (dispatched instanceof BatchDispatchResult.Rejected rejected) {
            var finish = program.dispatchRejected(run, materials, rejected.reason());
            if (finish.isPresent()) {
                var adoption = program.batchDispatchRejectionAdoption(run, materials, results, rejected.reason());
                if (adoption.isPresent()) return terminate(run, materials, context, reference, results,
                        new BatchPlan.Terminate<>(new Adoption<>(adoption.orElseThrow().materials(), adoption.orElseThrow().next(), adoption.orElseThrow().context(),
                                finish.orElseThrow().events(), Optional.empty()), finish.orElseThrow().status()));
            }
            return new Result<>(run, materials, context, false, Stop.REJECTED, rejected.reason());
        }
        if (dispatched instanceof BatchDispatchResult.Existing existing) return recoverLoaded(existing.run(), materials, context, existing.entries(), control);
        var registered = (BatchDispatchResult.Registered) dispatched; requireRun(run, registered.run());
        if (quiet(registered.run()) || !retainsLease(run, registered.run())) return stopped(registered.run(), materials, context);
        return round(registered.run(), materials, context, reference, ordered(reference, registered.entries()), outcomes, registered.permits(), calls, control);
    }

    private static List<Send> sends(Run current, BatchRef batch, List<BatchEntry> entries, List<DispatchResult.Registered> permits, List<Step.Dispatch> commands) {
        if (permits.size() != commands.size() || permits.isEmpty()) throw invalid("execution_batch_receipt_mismatch");
        var result = new ArrayList<Send>(); var slots = new java.util.HashSet<Integer>();
        for (var receipt : permits) {
            var matching = entries.stream().filter(entry -> !entry.reused() && entry.attempt().id().equals(receipt.attempt().id())).toList();
            var prepared = commands.stream().filter(command -> command.attemptId().equals(receipt.attempt().id())).toList();
            if (matching.size() != 1 || prepared.size() != 1 || !slots.add(matching.getFirst().slot().slot())) throw invalid("execution_batch_receipt_mismatch");
            var entry = matching.getFirst(); var command = prepared.getFirst(); requireRegistered(current, batch, entry, receipt, command);
            result.add(new Send(entry.slot().slot(), receipt, command));
        }
        return result.stream().sorted(Comparator.comparingInt(Send::slot)).toList();
    }

    private static void requireRegistered(Run current, BatchRef batch, BatchEntry entry, DispatchResult.Registered receipt, Step.Dispatch command) {
        var action = receipt.action(); var attempt = receipt.attempt(); var prepared = command.action();
        if (!receipt.run().equals(current) || !current.currentBatch().equals(Optional.of(batch)) || !entry.action().equals(action) || !entry.attempt().equals(attempt)
                || !action.batchSlot().equals(Optional.of(entry.slot())) || !action.run().equals(batch.run()) || !attempt.run().equals(batch.run()) || attempt.fence() != current.fence()
                || action.ordinal() != prepared.ordinal() || action.kind() != prepared.kind() || !action.target().equals(prepared.target()) || !action.capability().equals(prepared.capability())
                || !action.currentAttempt().equals(attempt.id()) || action.selectedAttempt().isPresent() || !action.reference().equals(prepared.action())
                || command.continuation().isEmpty() && (!action.request().equals(prepared.request().reference()) || !action.fingerprint().equals(prepared.fingerprint()))
                || !attempt.id().equals(command.attemptId()) || !attempt.action().equals(action.reference()) || !attempt.request().equals(prepared.request().reference())
                || attempt.result().isPresent() || !attempt.continuation().equals(command.continuation()) || !receipt.actor().equals(command.actor())) throw invalid("execution_batch_receipt_mismatch");
    }

    private static void requireReuse(BatchEntry entry, BatchMember.Reused source) {
        var attempt = entry.attempt();
        if (!entry.reused() || entry.slot().slot() != source.slot() || !entry.action().reference().equals(source.action()) || !attempt.id().equals(source.attemptId())
                || !attempt.request().equals(source.request()) || !attempt.result().equals(Optional.of(source.result())) || !attempt.feedback().equals(Optional.of(source.feedback()))
                || !terminal(attempt.resultKind())) throw invalid("execution_batch_reuse_receipt_mismatch");
    }
    private static <M> BatchAdoption adoption(BatchRef batch, List<BatchOutcome> results, Adoption<M> adopted) {
        if (adopted.waiting().isPresent() || results.size() != batch.size()) throw invalid("execution_batch_adoption_invalid");
        return new BatchAdoption(resultRefs(results), adopted.next(), adopted.context(), adopted.events());
    }
    private static List<BatchResultRef> resultRefs(List<BatchOutcome> results) {
        return results.stream().map(outcome -> outcome instanceof BatchOutcome.Actual actual
                ? new BatchResultRef(actual.call().action().action(), actual.call().attemptId(), actual.call().result().result().reference())
                : new BatchResultRef(((BatchOutcome.Reused) outcome).source().action(), ((BatchOutcome.Reused) outcome).source().attemptId(), ((BatchOutcome.Reused) outcome).source().result())).toList();
    }
    private static List<BatchEntry> ordered(BatchRef batch, List<BatchEntry> entries) {
        var result = entries.stream().sorted(Comparator.comparingInt(entry -> entry.slot().slot())).toList();
        if (result.size() != batch.size()) throw invalid("execution_batch_members_missing");
        for (int index = 0; index < result.size(); index++) if (!result.get(index).slot().equals(new BatchSlot(batch, index + 1))) throw invalid("execution_batch_slot_mismatch");
        return result;
    }
    private static boolean terminal(Optional<ResultKind> kind) { return kind.filter(value -> value == ResultKind.SUCCEEDED || value == ResultKind.FAILED).isPresent(); }
    private static boolean settled(List<BatchEntry> entries) { return entries.stream().allMatch(entry -> entry.attempt().result().isPresent() && entry.attempt().resultKind().filter(kind -> kind != ResultKind.UNKNOWN).isPresent()); }
    private static boolean hasWaiting(List<BatchEntry> entries) { return entries.stream().anyMatch(entry -> entry.attempt().resultKind().equals(Optional.of(ResultKind.INPUT_REQUIRED))); }
    private static boolean accurate(BatchOutcome[] outcomes) { return Arrays.stream(outcomes).allMatch(value -> value instanceof BatchOutcome.Reused || value instanceof BatchOutcome.Actual actual && (actual.call().result().kind() == ResultKind.SUCCEEDED || actual.call().result().kind() == ResultKind.FAILED)); }
    private Material material(Run run, ResultRef reference) { return material(run.identity().runRef().tenantId(), reference); }
    private Material material(com.skillforge.runtime.api.TenantId tenant, ResultRef reference) { return store.material(tenant, reference).filter(value -> value.reference().equals(reference)).orElseThrow(() -> invalid("execution_material_missing")); }
    private static boolean quiet(Run run) { return run.cancelled() || run.phase() == Phase.WAITING || run.phase() == Phase.UNCERTAIN || run.phase() == Phase.FINISHED || run.lease().isEmpty(); }
    private static void requireRun(Run before, Run after) { if (!before.identity().equals(after.identity())) throw invalid("execution_batch_owner_mismatch"); }
    private static boolean retainsLease(Run before, Run after) { return before.lease().isPresent() && after.lease().isPresent() && before.fence() == after.fence() && before.lease().orElseThrow().claimId().equals(after.lease().orElseThrow().claimId()) && before.lease().orElseThrow().workerId().equals(after.lease().orElseThrow().workerId()); }
    /** 预算与明确续跑拒绝均采用原准确回执，再原子停止批次。 */
    private Result<M> terminate(Run run, M materials, Material context, BatchRef reference, List<BatchOutcome> results, BatchPlan.Terminate<M> terminal) {
        var adoption = terminal.adoption();
        if (adoption.waiting().isPresent()) throw invalid("execution_batch_stop_wait_invalid");
        var stopped = store.stopBatch(new BatchStop(run.identity().runRef(), reference, run.revision(), run.lease().orElseThrow(), resultRefs(results), adoption.next(), adoption.context(), terminal.status(), adoption.events()));
        requireRun(run, stopped);
        if (stopped.phase() == Phase.FINISHED && stopped.progress().equals(adoption.next()) && stopped.currentBatch().isEmpty())
            return new Result<>(stopped, adoption.materials(), adoption.context(), false, Stop.FINISHED, "execution_batch_finished");
        return stopped(stopped, materials, context);
    }

    private Result<M> signal(Run run, M materials, Material context, Stop stop) { return new Result<>(run, materials, context, false, stop, "execution_batch_" + stop.name().toLowerCase(java.util.Locale.ROOT)); }
    private Result<M> stopped(Run run, M materials, Material context) { return signal(run, materials, context, run.cancelled() ? Stop.CANCELLED : run.phase() == Phase.FINISHED ? Stop.FINISHED : run.phase() == Phase.WAITING ? Stop.WAITING : run.phase() == Phase.UNCERTAIN ? Stop.UNKNOWN : Stop.OWNERSHIP_LOST); }
    record Result<M>(Run run, M materials, Material context, boolean advanced, Stop stop, String reason) { }
    private record Finished(int slot, BatchCall actual, RuntimeException failure) { }
    private record Send(int slot, DispatchResult.Registered receipt, Step.Dispatch command) { }
    private static IllegalStateException invalid(String reason) { return new IllegalStateException(reason); }
}
