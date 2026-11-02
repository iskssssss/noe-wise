import json
from pathlib import Path
import tempfile
import unittest
import sys
sys.path.insert(0, str(Path(__file__).parent))
from core_final_format_oracle import CORE_SUITE, evaluate_lane, whole_message

GOOD = '{"status":"success","value":100,"url":null,"message":"已查询"}'

class WholeFinalFormatTests(unittest.TestCase):
    def test_accepts_raw_complete_object_and_whitespace(self):
        self.assertEqual('PASS', whole_message(' \n'+GOOD+'\t')['status'])

    def test_accepts_single_whole_message_json_fence(self):
        self.assertEqual('PASS', whole_message('```json\n'+GOOD+'\n```')['status'])

    def test_rejects_greeting_only_and_json_preamble(self):
        for text in ['你好', '查询结果如下：\n'+GOOD, '查询结果如下：\n```json\n'+GOOD+'\n```']:
            with self.subTest(text=text):
                self.assertEqual('FAIL', whole_message(text)['status'])

    def test_rejects_epilogue_two_objects_and_two_fences(self):
        for text in [GOOD+'\n希望有帮助', GOOD+'\n'+GOOD, '```json\n'+GOOD+'\n```\n```json\n'+GOOD+'\n```']:
            self.assertEqual('FAIL', whole_message(text)['status'])

    def test_rejects_missing_extra_or_wrong_type_fields(self):
        for text in ['{"status":"success","value":100,"url":null}',
                     GOOD[:-1]+',"trace":"x"}', GOOD.replace('"message":"已查询"','"message":7'),
                     GOOD.replace('"url":null','"url":false'), GOOD.replace('"success"','"completed"')]:
            self.assertEqual('FAIL', whole_message(text)['status'])

    def test_preserves_generic_json_value_types_for_separate_fidelity_check(self):
        for raw in ['100','"100"','null','true','[]','{}']:
            self.assertEqual('PASS', whole_message(GOOD.replace(':100', ':'+raw))['status'])

    def test_rejects_duplicate_fields_including_nested_value(self):
        for text in [GOOD[:-1]+',"status":"denied"}', GOOD.replace(':100', ':{"x":1,"x":2}')]:
            self.assertEqual(['duplicate_json_key'], whole_message(text)['violations'])

    def test_rejects_non_json_nonfinite_and_non_object(self):
        for text in [GOOD.replace(':100', ':NaN'), GOOD.replace(':100', ':Infinity'),
                     GOOD.replace(':100', ':1e9999'), '[]', 'null', '100']:
            self.assertEqual('FAIL', whole_message(text)['status'])

    def test_rejects_tool_record_shape_even_with_correct_value(self):
        self.assertEqual('FAIL', whole_message('{"case_key":"B10","complete":true,"value":100}')['status'])

    def test_does_not_classify_value_semantics_or_process_limit_as_format(self):
        self.assertEqual('PASS', whole_message(GOOD.replace(':100', ':999'))['status'])


