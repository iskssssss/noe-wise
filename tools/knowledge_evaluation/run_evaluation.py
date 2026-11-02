"""冻结固定知识评估输入，导入真实工程证据与人工语义复核；不发起网络或模型调用。"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, fields
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SUITE = Path(__file__).with_name('suite.json')
OUTCOMES = ('PASS', 'FAIL', 'UNKNOWN', 'NOT_RUN')


@dataclass(frozen=True)
class Document:
    tenant_id: str
    knowledge_handle: str
    source_id: str
    document_id: str
    source_version: str
    title: str
    text: str
    mounted: bool


@dataclass(frozen=True)
class Case:
    id: str
    category: str
    prompt: str
    document_ids: list[str]
    expected_answer_facts: list[str]
    semantic_rubric: str
    contract_tests: list[str]


@dataclass(frozen=True)
class FrozenIdentity:
    status: str = 'NOT_RUN'
    tenant_id: str | None = None
    agent_id: str | None = None
    model_id: str | None = None
    model_revision: str | None = None
    provider: str | None = None
    wire_model: str | None = None
    reasoning_effort: str | None = None
    max_model_calls: int | None = None
    max_total_tokens: int | None = None
    max_duration_seconds: int | None = None
    tool_schema_sha256: str | None = None
    source_snapshot_sha256: str | None = None


@dataclass(frozen=True)
class ModelRun:
    case_id: str
    execution_kind: str
    run_id: str
    runtime_status: str
    identity_sha256: str
    trace_path: str
    trace_sha256: str
    answer_path: str
    answer_sha256: str
    provider_receipt_path: str
    provider_receipt_sha256: str


@dataclass(frozen=True)
class SemanticReview:
    case_id: str
    run_id: str
    suite_sha256: str
    identity_sha256: str
    answer_sha256: str
    outcome: str
    reviewer: str
    reviewed_at: str
    reason: str
    evidence_refs: list[str]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def record(kind, value):
    require(isinstance(value, dict), 'typed_record_required')
    require(set(value) <= {field.name for field in fields(kind)}, 'unknown_record_field')
    try:
        return kind(**value)
    except TypeError as error:
        raise ValueError('required_record_field_missing') from error


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def object_sha(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(',', ':')).encode()).hexdigest()


def save(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def timestamp(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None, 'timezone_required')
    return result


def load_suite(path):
    value = read(path)
    require(value.get('schema_version') == 1 and value.get('suite_id') == 'knowledge-q06-v1', 'suite_identity_invalid')
    documents = [record(Document, item) for item in value['documents']]
    cases = [record(Case, item) for item in value['cases']]
    ids = [document.document_id for document in documents]
    require(len(ids) == len(set(ids)) == 26, 'fixed_document_set_invalid')
    require([case.id for case in cases] == [f'K{i:02}' for i in range(1, 9)], 'fixed_case_matrix_invalid')
    oracle = value['oracles']
    mounted = [item for item in documents if item.mounted and item.tenant_id == oracle['catalog_tenant_id']]
    require(len(mounted) == 24 and [item.document_id for item in mounted] == oracle['mounted_document_ids'], 'catalog_oracle_invalid')
    filtered = [item.document_id for item in mounted if oracle['title_filter'].casefold() in item.title.casefold()]
    require(filtered == oracle['title_filtered_document_ids'], 'title_filter_oracle_invalid')
    long = next(item for item in documents if item.document_id == oracle['long_document_id'])
    require(len(long.text) == oracle['long_total_characters'] == 25600, 'long_document_oracle_invalid')
    require([long.text[index] for index in oracle['non_bmp_offsets']] == ['🚀', '𠮷'], 'unicode_codepoint_oracle_invalid')
    require(oracle['long_end_marker'] in long.text[-100:], 'long_document_end_marker_missing')
    require(set(oracle['denied_document_ids']).isdisjoint(oracle['mounted_document_ids']), 'denied_document_in_catalog')
    for case in cases:
        require(set(case.document_ids) <= set(ids) and case.prompt and case.semantic_rubric, 'case_oracle_invalid')
        require(all(isinstance(item, str) and '#' in item for item in case.contract_tests), 'exact_junit_selector_required')
    return value, documents, cases


def validate_identity(value):
    require(value.status in ('FROZEN', 'NOT_RUN'), 'identity_status_invalid')
    if value.status == 'FROZEN':
        for key in ('tenant_id', 'agent_id', 'model_id', 'model_revision', 'provider', 'wire_model', 'reasoning_effort'):
            require(isinstance(getattr(value, key), str) and getattr(value, key).strip(), 'frozen_identity_missing_' + key)
        for key in ('max_model_calls', 'max_total_tokens', 'max_duration_seconds'):
            require(type(getattr(value, key)) is int and getattr(value, key) > 0, 'frozen_budget_missing_' + key)
        for key in ('tool_schema_sha256', 'source_snapshot_sha256'):
            require(re.fullmatch('[0-9a-f]{64}', getattr(value, key) or '') is not None, 'frozen_digest_missing_' + key)
    else:
        require(all(getattr(value, field.name) is None for field in fields(value) if field.name != 'status'),
                'not_run_identity_must_not_invent_configuration')
    return value


def identity(path=None):
    return validate_identity(record(FrozenIdentity, read(path)) if path else FrozenIdentity())


def prepare(suite_path, output, identity_path=None):
    suite, documents, _ = load_suite(suite_path)
    frozen_identity = asdict(identity(identity_path))
    output.mkdir(parents=True, exist_ok=False)
    (output / 'suite.json').write_bytes(suite_path.read_bytes())
    manifest = {'schema_version': 1, 'suite_id': suite['suite_id'], 'suite_sha256': sha(suite_path),
                'prepared_at': datetime.now(timezone.utc).isoformat(), 'identity': frozen_identity,
                'identity_sha256': object_sha(frozen_identity),
                'document_sha256': {item.document_id: hashlib.sha256(item.text.encode()).hexdigest() for item in documents},
                'network_calls_dispatched_by_this_tool': 0}
    save(output / 'frozen.json', manifest)
    write_report(output / 'report-initial', assess(output))
    return manifest


def junit_evidence(paths, not_before):
    if paths:
        require(not_before is not None, 'junit_not_before_required')
    earliest = timestamp(not_before).timestamp() if not_before else None
    tests, files = {}, []
    for path in paths:
        require(path.is_file() and path.stat().st_mtime >= earliest, 'junit_missing_or_older_than_window')
        root = ET.parse(path).getroot()
        require(root.tag in ('testsuite', 'testsuites'), 'junit_xml_required')
        files.append({'path': str(path.resolve()), 'sha256': sha(path)})
        for test in root.iter('testcase'):
            selector = test.get('classname', '') + '#' + test.get('name', '')
            require(selector not in tests, 'duplicate_junit_case')
            outcome = 'FAIL' if test.find('failure') is not None or test.find('error') is not None else 'UNKNOWN' if test.find('skipped') is not None else 'PASS'
            tests[selector] = {'outcome': outcome, 'file_sha256': files[-1]['sha256']}
    return tests, files


def artifact(root, relative, expected):
    require(isinstance(relative, str) and relative and not Path(relative).is_absolute(), 'relative_evidence_path_required')
    path = (root / relative).resolve()
    require(path.is_relative_to(root.resolve()) and path.is_file(), 'evidence_path_escape_or_missing')
    require(sha(path) == expected, 'evidence_digest_mismatch')
    return path


def load_runs(path, manifest, valid_cases):
    if path is None:
        return {}
    value = read(path)
    require(value['suite_sha256'] == manifest['suite_sha256'], 'model_suite_binding_mismatch')
    require(manifest['identity']['status'] == 'FROZEN', 'real_model_requires_frozen_identity')
    runs = {}
    for row in value['runs']:
        run = record(ModelRun, row)
        require(run.case_id in valid_cases and run.case_id not in runs, 'unknown_or_duplicate_model_case')
        require(run.execution_kind == 'REAL_MODEL' and run.run_id, 'mock_is_not_real_model_evidence')
        require(run.identity_sha256 == manifest['identity_sha256'], 'model_identity_binding_mismatch')
        trace = read(artifact(path.parent, run.trace_path, run.trace_sha256))
        artifact(path.parent, run.answer_path, run.answer_sha256)
        artifact(path.parent, run.provider_receipt_path, run.provider_receipt_sha256)
        # 复用既有会话观测指标；调用/用量缺失不能补零或用回答词面猜测。
        spec = importlib.util.spec_from_file_location('knowledge_conversation_metrics', ROOT / 'tools/runtime/conversation_eval.py')
        module = importlib.util.module_from_spec(spec)
        import sys
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        metrics = module.Observation.from_trace(trace).metrics()
        require(metrics['model_calls']['value'] > 0, 'real_model_call_evidence_missing')
        runs[run.case_id] = (run, metrics)
    return runs


def load_reviews(path, manifest, runs):
    if path is None:
        return {}
    reviews = {}
    for row in read(path)['reviews']:
        review = record(SemanticReview, row)
        require(review.case_id in runs and review.case_id not in reviews, 'review_requires_observed_case')
        run, _ = runs[review.case_id]
        require(review.run_id == run.run_id and review.answer_sha256 == run.answer_sha256
                and review.suite_sha256 == manifest['suite_sha256']
                and review.identity_sha256 == manifest['identity_sha256'], 'review_binding_mismatch')
        require(review.outcome in ('PASS', 'FAIL', 'UNKNOWN'), 'semantic_review_outcome_invalid')
        require(run.runtime_status == 'completed', 'semantic_review_requires_completed_run')
        if review.outcome != 'UNKNOWN':
            require(review.reviewer.strip() and review.reason.strip() and review.evidence_refs, 'semantic_review_evidence_missing')
            timestamp(review.reviewed_at)
            require(all(ref in (run.answer_path, run.trace_path, run.provider_receipt_path) for ref in review.evidence_refs),
                    'review_must_reference_bound_evidence')
        reviews[review.case_id] = review
    return reviews


def assess(batch, junit=(), not_before=None, runs_path=None, review_path=None):
    manifest = read(batch / 'frozen.json')
    require(sha(batch / 'suite.json') == manifest['suite_sha256'], 'frozen_suite_changed')
    require(object_sha(manifest['identity']) == manifest['identity_sha256'], 'frozen_identity_changed')
    validate_identity(record(FrozenIdentity, manifest['identity']))
    _, documents, cases = load_suite(batch / 'suite.json')
    require(manifest['document_sha256'] == {item.document_id: hashlib.sha256(item.text.encode()).hexdigest() for item in documents}, 'frozen_corpus_changed')
    if junit and not_before:
        require(timestamp(not_before) >= timestamp(manifest['prepared_at']), 'junit_window_before_frozen_batch')
    tests, files = junit_evidence(junit, not_before)
    runs = load_runs(runs_path, manifest, {case.id for case in cases})
    reviews = load_reviews(review_path, manifest, runs)
    results = []
    for case in cases:
        found = [tests[test]['outcome'] for test in case.contract_tests if test in tests]
        contract = 'NOT_RUN' if not found else 'FAIL' if 'FAIL' in found else 'UNKNOWN' if 'UNKNOWN' in found or len(found) < len(case.contract_tests) else 'PASS'
        observed = runs.get(case.id)
        semantic = reviews[case.id].outcome if case.id in reviews else 'UNKNOWN' if observed else 'NOT_RUN'
        results.append({'case_id': case.id, 'category': case.category, 'contract': contract,
                        'contract_tests': case.contract_tests, 'missing_contract_tests': [test for test in case.contract_tests if test not in tests],
                        'semantic': semantic, 'semantic_rubric': case.semantic_rubric,
                        'review': asdict(reviews[case.id]) if case.id in reviews else None,
                        'model_run': None if not observed else {'run_id': observed[0].run_id,
                        'runtime_status': observed[0].runtime_status, 'answer_sha256': observed[0].answer_sha256,
                        'metrics': observed[1]}})
    return {'schema_version': 1, 'suite_id': manifest['suite_id'], 'suite_sha256': manifest['suite_sha256'],
            'identity': manifest['identity'], 'identity_sha256': manifest['identity_sha256'],
            'network_calls_dispatched_by_this_tool': 0, 'engineering_evidence': files, 'cases': results,
            'counts': {lane: {outcome: sum(case[lane] == outcome for case in results) for outcome in OUTCOMES}
                       for lane in ('contract', 'semantic')}}


def write_report(output, report):
    output.mkdir(parents=True, exist_ok=False)
    save(output / 'report.json', report)
    lines = ['# 固定知识评估报告', '', '契约通过仅来自所列真实 JUnit；语义通过仅来自绑定原回答的人工复核。', '',
             '|案例|类别|确定性契约|真实模型语义|', '|---|---|---|---|']
    lines += [f"|{row['case_id']}|{row['category']}|{row['contract']}|{row['semantic']}|" for row in report['cases']]
    lines += ['', '未执行项保留 NOT_RUN；缺少、跳过或不完整证据不计为通过。该报告工具不调用模型或业务工具。', '']
    (output / 'report.md').write_text('\n'.join(lines), encoding='utf-8', newline='\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    validate = commands.add_parser('validate'); validate.add_argument('--suite', type=Path, default=DEFAULT_SUITE)
    freeze = commands.add_parser('prepare'); freeze.add_argument('--suite', type=Path, default=DEFAULT_SUITE)
    freeze.add_argument('--identity', type=Path); freeze.add_argument('--output', type=Path, required=True)
    report = commands.add_parser('report'); report.add_argument('--batch', type=Path, required=True)
    report.add_argument('--junit', type=Path, action='append', default=[]); report.add_argument('--not-before')
    report.add_argument('--runs', type=Path); report.add_argument('--reviews', type=Path)
    report.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'validate':
        _, documents, cases = load_suite(args.suite)
        print(json.dumps({'corpus_documents': len(documents), 'cases': len(cases), 'suite_integrity': 'PASS',
                          'runtime_contract': 'NOT_RUN', 'real_model_semantics': 'NOT_RUN'}))
    elif args.command == 'prepare':
        prepare(args.suite, args.output, args.identity)
        print(json.dumps({'prepared': str(args.output), 'contract': 'NOT_RUN', 'semantic': 'NOT_RUN'}))
    else:
        result = assess(args.batch, args.junit, args.not_before, args.runs, args.reviews)
        write_report(args.output, result)
        print(json.dumps(result['counts']))


if __name__ == '__main__':
    main()
