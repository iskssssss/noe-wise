"""Standalone supplemental oracle for frozen Core public final replies.

No Core imports, requests, subprocesses, original-oracle writes, or hidden model-input
access. Exact frozen common-PROTOCOL binding is required before checking replies.
"""
from pathlib import Path
import argparse
import ast
import hashlib
import json
import math
import re
from datetime import datetime, timezone

SCHEMA = 'supplemental-core-whole-final-format/v1'
CORE_SUITE = 'chat-tools-7'
FIELDS = {'status', 'value', 'url', 'message'}
STATUSES = {'success', 'unavailable', 'denied'}

def sha_bytes(data):
    return hashlib.sha256(data).hexdigest()

def sha(path):
    return sha_bytes(path.read_bytes())

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def protocol_from_source(path):
    tree = ast.parse(path.read_text(encoding='utf-8-sig'))
    found = [ast.literal_eval(node.value) for node in tree.body
             if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'PROTOCOL' for t in node.targets)]
    if len(found) != 1 or not isinstance(found[0], str):
        raise ValueError('common_protocol_source_unavailable')
    return found[0]

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate_json_key')
        result[key] = value
    return result

def finite_number(text):
    value = float(text)
    if not math.isfinite(value):
        raise ValueError('nonfinite_json_number')
    return value

def invalid_constant(text):
    raise ValueError('non_json_constant')

def whole_message(text):
    """Align with the existing consumer: raw object or one whole-message JSON fence.

    Checks only mechanical reply format. value accepts every lawful JSON type;
    original-value fidelity and status correctness remain separate business checks.
    """
    if not isinstance(text, str):
        return {'status': 'FAIL', 'violations': ['final_content_not_text']}
    source = text.strip()
    fence = re.fullmatch(r'```(?:json)?[ \t]*\r?\n(.*?)\r?\n```', source, re.DOTALL)
    if fence:
        source = fence.group(1)
    try:
        value = json.loads(source, object_pairs_hook=unique_object,
                           parse_float=finite_number, parse_constant=invalid_constant)
    except (ValueError, TypeError, OverflowError) as error:
        named = str(error) if str(error) in {'duplicate_json_key', 'nonfinite_json_number', 'non_json_constant'} else 'whole_message_not_single_json_object'
        return {'status': 'FAIL', 'violations': [named]}
    violations = []
    if not isinstance(value, dict):
        violations.append('top_level_not_object')
    else:
        if set(value) != FIELDS:
            violations.append('fields_not_exactly_status_value_url_message')
        if not isinstance(value.get('status'), str) or value.get('status') not in STATUSES:
            violations.append('status_not_allowed_string')
        if 'url' in value and value['url'] is not None and not isinstance(value['url'], str):
            violations.append('url_not_string_or_null')
        if not isinstance(value.get('message'), str):
            violations.append('message_not_string')
    return {'status': 'FAIL' if violations else 'PASS', 'violations': violations}