class FrozenEvidenceEligibilityTests(unittest.TestCase):
    """Synthetic file fixtures: no existing Core inputs or evidence are changed."""
    PROTOCOL = 'Every final must contain the supplied four JSON fields.\n'

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.lane = self.root / 'lane'
        self.case = self.lane / 'cases' / 'B01' / '1'
        self.case.mkdir(parents=True)
        self.plan = {'suite_version': CORE_SUITE, 'batch': 'synthetic-format-oracle', 'repetitions': 1,
                     'profiles': [{'key': 'behavior', 'prompt': self.PROTOCOL + 'Allowed fixture suffix.'}],
                     'cases': [{'id': 'B01', 'profile': 'behavior'}]}
        self.evidence = {'frozen_at': '2026-10-12T00:00:00Z',
                         'terminal': {'durability': 'persistent', 'status': 'completed'},
                         'completeness': {k: True for k in ('trace', 'audit', 'terminal', 'context', 'quiescence')},
                         'blocking_reasons': [], 'observable_output': {'visible_events': [self.final(GOOD)]}}
        self.evaluation = {'behavior_status': 'passed'}

    @staticmethod
    def final(content, durability='persistent'):
        return {'event': 'message.delta', 'data': {'phase': 'final', 'state': 'completed',
                'durability': durability, 'message_id': 'm', 'input_id': 'i', 'run_id': 'r', 'content': content}}

    @staticmethod
    def write(path, value):
        path.write_text(json.dumps(value), encoding='utf-8')

    def assess(self):
        self.write(self.lane / 'plan.json', self.plan)
        self.write(self.case / 'evidence.json', self.evidence)
        self.write(self.case / 'evaluation.json', self.evaluation)
        return evaluate_lane(self.root, self.lane, self.PROTOCOL)[0]

    def test_eligible_frozen_final_passes_and_original_verdict_is_preserved(self):
        row = self.assess()
        self.assertEqual('PASS', row['status'])
        self.assertEqual('passed', row['original_behavior_status_unchanged'])
        self.assertEqual(self.evaluation, json.loads((self.case / 'evaluation.json').read_text()))

    def test_wrong_suite_profile_or_exact_protocol_is_not_applicable(self):
        self.plan['suite_version'] = 'another-suite'
        self.assertEqual('NOT_APPLICABLE', self.assess()['status'])
        self.plan['suite_version'] = CORE_SUITE
        self.plan['cases'][0]['profile'] = 'other'
        self.assertEqual('NOT_APPLICABLE', self.assess()['status'])
        self.plan['cases'][0]['profile'] = 'behavior'
        self.plan['profiles'][0]['prompt'] = 'Changed contract.\n' + self.PROTOCOL
        self.assertEqual('NOT_APPLICABLE', self.assess()['status'])

    def test_missing_evidence_or_original_evaluation_cannot_be_pass(self):
        self.assess()
        for filename in ('evidence.json', 'evaluation.json'):
            self.assess()
            (self.case / filename).unlink()
            row = evaluate_lane(self.root, self.lane, self.PROTOCOL)[0]
            self.assertEqual('NOT_REVIEWED', row['status'])
            self.assertEqual('missing_frozen_evidence_or_original_evaluation', row['reason'])

    def test_unfrozen_incomplete_or_blocked_evidence_cannot_be_pass(self):
        self.evidence['frozen_at'] = None
        self.assertEqual('NOT_REVIEWED', self.assess()['status'])
        self.evidence['frozen_at'] = '2026-10-12T00:00:00Z'
        for dimension in self.evidence['completeness']:
            self.evidence['completeness'][dimension] = False
            self.assertEqual('NOT_REVIEWED', self.assess()['status'])
            self.evidence['completeness'][dimension] = True
        self.evidence['blocking_reasons'] = ['synthetic-incomplete']
        self.assertEqual('NOT_REVIEWED', self.assess()['status'])

    def test_transient_terminal_or_final_is_not_reviewed(self):
        self.evidence['terminal']['durability'] = 'transient'
        self.assertEqual('NOT_REVIEWED', self.assess()['status'])
        self.evidence['terminal']['durability'] = 'persistent'
        self.evidence['observable_output']['visible_events'] = [self.final(GOOD, 'transient')]
        self.assertEqual('NOT_REVIEWED', self.assess()['status'])

    def test_no_final_does_not_infer_reply_from_parsed_or_hidden_fields(self):
        self.evidence['observable_output']['visible_events'] = []
        self.evidence['answer'] = json.loads(GOOD)
        self.evidence['synthetic_hidden_model_output'] = GOOD
        self.evidence['terminal']['status'] = 'cancelled'
        row = self.assess()
        self.assertEqual('NOT_REVIEWED', row['status'])
        self.assertEqual('no_persistent_completed_public_final', row['reason'])

    def test_every_distinct_persistent_final_is_checked_and_delivery_duplicate_is_deduplicated(self):
        second = self.final('Preface\n' + GOOD)
        second['data']['message_id'] = 'm2'
        self.evidence['observable_output']['visible_events'] = [self.final(GOOD), self.final(GOOD), second]
        row = self.assess()
        self.assertEqual('FAIL', row['status'])
        self.assertEqual(2, row['observed_public_final_count'])
        self.assertEqual(['PASS', 'FAIL'], [final['status'] for final in row['finals']])

if __name__ == '__main__':
    unittest.main()
