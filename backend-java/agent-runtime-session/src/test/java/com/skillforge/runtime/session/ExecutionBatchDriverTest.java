package com.skillforge.runtime.session;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.LeaseFence;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.session.ExecutionCoordinator.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 只证明调度顺序和无重放；真实IAM、预算及SQL原子性由Store/Host集成验证。 */
class ExecutionBatchDriverTest {
    @Test void settledBatchAuthorizationRefusalStopsOriginalWaitsWithoutSendingAnotherAttempt() {
        for (String reason : List.of("execution_dispatch_unauthorized", "execution_dispatch_paused")) {
            var f = new Fixture(false); f.firstKind = ResultKind.INPUT_REQUIRED;
            f.driver((receipt, prepared) -> actual(prepared.ordinal(), 1, prepared.ordinal() == 2 ? ResultKind.INPUT_REQUIRED : ResultKind.SUCCEEDED))
                    .dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
            f.store.acceptAll(); f.store.continuationRejection = reason;
            var result = f.driver((receipt, prepared) -> { throw new AssertionError("denied continuation must not send"); })
                    .recover(f.store.current, 0, f.store.context, f.control);
            assertEquals(2, f.store.savedOrdinals.size()); assertEquals(1, f.rejectionCallbacks);
            if (reason.equals("execution_dispatch_unauthorized")) {
                assertEquals(Stop.FINISHED, result.stop()); assertEquals(RunStatus.FAILED, result.run().status());
                assertTrue(result.run().currentBatch().isEmpty()); assertEquals(2, result.run().progress().toolCalls());
                assertEquals("dispatch-denied", f.store.stopEvents.getFirst().type());
            } else {
                assertEquals(Stop.REJECTED, result.stop()); assertTrue(result.run().currentBatch().isPresent()); assertTrue(f.store.stopEvents.isEmpty());
            }
        }
    }

    @Test void savesOutOfOrderCallbacksImmediatelyAndAdoptsCompleteOriginalOrder() {
        var f = new Fixture(false); var started = new CountDownLatch(2); var secondSaved = new CountDownLatch(1); f.store.secondSaved = secondSaved;
        var result = f.driver((receipt, prepared) -> { started.countDown(); await(started); if (prepared.ordinal() == 2) await(secondSaved); return actual(prepared.ordinal(), generation(receipt.attempt().id()), ResultKind.SUCCEEDED); })
                .dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertTrue(result.advanced()); assertEquals(2, result.materials()); assertEquals(List.of(3L, 2L), f.store.savedOrdinals);
        assertEquals(1, f.adoptions); assertEquals(1, f.store.dispatches); assertEquals(0, f.store.reads);
        assertEquals(List.of("tool-2", "tool-3"), f.store.adoptedResults.stream().map(value -> value.action().actionId()).toList());
    }

