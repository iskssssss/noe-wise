"""仅验证本地评估资产和报告边界；临时模拟XML不计为PG或模型验收。"""
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest

import run_evaluation as evaluation


class KnowledgeEvaluationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='q06-reporter-selftest-')
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def prepare(self, selector=None):
        suite = evaluation.read(evaluation.DEFAULT_SUITE)
        if selector:
            suite['cases'][0]['contract_tests'] = [selector]
        source = self.root / 'input-suite.json'
        evaluation.save(source, suite)
        batch = self.root / 'batch'
        evaluation.prepare(source, batch)
        return batch

    def xml(self, body):
        path = self.root / 'selftest.xml'
        path.write_text('<testsuite>' + body + '</testsuite>', encoding='utf-8')
        return path

    def window(self, batch):
        return evaluation.read(batch / 'frozen.json')['prepared_at']

    def test_fixed_corpus_covers_pagination_unicode_and_isolation(self):
        suite, documents, cases = evaluation.load_suite(evaluation.DEFAULT_SUITE)
        self.assertEqual(26, len(documents))
        self.assertEqual(8, len(cases))
        long = next(document for document in documents if document.document_id == suite['oracles']['long_document_id'])
        self.assertEqual(25600, len(long.text))
        self.assertEqual('🚀', long.text[3999])
        self.assertEqual('𠮷', long.text[7999])
        self.assertGreater(len(long.text.encode('utf-16-le')) // 2, len(long.text))
        self.assertEqual(2, len([item for item in documents if not item.mounted]))

    def test_prepare_never_invents_contract_or_model_pass(self):
        batch = self.prepare()
        report = evaluation.read(batch / 'report-initial/report.json')
        self.assertEqual({'PASS': 0, 'FAIL': 0, 'UNKNOWN': 0, 'NOT_RUN': 8}, report['counts']['contract'])
        self.assertEqual(report['counts']['contract'], report['counts']['semantic'])
        self.assertEqual('NOT_RUN', report['identity']['status'])
        self.assertEqual(0, report['network_calls_dispatched_by_this_tool'])

    def test_reusing_batch_or_report_directory_is_rejected(self):
        batch = self.prepare()
        with self.assertRaises(FileExistsError):
            evaluation.prepare(evaluation.DEFAULT_SUITE, batch)
        with self.assertRaises(FileExistsError):
            evaluation.write_report(batch / 'report-initial', evaluation.assess(batch))

    def test_frozen_suite_or_identity_drift_is_rejected(self):
        batch = self.prepare()
        (batch / 'suite.json').write_bytes((batch / 'suite.json').read_bytes() + b' ')
        with self.assertRaisesRegex(ValueError, 'frozen_suite_changed'):
            evaluation.assess(batch)

    def test_partial_junit_evidence_stays_unknown_and_semantics_not_run(self):
        batch = self.prepare('fixture.Synthetic#first')
        suite = evaluation.read(batch / 'suite.json')
        suite['cases'][0]['contract_tests'].append('fixture.Synthetic#second')
        (batch / 'suite.json').write_text(json.dumps(suite, ensure_ascii=False), encoding='utf-8')
        manifest = evaluation.read(batch / 'frozen.json')
        manifest['suite_sha256'] = evaluation.sha(batch / 'suite.json')
        (batch / 'frozen.json').write_text(json.dumps(manifest), encoding='utf-8')
        xml = self.xml('<testcase classname="fixture.Synthetic" name="first"/>')
        report = evaluation.assess(batch, [xml], self.window(batch))
        self.assertEqual('UNKNOWN', report['cases'][0]['contract'])
        self.assertEqual('NOT_RUN', report['cases'][0]['semantic'])

    def test_failed_and_skipped_junit_are_not_pass(self):
        batch = self.prepare('fixture.Synthetic#case')
        for element, expected in (('<failure message="synthetic test"/>', 'FAIL'), ('<skipped/>', 'UNKNOWN')):
            xml = self.xml('<testcase classname="fixture.Synthetic" name="case">' + element + '</testcase>')
            report = evaluation.assess(batch, [xml], self.window(batch))
            self.assertEqual(expected, report['cases'][0]['contract'])
            self.assertEqual('NOT_RUN', report['cases'][0]['semantic'])

    def test_junit_requires_execution_window_and_exact_selector(self):
        batch = self.prepare('fixture.Synthetic#target')
        xml = self.xml('<testcase classname="fixture.Synthetic" name="other"/>')
        with self.assertRaisesRegex(ValueError, 'junit_not_before_required'):
            evaluation.assess(batch, [xml])
        future = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
        with self.assertRaisesRegex(ValueError, 'older_than_window'):
            evaluation.assess(batch, [xml], future)
        with self.assertRaisesRegex(ValueError, 'window_before_frozen_batch'):
            evaluation.assess(batch, [xml], '2020-01-01T00:00:00Z')
        report = evaluation.assess(batch, [xml], self.window(batch))
        self.assertEqual('NOT_RUN', report['cases'][0]['contract'])

    def test_missing_provider_and_mock_cannot_be_semantic_evidence(self):
        batch = self.prepare()
        manifest = evaluation.read(batch / 'frozen.json')
        runs = self.root / 'runs.json'
        evaluation.save(runs, {'suite_sha256': manifest['suite_sha256'], 'runs': []})
        with self.assertRaisesRegex(ValueError, 'real_model_requires_frozen_identity'):
            evaluation.assess(batch, runs_path=runs)
        manifest['identity']['status'] = 'FROZEN'
        row = {'case_id': 'K01', 'execution_kind': 'MOCK', 'run_id': 'synthetic', 'runtime_status': 'completed',
               'identity_sha256': manifest['identity_sha256'], 'trace_path': 'none.json', 'trace_sha256': 'a' * 64,
               'answer_path': 'none.txt', 'answer_sha256': 'b' * 64, 'provider_receipt_path': 'none.json',
               'provider_receipt_sha256': 'c' * 64}
        runs.write_text(json.dumps({'suite_sha256': manifest['suite_sha256'], 'runs': [row]}), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'mock_is_not_real_model_evidence'):
            evaluation.load_runs(runs, manifest, {'K01'})

    def test_review_without_real_run_is_rejected(self):
        batch = self.prepare()
        review = self.root / 'review.json'
        evaluation.save(review, {'reviews': [{'case_id': 'K01', 'run_id': 'synthetic', 'suite_sha256': 'a' * 64,
                         'identity_sha256': 'b' * 64, 'answer_sha256': 'c' * 64, 'outcome': 'PASS',
                         'reviewer': 'selftest', 'reviewed_at': '2026-10-24T00:00:00Z', 'reason': 'synthetic',
                         'evidence_refs': ['answer.txt']}]})
        with self.assertRaisesRegex(ValueError, 'review_requires_observed_case'):
            evaluation.assess(batch, review_path=review)

    def test_evidence_path_escape_and_digest_mismatch_are_rejected(self):
        path = self.root / 'evidence.txt'
        path.write_text('local selftest only', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'digest_mismatch'):
            evaluation.artifact(self.root, path.name, 'a' * 64)
        with self.assertRaisesRegex(ValueError, 'escape_or_missing'):
            evaluation.artifact(self.root, '../outside.txt', 'a' * 64)


if __name__ == '__main__':
    unittest.main()