def evaluate_lane(root, lane, protocol):
    plan_path = lane / 'plan.json'
    plan = read(plan_path)
    batch = read(lane / 'batch.json') if (lane / 'batch.json').exists() else {}
    profiles = {p['key']: p for p in plan.get('profiles', [])}
    selection = plan.get('local_selection') or {}
    ids = set(selection.get('case_ids') or [c['id'] for c in plan['cases']])
    repetitions = selection.get('repetitions', plan['repetitions'])
    rows = []
    for case in plan['cases']:
        if case['id'] not in ids:
            continue
        profile = profiles.get(case.get('profile'), {})
        applies = (plan.get('suite_version') == CORE_SUITE and case.get('profile') in {'behavior', 'readonly'}
                   and profile.get('prompt', '').startswith(protocol))
        binding = {'frozen_plan_sha256': sha(plan_path), 'plan_fingerprint': plan.get('fingerprint'),
                   'profile_key': case.get('profile'), 'frozen_profile_prompt_sha256': sha_bytes(profile.get('prompt', '').encode()),
                   'common_protocol_sha256': sha_bytes(protocol.encode()),
                   'meaning': 'Exact supplied frozen-plan prompt prefix binding; does not retroactively prove deployed binary or start-time source identity.'}
        for repetition in range(1, repetitions + 1):
            folder = lane / 'cases' / case['id'] / str(repetition)
            evidence_path, evaluation_path = folder / 'evidence.json', folder / 'evaluation.json'
            row = {'lane': lane.relative_to(root).as_posix(), 'batch': plan['batch'], 'case_id': case['id'],
                   'repetition': repetition, 'profile_binding': binding, 'dimension': 'whole_public_final_format'}
            if not applies:
                rows.append({**row, 'status': 'NOT_APPLICABLE', 'reason': 'not_exact_core_common_protocol'})
                continue
            if not evidence_path.exists() or not evaluation_path.exists():
                rows.append({**row, 'status': 'NOT_REVIEWED', 'reason': 'missing_frozen_evidence_or_original_evaluation',
                             'batch_lifecycle': batch.get('lifecycle')})
                continue
            before = sha(evidence_path)
            evidence = read(evidence_path)
            evaluation = read(evaluation_path)
            complete = evidence.get('completeness') or {}
            terminal = evidence.get('terminal') or {}
            row.update({'evidence_path': evidence_path.relative_to(root).as_posix(), 'evidence_sha256': before,
                        'original_evaluation_path': evaluation_path.relative_to(root).as_posix(),
                        'original_evaluation_sha256': sha(evaluation_path),
                        'original_behavior_status_unchanged': evaluation.get('behavior_status')})
            if not evidence.get('frozen_at') or terminal.get('durability') != 'persistent' or evidence.get('blocking_reasons') or not all(
                    complete.get(k) is True for k in ('trace', 'audit', 'terminal', 'context', 'quiescence')):
                rows.append({**row, 'status': 'NOT_REVIEWED', 'reason': 'not_complete_persistent_frozen_evidence'})
                continue
            final = [e for e in evidence.get('observable_output', {}).get('visible_events', [])
                     if e.get('event') == 'message.delta' and e.get('data', {}).get('phase') == 'final'
                     and e.get('data', {}).get('state') == 'completed' and e.get('data', {}).get('durability') == 'persistent']
            assessed = []
            seen = set()
            for event in final:
                data = event['data']
                identity = (data.get('message_id'), data.get('input_id'), data.get('run_id'),
                            json.dumps(data.get('content'), sort_keys=True, ensure_ascii=False))
                if identity in seen:
                    continue
                seen.add(identity)
                result = whole_message(data.get('content'))
                assessed.append({**result, 'message_id': data.get('message_id'), 'run_id': data.get('run_id'),
                                 'input_id': data.get('input_id'), 'public_event_id': data.get('event_id', event.get('id')),
                                 'public_text_sha256': sha_bytes(str(data.get('content')).encode())})
            if sha(evidence_path) != before:
                rows.append({**row, 'status': 'NOT_REVIEWED', 'reason': 'evidence_changed_while_reading'})
            elif not assessed:
                rows.append({**row, 'status': 'NOT_REVIEWED', 'reason': 'no_persistent_completed_public_final',
                             'terminal_status': terminal.get('status'), 'stop_on_unknown': case.get('stop_on_unknown', False)})
            else:
                rows.append({**row, 'status': 'FAIL' if any(r['status'] == 'FAIL' for r in assessed) else 'PASS',
                             'observed_public_final_count': len(assessed), 'finals': assessed,
                             'scope': 'Every observed persistent completed public final is assessed; absent finals are never inferred from hidden model output or parsed_answer.'})
    return rows

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument('--ledger', type=Path)
    selection.add_argument('--lane', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    protocol_source = root / 'tools/chat_regression/behavior_suite.py'
    protocol = protocol_from_source(protocol_source)
    lanes = [root / p for p in args.lane]
    ledger_binding = None
    if not lanes:
        ledger = (args.ledger if args.ledger.is_absolute() else root / args.ledger).resolve()
        registry = read(ledger)
        reservations = registry['reservations']
        values = reservations.values() if isinstance(reservations, dict) else reservations
        lanes = [Path(v.get('active_evidence_root') or v['evidence_root']) for v in values if v.get('suite') == 'core']
        lanes = [p if p.is_absolute() else root / p for p in lanes]
        ledger_binding = {'path': str(ledger), 'sha256': sha(ledger)}
    lanes = list(dict.fromkeys(lane.resolve() for lane in lanes))
    rows = [row for lane in lanes for row in evaluate_lane(root, lane, protocol)]
    counts = {status: sum(r['status'] == status for r in rows) for status in ('PASS', 'FAIL', 'NOT_REVIEWED', 'NOT_APPLICABLE')}
    report = {'schema': SCHEMA, 'generated_at': datetime.now(timezone.utc).isoformat(),
              'oracle_source_sha256': sha(Path(__file__)), 'protocol_source_path': protocol_source.relative_to(root).as_posix(),
              'protocol_source_sha256_at_review': sha(protocol_source), 'common_protocol_sha256': sha_bytes(protocol.encode()),
              'public_prompt_basis_excerpt': '每轮回答一个JSON对象，字段为status、value、url、message；status取success、unavailable、denied，value保留原始JSON类型，url缺失为null。',
              'application_boundary': 'Standalone supplemental format only; no Core imports, original oracle edits, model calls, production actions, process verdicts, semantic explanation judgments, or deployed-identity proof.',
              'original_automatic_and_manual_oracles_unchanged': True, 'paid_calls': 0, 'api_requests': 0,
              'ledger_binding': ledger_binding, 'counts': counts, 'rows': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise ValueError('supplemental_report_path_already_exists')
    with args.output.open('x', encoding='utf-8') as output:
        output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'output': str(args.output), 'counts': counts}, ensure_ascii=False))

if __name__ == '__main__':
    main()
