"""Distinct diagnostic fixture names under one unchanged budget reservation."""
from __future__ import annotations

from pathlib import Path
import hashlib
import re


def fixture_namespace(receipt):
    batch = receipt.get("batch_id")
    sequence = receipt.get("diagnostic_sequence", 0)
    if not isinstance(batch, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,47}", batch) or type(sequence) is not int or sequence < 0:
        raise ValueError("diagnostic_fixture_namespace_identity_invalid")
    if sequence:
        original = Path(receipt["evidence_root"]).resolve()
        active = Path(receipt["active_evidence_root"]).resolve()
        if active.parent != original or active.name != f"diagnostic-{sequence}":
            raise ValueError("diagnostic_fixture_namespace_child_identity_invalid")
    fixture_batch = batch
    if sequence:
        suffix = f"-diagnostic-{sequence}"
        if len(suffix) > 35:
            raise ValueError("diagnostic_fixture_namespace_sequence_too_long")
        fixture_batch = batch + suffix
        if len(fixture_batch) > 48:
            fixture_batch = batch[:48 - len(suffix) - 9].rstrip("-") + "-" + hashlib.sha256(batch.encode()).hexdigest()[:8] + suffix
    return {"reservation_id": receipt["reservation_id"], "canonical_budget_batch_id": batch,
            "diagnostic_sequence": sequence, "fixture_batch_id": fixture_batch,
            "budget_caps_and_identity_unchanged": True, "new_budget_reservation": False,
            "existing_resource_reuse": False}
