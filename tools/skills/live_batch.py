"""真实模型验收的显式批次与预算预留守卫；不签发预留、不重放未知调用。"""
from __future__ import annotations

from datetime import datetime, timezone
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
from urllib.parse import urlencode
import uuid


def add_arguments(parser):
    parser.add_argument('--batch-id', default=os.environ.get('FULL_TEST_BATCH'))
    parser.add_argument('--evidence-root', type=Path, default=os.environ.get('SKILL_LIVE_EVIDENCE'))
    parser.add_argument('--packages-report', type=Path, default=os.environ.get('SKILL_LIVE_PACKAGES_REPORT'))


def configure(args, profile):
    batch = args.batch_id
    if not isinstance(batch, str) or not re.fullmatch(r'[a-z0-9][a-z0-9_.-]{2,70}', batch):
        raise RuntimeError('EXPLICIT_LIVE_BATCH_REQUIRED')
    if os.environ.get('FULL_TEST_BATCH') not in (None, batch):
        raise RuntimeError('LIVE_BATCH_ENVIRONMENT_MISMATCH')
    if not args.evidence_root or not args.packages_report:
        raise RuntimeError('EXPLICIT_EVIDENCE_AND_PUBLISHED_PACKAGE_REPORT_REQUIRED')
    evidence = Path(args.evidence_root).resolve()
    packages = Path(args.packages_report).resolve(strict=True)
    if batch not in evidence.parts or batch not in packages.parts or not packages.is_file():
        raise RuntimeError('BATCH_OWNED_EVIDENCE_AND_PACKAGE_PATHS_REQUIRED')
    scope = {'schema_version': 1, 'batch_id': batch, 'profile': profile,
             'evidence_root': str(evidence), 'packages_report': str(packages),
             'agent_key': 'skill-live-' + batch + '-' + profile}
    evidence.mkdir(parents=True, exist_ok=True)
    path = evidence / 'batch.json'
    if path.exists() and json.loads(path.read_text(encoding='utf-8')) != scope:
        raise RuntimeError('EXISTING_LIVE_BATCH_SCOPE_CHANGED')
    if not path.exists():
        _save(path, scope)
    return scope


def _save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def _natural(value):
    if isinstance(value, bool):
        raise ValueError('boolean is not usage')
    if isinstance(value, int) and value >= 0:
        return value
    if isinstance(value, str) and value.isascii() and value.isdigit():
        return int(value)
    raise ValueError('authoritative nonnegative usage required')


