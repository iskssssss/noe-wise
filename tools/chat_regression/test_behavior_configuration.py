"""省去 API 批次重复启动回读，保留派发前、周期和收尾检查。"""
from pathlib import Path
import sys
import tempfile
import unittest
import json
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))
from behavior_environment import ConfigurationMonitor
from behavior_product_runner import _start_product_monitor, execute_product_case
from runner import save
from client import ApiError, ModelQuotaStop, failure_diagnostic
from behavior_runner import run_batch
from behavior_suite import make_plan


class ConfigurationStartupTest(unittest.TestCase):
    def test_read_failure_keeps_safe_http_context_without_retry_or_secret(self):
        read = Mock(side_effect=ApiError('private-response SECRET', status_code=401, method='GET',
                                        path='/agents/detail?agentId=private-id&token=SECRET'))
        monitor = ConfigurationMonitor(read, {'revision': 1})
        self.assertFalse(monitor.check())
        read.assert_called_once()
        failure = monitor.failures[0]
        self.assertEqual((401, 'GET', '/agents/detail'),
                         (failure['http_status'], failure['method'], failure['endpoint']))
        self.assertNotIn('SECRET', json.dumps(failure))
        self.assertNotIn('private', json.dumps(failure))
        self.assertEqual('configuration_read_failed', monitor.reasons[0]['detail'])

    def test_diagnostic_does_not_export_arbitrary_exception_fields(self):
        error = ApiError('SECRET', status_code='SECRET', method='SECRET', path='/SECRET?token=SECRET')
        self.assertEqual({'kind': 'api_failure'}, failure_diagnostic(error))
        self.assertEqual({'kind': 'unexpected_failure'}, failure_diagnostic(ValueError('SECRET')))
        self.assertEqual({'kind': 'quota_or_rate_limit', 'http_status': 429},
                         failure_diagnostic(ModelQuotaStop('SECRET', status_code=429)))
        self.assertEqual({'kind': 'api_failure', 'method': 'GET', 'endpoint': '/agents/detail',
                          'transport_kind': 'TimeoutError'}, failure_diagnostic(ApiError('SECRET', method='GET',
                              path='/agents/detail?agentId=SECRET', transport_kind='TimeoutError')))
        self.assertNotIn('SECRET', str(failure_diagnostic(ApiError('SECRET', transport_kind='SECRET', response_kind='SECRET'))))

    def test_core_persists_safe_failure_details_and_never_dispatches_when_startup_check_fails(self):
        plan = make_plan('tests')
        plan['local_selection'] = {'case_ids': ['B01'], 'repetitions': 1}
        error = ApiError('private body SECRET', status_code=429, method='GET', path='/agents/detail?agentId=SECRET')
        with tempfile.TemporaryDirectory() as directory, patch('behavior_runner.configuration',
                side_effect=[{'revision': 1}, error, error]), patch('behavior_runner.execute_case') as execute:
            batch = run_batch(Mock(), Mock(), Mock(), plan,
                              {'users': [{'id': 'one'}], 'model_id': 'one'}, directory, 'revision', 100, 1, 60)
            checks = json.loads((Path(directory) / 'configuration-checks.json').read_text(encoding='utf-8'))
            execute.assert_not_called()
            self.assertEqual(len(checks['failures']), 2)
            self.assertEqual(checks['failures'], batch['configuration_failures'])
            self.assertEqual(checks['failures'][0]['http_status'], 429)
            self.assertNotIn('SECRET', json.dumps(checks))

    def test_api_selection_skips_startup_read_and_browser_keeps_it(self):
        for selected, expected in ((['D01', 'D08'], False), (['D09'], True),
                                   (['D08', 'D12'], True), ([], True)):
            with self.subTest(selected=selected):
                monitor = Mock()
                _start_product_monitor(monitor, {'local_selection': {'case_ids': selected}})
                monitor.start.assert_called_once_with(check_initial=expected)

    def test_default_start_checks_and_close_still_checks(self):
        read = Mock(return_value={'revision': 1})
        monitor = ConfigurationMonitor(read, {'revision': 1})
        monitor.start()
        try:
            self.assertEqual(read.call_count, 1)
        finally:
            monitor.close()
        self.assertEqual(read.call_count, 2)
        self.assertFalse(monitor.thread.is_alive())

    def test_skipped_start_retains_periodic_and_final_drift_checks(self):
        read = Mock(return_value={'revision': 2})
        monitor = ConfigurationMonitor(read, {'revision': 1})
        # 明确控制周期唤醒，不依赖 sleep 推断后台已运行。
        monitor.stop = Mock()
        monitor.stop.wait.side_effect = [False, True]
        monitor.start(check_initial=False)
        monitor.thread.join(5)
        self.assertFalse(monitor.thread.is_alive())
        self.assertEqual(read.call_count, 1)
        self.assertIn('observed_configuration_changed', str(monitor.reasons))
        monitor.close()
        self.assertEqual(read.call_count, 2)

    def test_changed_config_blocks_api_input_before_budget_or_dispatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / 'cases' / 'D01' / '1'
            folder.mkdir(parents=True)
            save(folder / 'state.json', {'session_id': 'existing', 'turns': {}})
            api, budget, monitor = Mock(), Mock(), Mock()
            monitor.check.return_value = False
            with self.assertRaisesRegex(ValueError, 'product_configuration_changed'):
                execute_product_case(api, {'id': 'D01', 'turns': ['hello']}, 1, {},
                                     {'cases': {'D01': {}}}, root, budget, monitor)
            monitor.check.assert_called_once()
            budget.before_turn.assert_not_called()
            api.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()
