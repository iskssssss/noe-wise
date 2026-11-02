"""真实续期设施的已知与未知响应负控，不发送网络/模型请求。"""
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import requests
from live_auth import current_token


class LiveAuthTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / 'fixture.private.json'
        self.fixture = {'origins': {'institution': 'http://127.0.0.1:1234'}, 'institution_token': 'old-access',
                        'authentication': {'institution': {'refresh_token': 'old-refresh', 'expires_at': time.time() - 1}}}
        self.save()

    def save(self):
        self.path.write_text(json.dumps(self.fixture))

    def tearDown(self):
        self.temporary.cleanup()

    def test_explicit_true_expiry_refreshes_once_and_all_later_clients_read_new_token(self):
        response = Mock(ok=True, json=lambda: {'code': 'OK', 'data': {
            'access_token': 'new-access', 'refresh_token': 'new-refresh', 'expires_in': 600}})
        with patch('live_auth.requests.post', return_value=response) as post:
            for _ in range(3):
                self.assertEqual('new-access', current_token(self.path, 'institution', 'http://127.0.0.1:1234'))
            post.assert_called_once()
            self.assertFalse(post.call_args.kwargs['allow_redirects'])
        self.assertEqual('new-refresh', json.loads(self.path.read_text())['authentication']['institution']['refresh_token'])

    def test_unknown_response_stops_and_original_refresh_is_never_replayed(self):
        with patch('live_auth.requests.post', side_effect=requests.ConnectionError()) as post:
            with self.assertRaisesRegex(RuntimeError, 'OUTCOME_UNKNOWN'):
                current_token(self.path, 'institution', 'http://127.0.0.1:1234')
            with self.assertRaisesRegex(RuntimeError, 'UNKNOWN_NO_REPLAY'):
                current_token(self.path, 'institution', 'http://127.0.0.1:1234')
            post.assert_called_once()
        self.assertEqual('old-access', json.loads(self.path.read_text())['institution_token'])

    def test_origin_change_and_refresh_lock_refuse_before_network(self):
        with patch('live_auth.requests.post') as post:
            with self.assertRaisesRegex(RuntimeError, 'ORIGIN_CHANGED'):
                current_token(self.path, 'institution', 'http://127.0.0.1:8200')
            self.path.with_suffix('.auth.lock').write_text('another-process')
            with self.assertRaisesRegex(RuntimeError, 'LOCKED'):
                current_token(self.path, 'institution', 'http://127.0.0.1:1234')
            post.assert_not_called()


if __name__ == '__main__':
    unittest.main()