    @Test void serialBatchRunsInSlotOrderWithOneInFlightAndKeepsEachResult() {
        var f = new Fixture(false); f.parallel = false;
        var active = new AtomicInteger(); var peak = new AtomicInteger(); var sent = new ArrayList<Long>();
        var result = f.driver((receipt, prepared) -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { sent.add(prepared.ordinal()); return actual(prepared.ordinal(), 1, ResultKind.SUCCEEDED); }
            finally { active.decrementAndGet(); }
        }).dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertTrue(result.advanced()); assertEquals(1, peak.get()); assertEquals(0, active.get());
        assertEquals(List.of(2L, 3L), sent); assertEquals(List.of(2L, 3L), f.store.savedOrdinals);
        assertEquals(List.of("tool-2", "tool-3"), f.store.adoptedResults.stream().map(value -> value.action().actionId()).toList());
    }

    @Test void serialBatchPreservesFailedAndSuccessfulSiblingsAndRecoveryNeverResends() {
        var f = new Fixture(false); f.parallel = false; f.firstKind = ResultKind.FAILED;
        var result = f.driver((receipt, prepared) -> actual(prepared.ordinal(), 1, prepared.ordinal() == 2 ? ResultKind.FAILED : ResultKind.SUCCEEDED))
                .dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertTrue(result.advanced()); assertEquals(List.of(2L, 3L), f.store.savedOrdinals);
        assertEquals(Optional.of(ResultKind.FAILED), f.store.entries.getFirst().attempt().resultKind());
        assertEquals(Optional.of(ResultKind.SUCCEEDED), f.store.entries.getLast().attempt().resultKind());
        var saved = new Fixture(false); saved.parallel = false; saved.firstKind = ResultKind.FAILED;
        saved.store.register(saved.batch); saved.store.save(0, actual(2, 1, ResultKind.FAILED), Optional.empty());
        saved.store.save(1, actual(3, 1, ResultKind.SUCCEEDED), Optional.empty());
        var recovered = saved.driver((receipt, prepared) -> { throw new AssertionError("saved attempt cannot send"); })
                .recover(saved.store.current, 0, saved.store.context, saved.control);
        assertTrue(recovered.advanced()); assertEquals(1, saved.store.recoveryAdoptions);
    }

    @Test void oneFreshMixedSlotNeverSendsOrReloadsReusedOriginalSingleAction() {
        var f = new Fixture(true); var sent = new ArrayList<Long>();
        var result = f.driver((receipt, prepared) -> { sent.add(prepared.ordinal()); return actual(prepared.ordinal(), 1, ResultKind.SUCCEEDED); })
                .dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertTrue(result.advanced()); assertEquals(List.of(2L), sent); assertEquals(0, f.store.reads); assertEquals(1, result.run().progress().toolCalls());
        var original = f.store.entries.getFirst(); assertTrue(original.reused()); assertTrue(original.action().batchSlot().isEmpty());
        assertEquals("original-single", f.store.adoptedResults.getFirst().action().actionId()); assertEquals(1, f.store.savedOrdinals.size());
    }

    @Test void recoveryAdoptsOnlySavedAccurateResultsWithoutCapabilityInvocation() {
        var f = new Fixture(false); f.store.register(f.batch); f.store.save(0, actual(2, 1, ResultKind.SUCCEEDED), Optional.empty()); f.store.save(1, actual(3, 1, ResultKind.SUCCEEDED), Optional.empty());
        var result = f.driver((receipt, prepared) -> { throw new AssertionError("recovery cannot send"); }).recover(f.store.current, 0, f.store.context, f.control);
        assertTrue(result.advanced()); assertEquals(6, f.store.reads); assertEquals(1, f.store.recoveryAdoptions); assertEquals(0, f.store.quiescences);
    }

    @Test void missingOrUnknownCurrentAttemptNeverReplaysAccurateSibling() {
        for (boolean unknown : List.of(false, true)) {
            var f = new Fixture(false); f.store.register(f.batch); f.store.save(1, actual(3, 1, ResultKind.SUCCEEDED), Optional.empty());
            if (unknown) f.store.save(0, actual(2, 1, ResultKind.UNKNOWN), Optional.empty());
            var result = f.driver((receipt, prepared) -> { throw new AssertionError("missing cannot send"); }).recover(f.store.current, 0, f.store.context, f.control);
            assertFalse(result.advanced()); assertEquals(Stop.UNKNOWN, result.stop()); assertEquals(1, f.store.quiescences); assertEquals(0, f.adoptions); assertEquals(0, f.store.reads); assertEquals(0, f.rejectionCallbacks);
        }
    }

    @Test void earlyReplyPreservesSiblingSendAuthorityAndPartialRepliesRemainWaiting() {
        var f = new Fixture(false); var firstWait = new CountDownLatch(1); var started = new CountDownLatch(2); f.store.onWait = firstWait;
        f.store.acceptFirstEarly = true;
        var result = f.driver((receipt, prepared) -> {
            started.countDown(); await(started);
            if (prepared.ordinal() == 3) { await(firstWait); assertEquals(receipt.run().revision(), f.store.current.revision()); assertEquals(receipt.run().lease(), f.store.current.lease()); assertEquals(receipt.run().actorVerificationRef(), f.store.current.actorVerificationRef()); }
            return actual(prepared.ordinal(), 1, ResultKind.INPUT_REQUIRED);
        }).dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertEquals(Stop.WAITING, result.stop()); assertEquals(0, f.adoptions); assertEquals(0, result.run().progress().toolCalls());
        assertTrue(f.store.waits.getFirst().acceptedResponse().isPresent()); assertTrue(f.store.waits.getLast().acceptedResponse().isEmpty());
        var acceptedBefore = f.store.waits.getFirst().acceptedResponse().orElseThrow(); f.store.acceptAll(); assertEquals(acceptedBefore, f.store.waits.getFirst().acceptedResponse().orElseThrow());
        var continuationCalls = Collections.synchronizedList(new ArrayList<Long>());
        var resumed = f.driver((receipt, prepared) -> { continuationCalls.add(prepared.ordinal()); assertEquals(2, generation(receipt.attempt().id())); return actual(prepared.ordinal(), 2, ResultKind.SUCCEEDED); })
                .recover(f.store.current, 0, f.store.context, f.control);
        assertTrue(resumed.advanced()); assertEquals(Set.of(2L, 3L), new HashSet<>(continuationCalls)); assertEquals(4, resumed.run().progress().toolCalls()); assertEquals(1, f.adoptions);
    }

    @Test void repeatedInputResumesOnlyThatSlotAndKeepsAccurateSiblingThroughEveryGeneration() {
        var f = new Fixture(false); f.store.autoAccept = true; var invoked = Collections.synchronizedList(new ArrayList<String>());
        var result = f.driver((receipt, prepared) -> {
            invoked.add(receipt.attempt().id()); long generation = generation(receipt.attempt().id());
            return actual(prepared.ordinal(), generation, prepared.ordinal() == 2 && generation < 3 ? ResultKind.INPUT_REQUIRED : ResultKind.SUCCEEDED);
        }).dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertTrue(result.advanced()); assertEquals(4, result.run().progress().toolCalls()); assertEquals(1, f.adoptions);
        assertEquals(1, invoked.stream().filter(id -> id.startsWith("tool-3:")).count()); assertEquals(3, invoked.stream().filter(id -> id.startsWith("tool-2:")).count());
        assertEquals(0, f.store.batchReads); assertEquals(2, f.store.continuationDispatches);
    }

    @Test void recoveryResumesOnlyAcceptedCurrentWaitAndPreservesAccurateOrReusedSibling() {
        for (boolean mixed : List.of(false, true)) {
            var f = new Fixture(mixed); f.store.register(f.batch); int waitingSlot = mixed ? 2 : 1;
            var pending = f.batch.calls().getFirst(); var wait = new WaitDeclaration(new WaitRef(pending.action().action(), 1), WaitKind.TOOL, Map.of("question", ToolInputMethod.ELICITATION_CREATE), Optional.empty());
            f.store.save(waitingSlot - 1, actual(pending.action().ordinal(), 1, ResultKind.INPUT_REQUIRED), Optional.of(wait));
            if (!mixed) f.store.save(1, actual(3, 1, ResultKind.SUCCEEDED), Optional.empty());
            f.store.acceptAll(); var invoked = new ArrayList<String>();
            var result = f.driver((receipt, prepared) -> { invoked.add(receipt.attempt().id()); return actual(prepared.ordinal(), 2, ResultKind.SUCCEEDED); }).recover(f.store.current, 0, f.store.context, f.control);
            assertTrue(result.advanced()); assertEquals(List.of("tool-2:attempt:2"), invoked); assertEquals(mixed ? 2 : 3, result.run().progress().toolCalls());
            assertEquals(mixed ? 2 : 5, f.store.reads - 1); assertEquals(1, f.store.continuationDispatches); assertEquals(1, f.adoptions);
        }
    }

    @Test void cancelledGroupStillSavesAllLateActualResultsWithoutAdvancing() {
        var f = new Fixture(false); f.store.cancelOnFirstResult = true; var started = new CountDownLatch(2);
        var result = f.driver((receipt, prepared) -> { started.countDown(); await(started); return actual(prepared.ordinal(), 1, ResultKind.SUCCEEDED); }).dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertEquals(Stop.CANCELLED, result.stop()); assertEquals(2, f.store.savedOrdinals.size()); assertEquals(0, result.run().progress().toolCalls()); assertEquals(0, f.adoptions);
    }

    @Test void batchBudgetTerminationAtomicallyStoresRealPhysicalCountsWithoutNativeAdoption() {
        var f = new Fixture(false); f.store.autoAccept = true; f.stopOnResume = true;
        var result = f.driver((receipt, prepared) -> actual(prepared.ordinal(), 1, ResultKind.INPUT_REQUIRED)).dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertEquals(Stop.FINISHED, result.stop()); assertEquals(RunStatus.TERMINATED, result.run().status()); assertEquals(2, result.run().progress().toolCalls());
        assertEquals(0, f.adoptions); assertEquals(0, f.store.continuationDispatches); assertEquals(2, f.store.adoptedResults.size()); assertTrue(result.run().currentBatch().isEmpty());
    }

    @Test void lateCallbacksAfterLeaseTakeoverSaveFactsWithoutAdoptionOrQuiescingNewOwner() {
        var f = new Fixture(false); f.store.loseOnFirstResult = true; var started = new CountDownLatch(2);
        var result = f.driver((receipt, prepared) -> { started.countDown(); await(started); return actual(prepared.ordinal(), 1, ResultKind.SUCCEEDED); }).dispatch(f.store.current, 0, f.store.context, f.batch, f.control);
        assertEquals(Stop.OWNERSHIP_LOST, result.stop()); assertEquals(2, f.store.savedOrdinals.size()); assertEquals(0, f.adoptions); assertEquals(0, f.store.quiescences); assertEquals(2, result.run().fence());
    }

    @Test void technicalFailureDrainsAndSavesSiblingBeforeQuiescing() {
        var f = new Fixture(false); var started = new CountDownLatch(2); var failure = new IllegalStateException("controlled_failure");
        assertEquals(failure, assertThrows(IllegalStateException.class, () -> f.driver((receipt, prepared) -> { started.countDown(); await(started); if (prepared.ordinal() == 2) throw failure; return actual(3, 1, ResultKind.SUCCEEDED); }).dispatch(f.store.current, 0, f.store.context, f.batch, f.control)));
        assertEquals(List.of(3L), f.store.savedOrdinals); assertEquals(Phase.UNCERTAIN, f.store.current.phase()); assertEquals(0, f.adoptions);
    }

    private static void await(CountDownLatch latch) { try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture_latch_timeout"); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); } }

    private static final class Fixture implements Program<Integer> {
        private int rejectionCallbacks;
        @Override public Optional<Step.Finish> dispatchRejected(Run run, Integer materials, String reason) {
            rejectionCallbacks++;
            return "execution_dispatch_unauthorized".equals(reason) ? Optional.of(new Step.Finish(RunStatus.FAILED,
                    List.of(new Event(new com.skillforge.runtime.api.EventId("denial"), "dispatch-denied", new TypedPayload("application/json", "{}"))))) : Optional.empty();
        }
        @Override public Optional<Adoption<Integer>> batchDispatchRejectionAdoption(Run run, Integer materials, List<BatchOutcome> results, String reason) {
            int physical = results.stream().filter(BatchOutcome.Actual.class::isInstance).map(BatchOutcome.Actual.class::cast)
                    .mapToInt(value -> Math.toIntExact(generation(value.call().attemptId()))).sum();
            var context = material("denied-batch-context", "{}");
            return Optional.of(new Adoption<>(0, new Progress(1, physical, 0, context.reference()), context, List.of(), Optional.empty()));
        }
        private final Store store = new Store(); private final Step.Batch batch; private int adoptions; private boolean stopOnResume; private boolean parallel = true; private ResultKind firstKind = ResultKind.SUCCEEDED;
        private final Control control = new Control() { public boolean cancelled() { return false; } public boolean ownershipLost() { return false; } public void close() { } };
        private Fixture(boolean mixed) {
            var members = new ArrayList<BatchMember>();
            if (mixed) members.add(new BatchMember.Reused(1, new ActionRef(SessionFixtures.TENANT, "original-single"), "original-attempt", new ResultRef("original-request"), new ResultRef("original-result"), new ResultRef("original-feedback")));
            else members.add(member(1, dispatch(2, Optional.empty())));
            members.add(member(2, dispatch(mixed ? 2 : 3, Optional.empty()))); batch = new Step.Batch(store.source.reference(), members, SessionFixtures.ADMISSION);
        }
        private ExecutionBatchDriver<Integer> driver(Capabilities capabilities) { return new ExecutionBatchDriver<>(store, this, capabilities); }
        public boolean batchParallel(Integer materials) { return parallel; }
        public Integer open(Run run, Material context) { throw new AssertionError(); }
        public Step next(Run run, Integer materials) { throw new AssertionError(); }
        public Step continuation(Run run, Integer materials, Waiting waiting, Material response) { throw new AssertionError(); }
        public Adoption<Integer> acceptResponse(Run run, Integer materials, Waiting waiting, Material response) { throw new AssertionError(); }
        public Adoption<Integer> acceptInput(Run run, Integer materials, SupplementalInput input, Material content) { throw new AssertionError(); }
        public Adoption<Integer> planAdoption(Run run, Integer materials, Step.PlanLocal command, Material result) { throw new AssertionError(); }
        public Adoption<Integer> adopt(Run run, Integer materials, Prepared prepared, CapabilityResult result) { throw new AssertionError(); }
        public BatchResult inspectBatch(Run run, Integer materials, BatchCall result, int slot) {
            return new BatchResult(result.result().kind() == ResultKind.INPUT_REQUIRED ? Optional.of(new WaitDeclaration(new WaitRef(result.action().action(), generation(result.attemptId())), WaitKind.TOOL, Map.of("question", ToolInputMethod.ELICITATION_CREATE), Optional.empty())) : Optional.empty(), List.of());
        }
        public Adoption<Integer> adoptBatch(Run run, Integer materials, List<BatchOutcome> results) {
            adoptions++; assertEquals(2, results.size()); assertEquals(List.of(1, 2), results.stream().map(BatchOutcome::slot).toList()); int physical = 0;
            for (var outcome : results) if (outcome instanceof BatchOutcome.Actual actual) { assertEquals(actual.slot() == 1 ? firstKind : ResultKind.SUCCEEDED, actual.call().result().kind()); physical += Math.toIntExact(generation(actual.call().attemptId())); }
            var context = material("context-batch", "{\"complete\":true}"); return new Adoption<>(2, new Progress(1, physical, 0, context.reference()), context, List.of(), Optional.empty());
        }
        public BatchPlan<Integer> batchContinuation(Run run, Integer materials, List<BatchOutcome> results, List<BatchResponse> responses) {
            if (stopOnResume) {
                int physical = results.stream().filter(BatchOutcome.Actual.class::isInstance).map(BatchOutcome.Actual.class::cast).mapToInt(value -> Math.toIntExact(generation(value.call().attemptId()))).sum();
                var context = material("batch-stop-context", "{\"stopped\":true}");
                return new BatchPlan.Terminate<>(new Adoption<>(0, new Progress(1, physical, 0, context.reference()), context, List.of(), Optional.empty()), RunStatus.TERMINATED);
            }
            var calls = new ArrayList<Step.Dispatch>();
            for (var outcome : results) if (outcome instanceof BatchOutcome.Actual actual && actual.call().result().kind() == ResultKind.INPUT_REQUIRED) {
                var waiting = responses.stream().map(BatchResponse::waiting).filter(value -> value.sourceAttempt().equals(actual.call().attemptId())).findFirst().orElseThrow();
                calls.add(dispatch(actual.call().action().ordinal(), Optional.of(waiting.reference())));
            }
            assertEquals(calls.size(), responses.size()); return new BatchPlan.Resume<>(calls);
        }
    }

    private static final class Store implements ExecutionStore {
        private final Material context = ExecutionBatchDriverTest.material("context-model", "{}");
        private final Action source = new Action(new ActionRef(SessionFixtures.TENANT, "model-source"), SessionFixtures.IDENTITY.runRef(), 1, Kind.MODEL, "model", new ResultRef("model-capability"), new ResultRef("model-request"), "fingerprint", ActionStatus.SUCCEEDED, "model-attempt", Optional.of("model-attempt"), Optional.empty());
        private final List<Material> materials = new ArrayList<>(); private final List<BatchEntry> entries = new ArrayList<>(); private final List<Waiting> waits = new ArrayList<>(); private final List<Long> savedOrdinals = new ArrayList<>();
        private List<BatchResultRef> adoptedResults = List.of(); private Run current = run(2, Phase.READY, new Progress(1, 0, 0, context.reference()), Optional.empty(), false);
        private String continuationRejection;
        private List<Event> stopEvents = List.of();
        private int dispatches, continuationDispatches, reads, batchReads, quiescences, recoveryAdoptions; private boolean cancelOnFirstResult, loseOnFirstResult, autoAccept, acceptFirstEarly; private CountDownLatch secondSaved, onWait;
        public Optional<Action> action(ActionRef reference) { assertEquals(source.reference(), reference); return Optional.of(source); }
        public BatchDispatchResult dispatchBatch(BatchDispatch command) {
            dispatches++; var reference = new BatchRef(command.run(), command.sourceAttempt(), command.requests().size()); current = run(current.revision() + 1, Phase.AWAITING_RESULT, current.progress(), Optional.of(reference), false); var permits = new ArrayList<DispatchResult.Registered>();
            for (var member : command.requests()) {
                var slot = new BatchSlot(reference, member.slot());
                if (member instanceof BatchMember.Reused reuse) {
                    var action = new Action(reuse.action(), command.run(), 1, Kind.TOOL, "tool", new ResultRef("tool-capability"), reuse.request(), "original-fingerprint", ActionStatus.SUCCEEDED, reuse.attemptId(), Optional.of(reuse.attemptId()), Optional.empty());
                    var attempt = new Attempt(reuse.attemptId(), reuse.action(), command.run(), 1, reuse.request(), Optional.empty(), Optional.of(ResultKind.SUCCEEDED), Optional.of(reuse.result()), Optional.empty(), Optional.of(reuse.feedback()), Optional.empty(), true);
                    entries.add(new BatchEntry(slot, action, attempt, true));
                } else {
                    var request = ((BatchMember.Fresh) member).request(); var prepared = request.action(); materials.add(prepared.request()); materials.addAll(prepared.requestParts());
                    var action = new Action(prepared.action(), command.run(), prepared.ordinal(), Kind.TOOL, prepared.target(), prepared.capability(), prepared.request().reference(), prepared.fingerprint(), ActionStatus.DISPATCHED, request.attemptId(), Optional.empty(), Optional.of(slot));
                    var attempt = new Attempt(request.attemptId(), action.reference(), command.run(), 1, prepared.request().reference(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
                    entries.add(new BatchEntry(slot, action, attempt, false)); permits.add(new DispatchResult.Registered(current, action, attempt, command.actor()));
                }
            }
            return new BatchDispatchResult.Registered(current, entries, permits);
        }
        private void register(Step.Batch batch) { dispatchBatch(new BatchDispatch(current.identity().runRef(), current.revision(), current.lease().orElseThrow(), "model-attempt", batch.members(), batch.actor())); }
        private void save(int index, CapabilityResult actual, Optional<WaitDeclaration> declaration) {
            materials.add(actual.result()); actual.feedback().ifPresent(materials::add); var entry = entries.get(index); var old = entry.attempt();
            entries.set(index, new BatchEntry(entry.slot(), entry.action(), new Attempt(old.id(), old.action(), old.run(), old.fence(), old.request(), old.continuation(), Optional.of(actual.kind()), Optional.of(actual.result().reference()), Optional.empty(), actual.feedback().map(Material::reference), Optional.empty(), false), false));
            declaration.ifPresent(value -> {
                waits.add(new Waiting(value.reference(), WaitKind.TOOL, current.identity().runRef(), old.id(), value.requested(), Optional.empty(), Optional.empty(), Optional.empty(), OptionalLong.empty()));
                if (autoAccept || acceptFirstEarly && waits.size() == 1) accept(waits.size() - 1);
                if (onWait != null) onWait.countDown();
            });
        }
        public CompletionResult completeBatch(BatchCompletion command) {
            int index = -1; for (int position = 0; position < entries.size(); position++) if (entries.get(position).attempt().id().equals(command.result().attemptId())) index = position;
            assertTrue(index >= 0); long ordinal = entries.get(index).action().ordinal(); savedOrdinals.add(ordinal);
            var actual = command.result(); save(index, new CapabilityResult(actual.kind(), actual.result(), actual.feedback(), actual.externalOperation(), actual.usage()), actual.waitDeclaration());
            if (cancelOnFirstResult && savedOrdinals.size() == 1) current = run(current.revision() + 1, Phase.FINISHED, current.progress(), current.currentBatch(), true);
            if (loseOnFirstResult && savedOrdinals.size() == 1) current = new Run(current.identity(), current.configuration(), RunStatus.RUNNING, current.revision() + 1, 2, false, current.phase(), current.progress(),
                    Optional.of(new LeaseFence(SessionFixtures.TENANT, "new-claim", "new-worker", 2, SessionFixtures.NOW.plusSeconds(60))), current.currentAction(), current.currentBatch(), current.waiting(), current.actorVerificationRef(), Optional.empty(), current.createdAt(), current.updatedAt());
            boolean advanced = !current.cancelled() && command.adoption().isPresent(); if (advanced) adopt(command.adoption().orElseThrow()); if (ordinal == 3 && secondSaved != null) secondSaved.countDown();
            return new CompletionResult(current, entries.get(index).attempt(), true, advanced);
        }
        private void accept(int index) {
            var waiting = waits.get(index); if (waiting.acceptedResponse().isPresent()) return;
            var response = ExecutionBatchDriverTest.material("response-" + waiting.sourceAttempt(), "{}"); materials.add(response);
            waits.set(index, new Waiting(waiting.reference(), waiting.kind(), waiting.run(), waiting.sourceAttempt(), waiting.requested(), waiting.internal(), Optional.of(new AcceptedResponse(new InputId("input-" + waiting.sourceAttempt()), response.reference(), "response-fingerprint", new ResultRef("actor-" + index), current.revision())), Optional.empty(), OptionalLong.empty()));
        }
        private void acceptAll() { for (int index = 0; index < waits.size(); index++) accept(index); current = run(current.revision() + 1, Phase.READY, current.progress(), current.currentBatch(), false); }
        public BatchDispatchResult dispatchBatchContinuations(BatchContinue command) {
            if (continuationRejection != null) return new BatchDispatchResult.Rejected(continuationRejection);
            continuationDispatches++; current = run(current.revision() + 1, Phase.AWAITING_RESULT, current.progress(), current.currentBatch(), false); var permits = new ArrayList<DispatchResult.Registered>();
            for (var item : command.continuations()) {
                int index = item.slot() - 1; var entry = entries.get(index); var old = entry.action(); materials.add(item.action().request()); materials.addAll(item.action().requestParts());
                var action = new Action(old.reference(), old.run(), old.ordinal(), old.kind(), old.target(), old.capability(), old.request(), old.fingerprint(), ActionStatus.DISPATCHED, item.attemptId(), Optional.empty(), old.batchSlot());
                var attempt = new Attempt(item.attemptId(), action.reference(), action.run(), current.fence(), item.action().request().reference(), Optional.of(item.waiting()), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false);
                entries.set(index, new BatchEntry(entry.slot(), action, attempt, false)); permits.add(new DispatchResult.Registered(current, action, attempt, command.actor()));
            }
            return new BatchDispatchResult.Registered(current, entries, permits);
        }
        public List<Waiting> batchWaits(BatchRef reference) { return entries.stream().filter(entry -> entry.attempt().resultKind().equals(Optional.of(ResultKind.INPUT_REQUIRED))).map(entry -> waits.stream().filter(waiting -> waiting.sourceAttempt().equals(entry.attempt().id())).findFirst().orElseThrow()).toList(); }
        public BatchAdoptionResult adoptBatch(BatchAdopt command) { recoveryAdoptions++; adopt(command.adoption()); return new BatchAdoptionResult(current, true); }
        private void adopt(BatchAdoption adoption) { adoptedResults = adoption.results(); materials.add(adoption.context()); current = run(current.revision() + 1, Phase.READY, adoption.next(), Optional.empty(), false); }
        public BatchState quiesceBatch(BatchQuiesce command) {
            quiescences++; boolean unknown = entries.stream().anyMatch(entry -> entry.attempt().resultKind().isEmpty() || entry.attempt().resultKind().equals(Optional.of(ResultKind.UNKNOWN)));
            boolean waiting = !unknown && entries.stream().anyMatch(entry -> entry.attempt().resultKind().equals(Optional.of(ResultKind.INPUT_REQUIRED)));
            var phase = unknown ? Phase.UNCERTAIN : waiting ? batchWaits(command.batch()).stream().allMatch(value -> value.acceptedResponse().isPresent()) ? Phase.READY : Phase.WAITING : current.phase();
            if (phase != current.phase()) current = run(current.revision() + 1, phase, current.progress(), current.currentBatch(), false);
            return new BatchState(current, entries, true);
        }
        public List<BatchEntry> batch(BatchRef reference) { batchReads++; return List.copyOf(entries); }
        public List<Optional<Material>> materials(TenantId tenant, List<ResultRef> references) {
            reads++;
            return references.stream().map(reference -> materials.stream()
                    .filter(value -> value.reference().equals(reference)).findFirst()).toList();
        }
        public Optional<Material> material(TenantId tenant, ResultRef reference) { reads++; return materials.stream().filter(value -> value.reference().equals(reference)).findFirst(); }
        public Run stopBatch(BatchStop command) {
            stopEvents = command.events();
            adoptedResults = command.results(); materials.add(command.context());
            for (int index = 0; index < entries.size(); index++) assertEquals(entries.get(index).attempt().result().orElseThrow(), command.results().get(index).result());
            current = new Run(current.identity(), current.configuration(), command.status(), current.revision() + 1, current.fence(), false, Phase.FINISHED, command.next(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), current.actorVerificationRef(), Optional.empty(), current.createdAt(), current.updatedAt());
            return current;
        }
        public AdmissionResult admit(Admission command) { throw new AssertionError(); }
        public Optional<Run> load(RunRef reference) { throw new AssertionError(); }
        public Optional<Material> originalInput(RunRef reference) { throw new AssertionError(); }
        public Optional<SavedEvent> event(RunRef reference, EventId event) { throw new AssertionError(); }
        @Override public Optional<SavedEvent> event(SessionRef session, com.skillforge.runtime.api.EventId event) { throw new AssertionError("unexpected exact session event"); }
        public Optional<Run> claim(Claim command) { throw new AssertionError(); }
        public DispatchResult dispatch(Dispatch command) { throw new AssertionError(); }
        public boolean authorizeSend(DispatchResult.Registered registered, Prepared prepared) { throw new AssertionError(); }
        public CompletionResult complete(Completion command) { throw new AssertionError(); }
        public ResumeResult resume(Resume command) { throw new AssertionError(); }
        public LocalResponseResult acceptLocalResponse(AcceptLocalResponse command) { throw new AssertionError(); }
        public InputOffer offerInput(OfferInput command) { throw new AssertionError(); }
        public List<SupplementalInput> inputs(RunRef reference, long afterOrdinal, boolean pendingOnly, int limit) { throw new AssertionError(); }
        public InputAcceptance acceptInput(AcceptInput command) { throw new AssertionError(); }
        public QuiescenceResult quiesceUnknown(QuiesceUnknown command) { throw new AssertionError(); }
        public Run finish(Finish command) { throw new AssertionError(); }
        public Run cancel(Cancel command) { throw new AssertionError(); }
        public Run renew(RunRef reference, LeaseFence lease, Duration duration) { throw new AssertionError(); }
        public List<Action> actions(RunRef reference, long afterOrdinal, int limit) { throw new AssertionError(); }
        public TokenUsage usage(RunRef reference) { throw new AssertionError(); }
        public Optional<Attempt> attempt(RunRef reference, String attempt) { throw new AssertionError(); }
        public Optional<Waiting> waiting(RunRef reference, WaitRef waiting) { throw new AssertionError(); }
        public Optional<Waiting> waiting(RunRef reference, String sourceAttempt) { throw new AssertionError(); }
        public Optional<Waiting> response(RunRef reference, InputId input) { throw new AssertionError(); }
        public List<RunRef> due(TenantId tenant, int limit) { throw new AssertionError(); }
        public Optional<Instant> nextDueAt(TenantId tenant) { throw new AssertionError(); }
        public List<SavedEvent> events(SessionRef session, long afterSequence, int limit) { throw new AssertionError(); }
        @Override public List<SavedEvent> eventsInRange(SessionRef session, long afterSequence, long throughSequence, int limit) { throw new AssertionError(); }
    }

    private static BatchMember member(int slot, Step.Dispatch call) { return new BatchMember.Fresh(slot, new BatchRequest(call.action(), call.attemptId(), call.budget())); }
    private static Step.Dispatch dispatch(long ordinal, Optional<WaitRef> continuation) {
        long generation = continuation.map(value -> value.generation() + 1).orElse(1L); var request = material("request-" + ordinal + "-" + generation, "{\"generation\":" + generation + "}");
        var fingerprint = AgentExecutionCodec.digest(request.content().schemaId() + "\0" + request.content().canonicalJson());
        var prepared = new Prepared(new ActionRef(SessionFixtures.TENANT, "tool-" + ordinal), ordinal, Kind.TOOL, "tool", new ResultRef("tool-capability"), request, fingerprint); String attempt = prepared.action().actionId() + ":attempt:" + generation;
        return new Step.Dispatch(prepared, attempt, new ExecutionBudgetPort.Demand(SessionFixtures.IDENTITY.runRef(), attempt, fingerprint, ExecutionBudgetPort.Category.TOOL, ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.TOOL, 0, 0), false), SessionFixtures.ADMISSION, continuation, List.of());
    }
    private static long generation(String attempt) { return Long.parseLong(attempt.substring(attempt.lastIndexOf(':') + 1)); }
    private static CapabilityResult actual(long ordinal, long generation, ResultKind kind) { return new CapabilityResult(kind, material("result-" + ordinal + "-" + generation, "{\"actual\":" + ordinal + "}"), kind == ResultKind.SUCCEEDED ? Optional.of(material("safe-" + ordinal + "-" + generation, "{\"safe\":" + ordinal + "}")) : Optional.empty(), Optional.empty(), ExecutionBudgetPort.Usage.unreported()); }
    private static Material material(String reference, String json) { return new Material(new ResultRef(reference), new TypedPayload("fixture.batch.v1", json)); }
    private static Run run(long revision, Phase phase, Progress progress, Optional<BatchRef> batch, boolean cancelled) {
        var lease = cancelled || phase == Phase.UNCERTAIN || phase == Phase.WAITING ? Optional.<LeaseFence>empty() : Optional.of(new LeaseFence(SessionFixtures.TENANT, "claim", "worker", 1, SessionFixtures.NOW.plusSeconds(60)));
        return new Run(SessionFixtures.IDENTITY, new ResultRef("configuration"), cancelled ? RunStatus.CANCELLED : phase == Phase.UNCERTAIN || phase == Phase.WAITING ? RunStatus.SUSPENDED : RunStatus.RUNNING, revision, 1, cancelled, phase, progress, lease, Optional.empty(), batch, Optional.empty(), SessionFixtures.ADMISSION.verificationRef(), Optional.empty(), SessionFixtures.NOW, SessionFixtures.NOW.plusNanos(revision));
    }
}