class BudgetReservation:
    def __init__(self, scope):
        self.scope = scope
        supplied = os.environ.get('LIVE_BUDGET_RESERVATION')
        if not supplied:
            raise RuntimeError('SUPERVISOR_BUDGET_RESERVATION_REQUIRED')
        self.path = Path(supplied).resolve(strict=True)
        self.observations = self.path.with_name(self.path.stem + '-observations.json')
        self.validate(query_only=True)

    def validate(self, query_only=False):
        receipt = json.loads(self.path.read_text(encoding='utf-8-sig'))
        if (receipt.get('schema_version') != 1 or receipt.get('suite') != 'skills'
                or receipt.get('status') != 'RESERVED' or receipt.get('usage_known') is not True
                or receipt.get('batch_id') != self.scope['batch_id']
                or not isinstance(receipt.get('evidence_root'), str) or not Path(receipt['evidence_root']).is_absolute()
                or not Path(self.scope['evidence_root']).is_relative_to(Path(receipt.get('evidence_root', '')).resolve())):
            raise RuntimeError('BUDGET_RESERVATION_SCOPE_OR_USAGE_UNKNOWN')
        supervisor_run = receipt.get('run_id')
        if (not isinstance(supervisor_run, str) or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{2,127}', supervisor_run)
                or os.environ.get('FULL_TEST_RUN_ID') not in (None, supervisor_run)):
            raise RuntimeError('BUDGET_SUPERVISOR_RUN_MISMATCH')
        try:
            uuid.UUID(receipt['reservation_id'])
            round_number = _natural(receipt['round'])
            limits = {key: _natural(receipt[key]) for key in ('max_input_turns', 'max_tokens', 'max_seconds')}
            issued = datetime.fromisoformat(receipt['issued_at'].replace('Z', '+00:00'))
            if issued.tzinfo is None:
                raise ValueError('timezone required')
        except (KeyError, TypeError, ValueError):
            raise RuntimeError('INVALID_SUPERVISOR_BUDGET_RESERVATION') from None
        if (round_number not in (1, 2, 3) or not 0 < limits['max_input_turns'] <= 60
                or not 0 < limits['max_tokens'] <= 2000000 or not 0 < limits['max_seconds'] <= 10800):
            raise RuntimeError('SUPERVISOR_RESERVATION_EXCEEDS_ROUND_CAP')
        elapsed = (datetime.now(timezone.utc) - issued).total_seconds()
        if elapsed < -30 or elapsed >= limits['max_seconds'] and not query_only:
            raise RuntimeError('SUPERVISOR_BUDGET_TIME_EXHAUSTED')
        ledger_path = receipt.get('ledger_path')
        if not isinstance(ledger_path, str) or not Path(ledger_path).is_absolute():
            raise RuntimeError('EXPLICIT_SUPERVISOR_LEDGER_REQUIRED')
        ledger = json.loads(Path(ledger_path).read_text(encoding='utf-8'))
        owned = [row for row in ledger.get('reservations', []) if row.get('reservation_id') == receipt['reservation_id']]
        identities = [(row.get('suite'), row.get('round')) for row in ledger.get('reservations', [])]
        if (ledger.get('schema_version') != 1 or ledger.get('run_id') != supervisor_run or ledger.get('rounds') != 3
                or len(identities) != len(set(identities)) or len(owned) != 1
                or any(owned[0].get(key) != receipt.get(key) for key in ('run_id', 'suite', 'round', 'batch_id',
                    'evidence_root', 'issued_at', 'max_input_turns', 'max_tokens', 'max_seconds'))):
            raise RuntimeError('RESERVATION_NOT_REGISTERED_IN_CUMULATIVE_SUPERVISOR_LEDGER')
        if not query_only and (ledger.get('stop_new_dispatch') is not False or owned[0].get('status') != 'RESERVED'):
            raise RuntimeError('SUPERVISOR_STOPPED_NEW_DISPATCH_QUERY_ONLY')
        self.receipt = receipt
        return limits

    @contextmanager
    def _lock(self):
        path = self.observations.with_suffix('.lock')
        try:
            handle = path.open('x', encoding='utf-8')
        except FileExistsError:
            raise RuntimeError('LIVE_BUDGET_OBSERVATION_LOCKED_NO_PARALLEL_DISPATCH') from None
        try:
            with handle:
                handle.write(str(os.getpid()))
                handle.flush()
                yield
        finally:
            path.unlink()

    def _state(self):
        if not self.observations.exists():
            return {'schema_version': 1, 'reservation_id': self.receipt['reservation_id'],
                    'batch_id': self.scope['batch_id'], 'dispatches': {}}
        state = json.loads(self.observations.read_text(encoding='utf-8'))
        if state.get('reservation_id') != self.receipt['reservation_id'] or state.get('batch_id') != self.scope['batch_id']:
            raise RuntimeError('BUDGET_OBSERVATIONS_DIFFER_FROM_RESERVATION')
        return state

    def before_chat(self, body, client):
        with self._lock():
            self._before_chat(body, client)

    def _before_chat(self, body, client):
        limits = self.validate()
        self._settle(client)
        state = self._state()
        dispatches = state['dispatches']
        message = body.get('client_message_id')
        if not isinstance(message, str) or not message:
            raise RuntimeError('STABLE_CHAT_INPUT_ID_REQUIRED')
        if message in dispatches:
            raise RuntimeError('EXISTING_CHAT_DISPATCH_QUERY_ONLY_NO_POST_REPLAY')
        if any(item['state'] != 'settled' or item.get('usage_known') is not True for item in dispatches.values()):
            raise RuntimeError('PRIOR_DISPATCH_OR_USAGE_UNRESOLVED_QUERY_ONLY')
        tokens = sum(item['observed_tokens'] for item in dispatches.values())
        if len(dispatches) >= limits['max_input_turns'] or tokens >= limits['max_tokens']:
            raise RuntimeError('SUPERVISOR_ROUND_BUDGET_EXHAUSTED')
        dispatches[message] = {'state': 'unknown', 'usage_known': False, 'session_id': body['session_id'],
                              'agent_id': body['agent_id'], 'submitted_at': datetime.now(timezone.utc).isoformat()}
        _save(self.observations, state)

    def after_chat(self, body, response):
        with self._lock():
            self._after_chat(body, response)

    def _after_chat(self, body, response):
        state = self._state()
        item = state['dispatches'][body['client_message_id']]
        if not isinstance(response, dict) or not isinstance(response.get('execution_id'), str) or not response['execution_id']:
            raise RuntimeError('CHAT_ACCEPTANCE_UNKNOWN_QUERY_ORIGINAL_INPUT')
        item.update(state='accepted', run_id=response['execution_id'])
        _save(self.observations, state)

    def settle(self, client):
        self.validate(query_only=True)
        with self._lock():
            self._settle(client)

    def _settle(self, client):
        """按原Run读取状态和真实provider用量；无流水或estimated/unknown不按0处理。"""
        state = self._state()
        for item in state['dispatches'].values():
            if item['state'] == 'settled' or not item.get('run_id'):
                continue
            status = client.api('institution', '/chat/runs/status?' + urlencode(
                {'session_id': item['session_id'], 'run_id': item['run_id']}))
            item['observed_status'] = status.get('status')
            if status.get('status') not in ('completed', 'failed', 'cancelled'):
                continue
            records, total = [], None
            try:
                for page in range(1, 51):
                    result = client.api('institution', '/billing/records?' + urlencode(
                        {'runId': item['run_id'], 'page': page, 'pageSize': 200}))
                    if total is None:
                        total = _natural(result.get('total'))
                    elif total != _natural(result.get('total')):
                        raise ValueError('changing usage page total')
                    records.extend(result.get('items', []))
                    if len(records) >= total:
                        break
                else:
                    raise ValueError('usage pagination bound exceeded')
                if len(records) != total or len({row.get('event_id') for row in records}) != len(records):
                    raise ValueError('incomplete or duplicate usage pages')
                measured = [row for row in records if row.get('event_type') == 'llm.chat'
                            and row.get('usage_event_kind') == 'measured']
                if not measured or len({row.get('event_id') for row in measured}) != len(measured):
                    raise ValueError('absent or duplicate measured provider usage')
                if any(row.get('run_id') != item['run_id'] or row.get('usage_source') != 'PROVIDER_REPORTED' for row in measured):
                    raise ValueError('usage owner or source unknown')
                tokens = sum(_natural(row.get('tokens')) for row in measured)
                item.update(state='settled', usage_known=True, observed_tokens=tokens,
                            measured_event_ids=[row['event_id'] for row in measured],
                            settled_at=datetime.now(timezone.utc).isoformat())
            except (KeyError, TypeError, ValueError):
                item.update(usage_known=False, usage_observation='UNKNOWN_OR_INCOMPLETE_PROVIDER_MEASUREMENT')
        state['observed_input_turns'] = len(state['dispatches'])
        state['observed_tokens'] = sum(item.get('observed_tokens', 0) for item in state['dispatches'].values())
        state['observed_elapsed_seconds'] = max(0, (datetime.now(timezone.utc) - datetime.fromisoformat(
            self.receipt['issued_at'].replace('Z', '+00:00'))).total_seconds())
        state['usage_known'] = all(item.get('usage_known') is True for item in state['dispatches'].values())
        _save(self.observations, state)
