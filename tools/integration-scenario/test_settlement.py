"""适配器自身事务与并发协议测试；FakeWallet不冒充独立HTTP钱包验收。"""
from __future__ import annotations

import sqlite3
import json
import tempfile
import threading
import unittest
from decimal import Decimal
from pathlib import Path

from settlement import SettlementAdapter
from settlement_contract import SettlementError, WalletUnknown


PRICE = {"revision": "price-1", "currency": "CNY", "input_cents_per_thousand_tokens": "2",
         "output_cents_per_thousand_tokens": "7", "tool_cents_per_call": "3"}
ACCOUNTS = {"7": {"wallet_account_id": "customer-7", "currency": "CNY"}}


class FakeWallet:
    def __init__(self):
        self.receipts, self.net, self.sends, self.lookups = {}, {}, [], []
        self.reject = False
        self.unknown = False

    def lookup(self, instruction_id):
        self.lookups.append(instruction_id)
        return self.receipts.get(instruction_id)

    def settle(self, instruction):
        self.sends.append(dict(instruction))
        if self.unknown:
            raise WalletUnknown("simulated transport outcome unknown")
        key = instruction["instruction_id"]
        if key in self.receipts:
            return self.receipts[key]
        old = self.net.get(instruction["object_id"], Decimal(0))
        target = Decimal(instruction["target_amount"])
        net = old if self.reject else target
        self.net[instruction["object_id"]] = net
        receipt = {"instruction_id": key, "object_id": instruction["object_id"], "sequence": instruction["sequence"],
                   "outcome": "rejected" if self.reject else "applied", "confirmed_net_amount": str(net),
                   "delta": str(net - old), "receipt_id": "receipt:" + key}
        self.receipts[key] = receipt
        return receipt


class SettlementTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "adapter.sqlite3"
        self.db = self.connect()
        self.db.executescript("CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT NOT NULL);"
                              "CREATE TABLE usage(event_id TEXT PRIMARY KEY,tenant TEXT NOT NULL,payload TEXT NOT NULL);")
        self.now = [1000.0]
        self.adapter = SettlementAdapter(self.db, PRICE, ACCOUNTS, clock=lambda: self.now[0])
        self.wallet = FakeWallet()

    def tearDown(self):
        self.db.close()
        self.directory.cleanup()

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA journal_mode=WAL")
        return db

    def event(self, identifier, revision=0, **values):
        return {"event_id": str(identifier), "cursor": "cursor:" + str(identifier), "tenant_id": "7",
                "kind": "usage_report", "event_type": "llm.chat", "attempt_id": "attempt-1", "revision": revision,
                "usage_source": "PROVIDER_REPORTED", "prompt_tokens": None, "completion_tokens": None, **values}

    def consume(self, *events, stream="main", adapter=None, fault=None):
        adapter = adapter or self.adapter
        current = adapter.snapshot()["cursors"].get(stream)
        return adapter.consume_usage(stream, {"items": list(events), "next_cursor": events[-1]["cursor"]}, current, fault)

    @staticmethod
    def crash_at(stage):
        def fail(current):
            if current == stage:
                raise RuntimeError("simulated crash:" + stage)
        return fail

    def test_partial_dimensions_and_late_correction_keep_unknown_separate(self):
        self.consume(self.event("unknown", kind="unknown", usage_source="UNKNOWN"))
        self.assertEqual({}, self.adapter.totals())
        self.assertIsNone(self.adapter.snapshot()["objects"][0]["target_amount"])
        self.consume(self.event("input", 1, prompt_tokens=1000))
        self.assertEqual({"7": "2"}, self.adapter.totals())
        self.assertEqual("partial", self.adapter.snapshot()["objects"][0]["measurement_status"])
        self.consume(self.event("output", 1, completion_tokens=1000))
        self.assertEqual({"7": "9"}, self.adapter.totals())
        self.consume(self.event("old", 0, prompt_tokens=50, completion_tokens=50))
        self.assertEqual({"7": "9"}, self.adapter.totals())
        self.consume(self.event("correction", 2, prompt_tokens=800, completion_tokens=1000))
        self.assertEqual({"7": "8.6"}, self.adapter.totals())
        self.assertEqual(3, len(self.adapter.snapshot()["instructions"]))

    def test_measured_and_reports_merge_same_attempt_and_cost_facts_only_audit(self):
        self.consume(self.event("report", 1, prompt_tokens=1000, completion_tokens=0))
        self.consume(self.event("terminal", 0, kind="measured", prompt_tokens=1000, completion_tokens=0))
        self.consume(self.event("cost", kind="confirmation", attempt_id=None, amount_cents="90000"),
                     self.event("cost-adjust", kind="adjustment", attempt_id=None, amount_cents="-500"),
                     self.event("request", event_type="request", attempt_id=None))
        snapshot = self.adapter.snapshot()
        self.assertEqual(1, len(snapshot["objects"]))
        self.assertEqual(1, len(snapshot["instructions"]))
        self.assertEqual({"7": "2"}, self.adapter.totals())
        self.assertEqual(5, snapshot["consumed_events"])

    def test_actual_partial_report_watermark_and_final_snapshot_converge_in_both_orders(self):
        report_5 = self.event("output-5", 5, completion_tokens=40)
        report_7 = self.event("input-7", 7, prompt_tokens=100)
        final = self.event("final", 7, kind="measured", prompt_tokens=100, completion_tokens=50)
        self.consume(report_5, report_7, final)
        self.assertEqual({"7": "0.55"}, self.adapter.totals())
        self.assertEqual(50, self.adapter.snapshot()["objects"][0]["output_quantity"])
        other_db = sqlite3.connect(":memory:")
        try:
            other_db.executescript("CREATE TABLE kv(key TEXT PRIMARY KEY,value TEXT NOT NULL);"
                                  "CREATE TABLE usage(event_id TEXT PRIMARY KEY,tenant TEXT NOT NULL,payload TEXT NOT NULL);")
            reverse = SettlementAdapter(other_db, PRICE, ACCOUNTS)
            self.consume(final, report_7, report_5, adapter=reverse)
            self.assertEqual(self.adapter.totals(), reverse.totals())
            self.assertEqual(50, reverse.snapshot()["objects"][0]["output_quantity"])
            self.consume(self.event("late-report", 7, prompt_tokens=100, completion_tokens=40), adapter=reverse)
            self.assertEqual({"7": "0.55"}, reverse.totals())
            with self.assertRaisesRegex(SettlementError, "USAGE_REVISION_MEASUREMENT_CONFLICT"):
                self.consume(self.event("conflicting-final", 7, kind="measured", completion_tokens=51), adapter=reverse)
        finally:
            other_db.close()

    def test_same_watermark_final_overrides_report_and_late_report_cannot_restore_old_value(self):
        self.consume(self.event("report", 7, prompt_tokens=100, completion_tokens=40))
        self.consume(self.event("final", 7, kind="measured", prompt_tokens=100, completion_tokens=50))
        self.assertEqual({"7": "0.55"}, self.adapter.totals())
        self.consume(self.event("late-report", 7, completion_tokens=40))
        self.assertEqual({"7": "0.55"}, self.adapter.totals())

    def test_same_revision_conflict_rolls_back_whole_page(self):
        self.consume(self.event("first", 1, prompt_tokens=1000))
        before = self.adapter.snapshot()
        with self.assertRaisesRegex(SettlementError, "USAGE_REVISION_MEASUREMENT_CONFLICT"):
            self.consume(self.event("valid", 1, completion_tokens=2000), self.event("conflict", 1, prompt_tokens=999))
        self.assertEqual(before, self.adapter.snapshot())

    def test_frozen_price_survives_configuration_change_but_new_object_uses_new_price(self):
        self.consume(self.event("first", 1, prompt_tokens=1000))
        changed = PRICE | {"revision": "price-2", "input_cents_per_thousand_tokens": "100"}
        adapter = SettlementAdapter(self.db, changed, ACCOUNTS, clock=lambda: self.now[0])
        self.consume(self.event("same-attempt", 2, prompt_tokens=2000), adapter=adapter)
        self.assertEqual({"7": "4"}, adapter.totals())
        self.consume(self.event("new-attempt", 1, attempt_id="attempt-2", prompt_tokens=1000), adapter=adapter)
        self.assertEqual({"7": "104"}, adapter.totals())
        self.assertEqual({"price-1", "price-2"}, {item["pricing_revision"] for item in adapter.snapshot()["objects"]})

    def test_currency_configuration_is_explicit_and_historical_currencies_never_sum(self):
        self.consume(self.event("old-cny", 1, prompt_tokens=1000))
        with self.assertRaisesRegex(SettlementError, "PRICING_ACCOUNT_CURRENCY_CONFLICT"):
            SettlementAdapter(self.db, PRICE | {"currency": "USD"}, ACCOUNTS)
        new_price = PRICE | {"revision": "price-usd", "currency": "USD", "input_cents_per_thousand_tokens": "5"}
        new_accounts = {"7": {"wallet_account_id": "usd-account", "currency": "USD"}}
        changed = SettlementAdapter(self.db, new_price, new_accounts)
        self.consume(self.event("new-usd", 1, attempt_id="usd-attempt", prompt_tokens=1000), adapter=changed)
        objects = changed.snapshot()["objects"]
        self.assertEqual({"CNY", "USD"}, {obj["currency"] for obj in objects})
        self.assertEqual({"price-1", "price-usd"}, {obj["pricing_revision"] for obj in objects})
        with self.assertRaisesRegex(SettlementError, "MIXED_CURRENCY_TOTAL_REQUIRES_DIMENSION"):
            changed.totals()

    def test_tool_has_independent_call_price_and_unpriced_types_remain_gaps(self):
        self.consume(self.event("tool", event_type="tool.call", attempt_id="tool-1", quantity=2, measurement_unit="call", usage_source="UNKNOWN"))
        self.assertEqual({"7": "6"}, self.adapter.totals())
        self.consume(self.event("rerank", event_type="llm.rerank", attempt_id="rerank-1", quantity=4, measurement_unit="document"))
        unpriced = next(obj for obj in self.adapter.snapshot()["objects"] if obj["event_type"] == "llm.rerank")
        self.assertEqual("known", unpriced["measurement_status"])
        self.assertEqual("unpriced", unpriced["pricing_status"])
        self.assertIsNone(unpriced["target_amount"])

    def test_media_usage_is_saved_as_unpriced_calls_without_wallet_instructions(self):
        types = ("media.image", "media.video", "media.transcription", "media.speech")
        for index, event_type in enumerate(types):
            self.consume(self.event("media-" + str(index), event_type=event_type, attempt_id=event_type,
                                    quantity=1, measurement_unit="call", image_count="1" if index == 0 else None,
                                    video_count="1" if index == 1 else None,
                                    characters="18" if index == 3 else None,
                                    duration_seconds="2.080000" if index in (1, 2, 3) else None,
                                    pricing_model_id="863594696908805", pricing_model_revision=1))
        snapshot = self.adapter.snapshot()
        self.assertEqual(set(types), {obj["event_type"] for obj in snapshot["objects"]})
        self.assertTrue(all(obj["measurement_unit"] == "call" and obj["tool_quantity"] == 1
                            and obj["measurement_status"] == "known" and obj["pricing_status"] == "unpriced"
                            and obj["target_amount"] is None for obj in snapshot["objects"]))
        self.assertEqual({}, self.adapter.totals())
        self.assertEqual([], snapshot["instructions"])
        self.assertEqual(0, self.adapter.dispatch(self.wallet)["attempted"])
        self.assertEqual([], self.wallet.sends)
        raw = {json.loads(row[0])["event_type"]: json.loads(row[0])
               for row in self.db.execute("SELECT payload FROM usage")}
        self.assertEqual("2.080000", raw["media.transcription"]["duration_seconds"])
        self.assertIsNone(raw["media.transcription"]["characters"])
        self.assertEqual("18", raw["media.speech"]["characters"])

    def test_media_observations_keep_original_object_and_nullable_receipts_after_cross_view_replay_and_restart(self):
        started = self.event("media-started", event_type="media.speech", attempt_id="media-attempt", quantity=1,
                             measurement_unit="call", usage_source="UNKNOWN", characters=None, duration_seconds=None)
        partial = self.event("media-partial", 1, event_type="media.speech", attempt_id="media-attempt", quantity=1,
                             measurement_unit="call", characters="0", duration_seconds=None)
        terminal = self.event("media-terminal", 2, kind="measured", event_type="media.speech", attempt_id="media-attempt",
                              quantity=1, measurement_unit="call", characters="18", duration_seconds="0.125",
                              pricing_model_id="863594696908805", pricing_model_revision=1)
        self.consume(started, partial, terminal)
        self.consume(terminal | {"cursor": "institution-view"}, stream="institution")
        self.db.close();self.db = self.connect()
        self.adapter = SettlementAdapter(self.db, PRICE | {"revision": "new-tool-price", "tool_cents_per_call": "999"}, ACCOUNTS)
        replay = self.consume(started | {"cursor": "rebuilt-view"}, partial, terminal, stream="rebuilt")
        self.assertEqual(3, replay["duplicates"])
        snapshot = self.adapter.snapshot()
        self.assertEqual(1, len(snapshot["objects"]))
        self.assertEqual("media.speech", snapshot["objects"][0]["event_type"])
        self.assertEqual("unpriced", snapshot["objects"][0]["pricing_status"])
        self.assertIsNone(snapshot["objects"][0]["target_amount"])
        self.assertEqual(3, snapshot["consumed_events"])
        self.assertEqual([], snapshot["instructions"])
        raw = {row[0]: json.loads(row[1]) for row in self.db.execute("SELECT event_id,payload FROM usage")}
        self.assertIsNone(raw["media-started"]["characters"])
        self.assertIsNone(raw["media-partial"]["duration_seconds"])
        self.assertEqual("0", raw["media-partial"]["characters"])
        self.assertEqual("0.125", raw["media-terminal"]["duration_seconds"])
        self.assertEqual("cursor:media-terminal", snapshot["cursors"]["main"])

    def test_media_unpriced_gap_does_not_block_other_events_or_reuse_tool_price(self):
        media = self.event("media", event_type="media.image", attempt_id="media-attempt", quantity=1,
                           measurement_unit="call", image_count="1")
        llm = self.event("llm", 1, attempt_id="llm-attempt", prompt_tokens=1000, completion_tokens=1000)
        tool = self.event("tool", event_type="tool.call", attempt_id="tool-attempt", quantity=2, measurement_unit="call")
        result = self.consume(media, llm, tool)
        self.assertEqual({"inserted": 3, "duplicates": 0, "instructions": 2, "next_cursor": tool["cursor"]}, result)
        self.assertEqual({"7": "15"}, self.adapter.totals())
        self.assertEqual(2, self.adapter.dispatch(self.wallet)["applied"])
        self.assertEqual(2, len(self.wallet.sends))
        media_object = next(obj for obj in self.adapter.snapshot()["objects"] if obj["event_type"] == "media.image")
        self.assertIsNone(media_object["target_amount"])
        self.assertNotIn(media_object["object_id"], {item["object_id"] for item in self.wallet.sends})

    def test_media_attempt_cannot_change_type_and_rolls_back_conflicting_receipt(self):
        self.consume(self.event("image", event_type="media.image", quantity=1, measurement_unit="call", image_count="1"))
        before = self.adapter.snapshot()
        with self.assertRaisesRegex(SettlementError, "ATTEMPT_METERING_TYPE_CONFLICT"):
            self.consume(self.event("speech", 1, event_type="media.speech", quantity=1, measurement_unit="call", characters="18"))
        self.assertEqual(before, self.adapter.snapshot())
        self.assertEqual(1, self.db.execute("SELECT count(*) FROM usage").fetchone()[0])

    def test_media_not_sent_terminal_closes_started_object_without_inventing_zero_usage_after_restart(self):
        started = self.event("media-started", event_type="media.speech", quantity=1, measurement_unit="call",
                             usage_source="UNKNOWN", amount_status="unresolved", image_count=None,
                             video_count=None, characters=None, duration_seconds=None, tokens=None)
        terminal = started | {"event_id": "media-not-sent", "cursor": "cursor:media-not-sent", "kind": "measured",
                              "quantity": 0, "amount_status": "not_applicable"}
        self.consume(started, terminal)
        closed = self.adapter.snapshot()["objects"][0]
        self.assertEqual("not_applicable", closed["pricing_status"])
        self.assertEqual("not_sent", closed["measurement_status"])
        self.assertEqual(0, closed["tool_quantity"])
        self.assertIsNone(closed["target_amount"])
        self.db.close();self.db = self.connect()
        self.adapter = SettlementAdapter(self.db, PRICE | {"revision": "new-price", "tool_cents_per_call": "999"}, ACCOUNTS)
        replay = self.consume(terminal | {"cursor": "other-view"}, started, stream="institution")
        self.assertEqual(2, replay["duplicates"])
        self.consume(started | {"event_id": "late-start", "cursor": "late-start"})
        snapshot = self.adapter.snapshot()
        self.assertEqual(closed, snapshot["objects"][0])
        self.assertEqual([], snapshot["instructions"])
        self.assertEqual(0, self.adapter.dispatch(self.wallet)["attempted"])
        self.assertEqual([], self.wallet.sends)
        raw = {row[0]: json.loads(row[1]) for row in self.db.execute("SELECT event_id,payload FROM usage")}
        self.assertEqual(1, raw["media-started"]["quantity"])
        self.assertEqual(0, raw["media-not-sent"]["quantity"])
        for fact in (raw["media-started"], raw["media-not-sent"]):
            for field in ("image_count", "video_count", "characters", "duration_seconds", "tokens", "prompt_tokens", "completion_tokens"):
                self.assertIsNone(fact[field])

    def test_media_not_sent_final_can_precede_started_report_at_the_same_watermark(self):
        terminal = self.event("not-sent", kind="measured", event_type="media.image", quantity=0,
                              measurement_unit="call", usage_source="UNKNOWN", amount_status="not_applicable")
        self.consume(terminal)
        self.consume(self.event("late-start", event_type="media.image", quantity=1, measurement_unit="call",
                                usage_source="UNKNOWN", amount_status="unresolved"))
        obj = self.adapter.snapshot()["objects"][0]
        self.assertEqual("not_applicable", obj["pricing_status"])
        self.assertEqual("not_sent", obj["measurement_status"])
        self.assertEqual(0, obj["tool_quantity"])
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM usage").fetchone()[0])
        self.assertEqual([], self.adapter.snapshot()["instructions"])
        with self.assertRaisesRegex(SettlementError, "USAGE_REVISION_MEASUREMENT_CONFLICT"):
            self.consume(self.event("conflicting-final", kind="measured", event_type="media.image", quantity=1,
                                    measurement_unit="call", image_count="1", amount_status="unpriced"))
        self.assertEqual(2, self.db.execute("SELECT count(*) FROM usage").fetchone()[0])

    def test_media_reported_zero_is_not_inferred_as_not_sent_and_invalid_not_sent_rolls_back(self):
        self.consume(self.event("reported-zero", event_type="media.image", quantity=1, measurement_unit="call",
                                image_count="0", amount_status="unpriced"))
        before = self.adapter.snapshot()
        self.assertEqual("unpriced", before["objects"][0]["pricing_status"])
        self.assertEqual(1, before["objects"][0]["tool_quantity"])
        with self.assertRaisesRegex(SettlementError, "INVALID_MEDIA_NOT_SENT_FACT"):
            self.consume(self.event("invalid-not-sent", 1, kind="measured", event_type="media.image", quantity=0,
                                    measurement_unit="call", usage_source="UNKNOWN", amount_status="not_applicable",
                                    image_count="0"))
        self.assertEqual(before, self.adapter.snapshot())
        self.assertEqual(1, self.db.execute("SELECT count(*) FROM usage").fetchone()[0])

    def test_immutable_cross_view_replay_ignores_only_cursor_and_keeps_newest_checkpoint(self):
        first = self.event("one", 1, prompt_tokens=1000)
        self.consume(first)
        self.consume(first | {"cursor": "another-view"}, stream="headquarters")
        self.assertEqual(1, len(self.adapter.snapshot()["instructions"]))
        self.consume(self.event("two", 2, prompt_tokens=2000))
        self.adapter.consume_usage("main", {"items": [first], "next_cursor": first["cursor"]})
        self.assertEqual("cursor:two", self.adapter.snapshot()["cursors"]["main"])
        with self.assertRaisesRegex(SettlementError, "IMMUTABLE_USAGE_EVENT_CONFLICT"):
            self.consume(first | {"prompt_tokens": 9}, stream="other")
        with self.assertRaisesRegex(SettlementError, "CHECKPOINT_CONFLICT"):
            self.adapter.consume_usage("main", {"items": [self.event("three")], "next_cursor": "three"}, "stale")

    def test_before_commit_crash_rolls_back_cursor_price_object_and_instruction(self):
        with self.assertRaises(RuntimeError):
            self.consume(self.event("one", 1, prompt_tokens=1000), fault=self.crash_at("before_commit"))
        self.assertEqual(0, self.adapter.snapshot()["consumed_events"])
        self.assertEqual([], self.adapter.snapshot()["objects"])
        self.assertEqual([], self.adapter.snapshot()["instructions"])
        self.assertEqual({}, self.adapter.snapshot()["cursors"])
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM usage").fetchone()[0])

    def test_after_commit_crash_preserves_original_instruction_for_restart(self):
        with self.assertRaises(RuntimeError):
            self.consume(self.event("one", 1, prompt_tokens=1000), fault=self.crash_at("after_commit_before_send"))
        instruction = self.adapter.snapshot()["instructions"][0]["instruction_id"]
        self.db.close();self.db = self.connect()
        self.adapter = SettlementAdapter(self.db, PRICE, ACCOUNTS, clock=lambda: self.now[0])
        self.assertEqual(1, self.adapter.dispatch(self.wallet)["applied"])
        self.assertEqual(instruction, self.wallet.sends[0]["instruction_id"])

    def test_lost_http_receipt_recovers_original_id_after_lease_expiry(self):
        self.consume(self.event("one", 1, prompt_tokens=50000))
        with self.assertRaises(RuntimeError):
            self.adapter.dispatch(self.wallet, fault=self.crash_at("after_http_before_ack"))
        self.consume(self.event("corrected", 2, prompt_tokens=40000))
        self.assertEqual(0, self.adapter.dispatch(self.wallet)["attempted"])
        self.now[0] += 31
        self.assertEqual(2, self.adapter.dispatch(self.wallet)["applied"])
        self.assertEqual(2, len(self.wallet.sends))
        self.assertEqual(["100", "80"], [instruction["target_amount"] for instruction in self.wallet.sends])
        self.assertEqual("80", self.adapter.snapshot()["objects"][0]["confirmed_net_amount"])

    def test_unknown_and_not_found_retry_same_instruction_and_block_later_sequence(self):
        self.consume(self.event("one", 1, prompt_tokens=50000))
        self.consume(self.event("two", 2, prompt_tokens=40000))
        self.wallet.unknown = True
        self.assertEqual(1, self.adapter.dispatch(self.wallet)["unknown"])
        first_id = self.wallet.sends[0]["instruction_id"]
        self.assertEqual(1, self.adapter.dispatch(self.wallet)["unknown"])
        self.assertEqual([first_id, first_id], [item["instruction_id"] for item in self.wallet.sends])
        self.wallet.unknown = False
        self.assertEqual(2, self.adapter.dispatch(self.wallet)["applied"])
        self.assertEqual(first_id, self.wallet.lookups[-1])
        self.assertEqual("80", self.adapter.snapshot()["objects"][0]["confirmed_net_amount"])

    def test_waiting_predecessor_receipt_is_resubmitted_with_original_identity(self):
        self.consume(self.event("one", 1, prompt_tokens=1000))
        original_settle = self.wallet.settle
        ready = [False]
        def waiting(instruction):
            if not ready[0]:
                receipt = {"outcome": "waiting_predecessor", "instruction_id": instruction["instruction_id"]}
                self.wallet.receipts[instruction["instruction_id"]] = receipt
                return receipt
            self.wallet.receipts.pop(instruction["instruction_id"], None)
            return original_settle(instruction)
        self.wallet.settle = waiting
        self.assertEqual(1, self.adapter.dispatch(self.wallet)["waiting"])
        first = self.adapter.snapshot()["instructions"][0]["instruction_id"]
        ready[0] = True
        self.assertEqual(1, self.adapter.dispatch(self.wallet)["applied"])
        self.assertEqual([first], self.wallet.lookups)
        self.assertEqual(first, self.wallet.sends[0]["instruction_id"])

    def test_rejection_keeps_unpaid_gap_and_lower_revision_target_is_new_charge_not_refund(self):
        self.consume(self.event("one", 1, prompt_tokens=50000))
        self.wallet.reject = True
        self.assertEqual(1, self.adapter.dispatch(self.wallet)["rejected"])
        self.assertEqual("100", self.adapter.snapshot()["objects"][0]["settlement_gap"])
        self.assertEqual(0, self.adapter.dispatch(self.wallet)["attempted"])
        self.consume(self.event("two", 2, prompt_tokens=40000))
        self.wallet.reject = False
        self.assertEqual(1, self.adapter.dispatch(self.wallet)["applied"])
        last = self.adapter.snapshot()["instructions"][-1]
        self.assertEqual("80", last["receipt"]["delta"])
        self.assertEqual("0", self.adapter.snapshot()["objects"][0]["settlement_gap"])

    def test_explicit_authorized_reconciliation_is_idempotent_and_uses_latest_target(self):
        self.consume(self.event("one", 1, prompt_tokens=50000))
        self.wallet.reject = True;self.adapter.dispatch(self.wallet)
        obj = self.adapter.snapshot()["objects"][0]
        rejected = self.adapter.snapshot()["instructions"][0]["instruction_id"]
        with self.assertRaisesRegex(SettlementError, "EXPLICIT_RECONCILIATION_AUTHORIZATION_REQUIRED"):
            self.adapter.reconcile_rejection(obj["object_id"], rejected, "")
        instruction = self.adapter.reconcile_rejection(obj["object_id"], rejected, "operator-approved-1")
        self.assertEqual(instruction, self.adapter.reconcile_rejection(obj["object_id"], rejected, "operator-approved-1"))
        self.assertEqual(2, instruction["sequence"])
        self.assertEqual(rejected, instruction["reconciles_rejection_id"])
        self.assertEqual("100", instruction["target_amount"])
        self.wallet.reject = False;self.adapter.dispatch(self.wallet)
        self.assertEqual("0", self.adapter.snapshot()["objects"][0]["settlement_gap"])

    def test_management_reconciliation_replay_precedes_cas_and_audits_full_request(self):
        self.consume(self.event("one", 1, prompt_tokens=50000))
        self.wallet.reject = True;self.adapter.dispatch(self.wallet)
        obj = self.adapter.snapshot()["objects"][0]
        rejected = self.adapter.snapshot()["instructions"][0]["instruction_id"]
        metadata = {"actor": "operator:1", "idempotency_key": "request-1", "request_fingerprint": "body-digest-1",
                    "reason": "approved after account replenishment"}
        instruction = self.adapter.reconcile_rejection(obj["object_id"], rejected, "authorization-1",
                                                       obj["revision"], reconciliation_request=metadata)
        self.assertGreater(self.adapter.snapshot()["objects"][0]["revision"], obj["revision"])
        self.assertEqual(instruction, self.adapter.reconcile_rejection(obj["object_id"], rejected, "authorization-1",
                         obj["revision"], reconciliation_request=metadata))
        record = self.db.execute("SELECT expected_revision,reason,actor,idempotency_key FROM settlement_reconciliations").fetchone()
        self.assertEqual((obj["revision"], metadata["reason"], metadata["actor"], metadata["idempotency_key"]), record)

    def test_management_key_conflicts_on_changed_reason_object_or_revision_before_cas(self):
        self.consume(self.event("one", 1, prompt_tokens=50000))
        self.wallet.reject = True;self.adapter.dispatch(self.wallet)
        obj = self.adapter.snapshot()["objects"][0]
        rejected = self.adapter.snapshot()["instructions"][0]["instruction_id"]
        metadata = {"actor": "operator:1", "idempotency_key": "request-1", "request_fingerprint": "body-digest-1", "reason": "approved"}
        self.adapter.reconcile_rejection(obj["object_id"], rejected, "authorization-1", obj["revision"], reconciliation_request=metadata)
        for object_id, revision, changed in [(obj["object_id"], obj["revision"], metadata | {"reason": "another reason"}),
                                             ("another-object", obj["revision"], metadata),
                                             (obj["object_id"], obj["revision"] + 1, metadata)]:
            with self.subTest(object_id=object_id, revision=revision, reason=changed["reason"]):
                with self.assertRaisesRegex(SettlementError, "RECONCILIATION_IDEMPOTENCY_CONFLICT"):
                    self.adapter.reconcile_rejection(object_id, rejected, "authorization-with-new-digest", revision,
                                                     reconciliation_request=changed)
        self.assertEqual(2, len(self.adapter.snapshot()["instructions"]))

    def test_management_cas_and_incomplete_request_do_not_create_successor(self):
        self.consume(self.event("one", 1, prompt_tokens=50000))
        self.wallet.reject = True;self.adapter.dispatch(self.wallet)
        obj = self.adapter.snapshot()["objects"][0]
        rejected = self.adapter.snapshot()["instructions"][0]["instruction_id"]
        metadata = {"actor": "operator:1", "idempotency_key": "request-1", "request_fingerprint": "body-digest-1", "reason": "approved"}
        with self.assertRaisesRegex(SettlementError, "SETTLEMENT_OBJECT_REVISION_CONFLICT"):
            self.adapter.reconcile_rejection(obj["object_id"], rejected, "authorization-1", obj["revision"] - 1,
                                             reconciliation_request=metadata)
        with self.assertRaisesRegex(SettlementError, "RECONCILIATION_REVISION_AND_REASON_REQUIRED"):
            self.adapter.reconcile_rejection(obj["object_id"], rejected, "authorization-1", reconciliation_request=metadata)
        self.assertEqual(1, len(self.adapter.snapshot()["instructions"]))
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM settlement_reconciliations").fetchone()[0])

    def test_persistent_lease_serializes_workers_and_crash_only_recovers_same_identity(self):
        self.consume(self.event("one", 1, prompt_tokens=1000))
        self.consume(self.event("two", 2, prompt_tokens=2000))
        first = self.adapter._claim("worker-1", set())
        other = self.connect()
        try:
            adapter = SettlementAdapter(other, PRICE, ACCOUNTS, clock=lambda: self.now[0])
            self.assertIsNone(adapter._claim("worker-2", set()))
            self.now[0] += 31
            second = adapter._claim("worker-2", set())
            self.assertEqual(first[0], second[0])
            with self.assertRaisesRegex(SettlementError, "DISPATCH_LEASE_LOST"):
                self.adapter._finish(first[0], first[1], None)
        finally:
            other.close()

    def test_two_consumers_share_one_authoritative_object_and_sequence_chain(self):
        barrier = threading.Barrier(2)
        failures = []
        def consume(index):
            db = self.connect()
            try:
                adapter = SettlementAdapter(db, PRICE, ACCOUNTS)
                barrier.wait(timeout=5)
                adapter.consume_usage(str(index), {"items": [self.event("same", 1, prompt_tokens=1000)], "next_cursor": str(index)})
            except BaseException as error:
                failures.append(error)
            finally:
                db.close()
        workers = [threading.Thread(target=consume, args=(index,)) for index in range(2)]
        for worker in workers: worker.start()
        for worker in workers: worker.join(timeout=10)
        self.assertFalse(any(worker.is_alive() for worker in workers))
        self.assertEqual([], failures)
        self.assertEqual(1, self.adapter.snapshot()["consumed_events"])
        self.assertEqual(1, len(self.adapter.snapshot()["instructions"]))

    def test_missing_attempt_cannot_be_replaced_by_new_run_identity(self):
        with self.assertRaisesRegex(SettlementError, "ORIGINAL_ATTEMPT_REQUIRED"):
            self.consume(self.event("one", attempt_id=None, run_id="new-run", prompt_tokens=1000))
        self.assertEqual(0, self.adapter.snapshot()["consumed_events"])

    def test_sub_cent_accumulation_uses_cumulative_amount_and_explicit_zero_is_known(self):
        price = PRICE | {"input_cents_per_thousand_tokens": "0.000000000001", "output_cents_per_thousand_tokens": "0"}
        adapter = SettlementAdapter(self.db, price, ACCOUNTS)
        self.consume(self.event("one", 1, prompt_tokens=500, completion_tokens=0), adapter=adapter)
        self.assertEqual({"7": "0.000000000001"}, adapter.totals())
        self.consume(self.event("two", 2, prompt_tokens=1000, completion_tokens=0), adapter=adapter)
        self.assertEqual(1, len(adapter.snapshot()["instructions"]))


if __name__ == "__main__":
    unittest.main()
