"""本批私有登录回执的真实续期；unknown续期停止原请求，不重放任何聊天。"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import time

import requests


def _read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def _save(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def current_token(fixture_path, service, origin):
    """按真实expires_in提前续期；同私有fixture的多个进程先读当前token再独占刷新。"""
    if service not in ('platform', 'institution'):
        raise ValueError('EXPLICIT_AUTH_SERVICE_REQUIRED')
    fixture_path = Path(fixture_path)
    fixture = _read(fixture_path)
    if fixture['origins'][service].rstrip('/') != origin:
        raise RuntimeError('AUTH_FIXTURE_ORIGIN_CHANGED')
    authentication = fixture.get('authentication', {}).get(service)
    if not authentication or time.time() < authentication['expires_at'] - 30:
        return fixture[service + '_token']
    lock = fixture_path.with_suffix('.auth.lock')
    try:
        descriptor = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
    except FileExistsError:
        raise RuntimeError('AUTH_REFRESH_LOCKED_NO_PARALLEL') from None
    os.close(descriptor)
    try:
        fixture = _read(fixture_path)
        authentication = fixture['authentication'][service]
        if time.time() < authentication['expires_at'] - 30:
            return fixture[service + '_token']
        key = hashlib.sha256(authentication['refresh_token'].encode()).hexdigest()
        journal_path = fixture_path.with_name('authentication-refresh.private.json')
        journal = _read(journal_path) if journal_path.exists() else {'operations': {}}
        if key in journal['operations']:
            raise RuntimeError('AUTH_REFRESH_UNKNOWN_NO_REPLAY')
        entry = {'service': service, 'status': 'unknown', 'issued_at': time.time()}
        journal['operations'][key] = entry
        _save(journal_path, journal)
        route = '/platform/auth/refresh' if service == 'platform' else '/auth/refresh'
        try:
            response = requests.post(origin + '/api/v1' + route, json={'refresh_token': authentication['refresh_token']},
                                     timeout=30, allow_redirects=False)
        except requests.RequestException:
            raise RuntimeError('AUTH_REFRESH_OUTCOME_UNKNOWN_ORIGINAL_REQUEST_NOT_SENT') from None
        if not response.ok:
            entry.update(status='rejected', http_status=response.status_code)
            _save(journal_path, journal)
            raise RuntimeError('AUTH_REFRESH_REJECTED_ORIGINAL_REQUEST_NOT_SENT')
        try:
            envelope = response.json()
            data = envelope['data']
            if (envelope['code'] != 'OK' or not isinstance(data['access_token'], str) or not data['access_token']
                    or not isinstance(data['refresh_token'], str) or not data['refresh_token']
                    or isinstance(data['expires_in'], bool) or not isinstance(data['expires_in'], (float, int))
                    or data['expires_in'] <= 0):
                raise ValueError()
        except (ValueError, KeyError, TypeError):
            raise RuntimeError('AUTH_REFRESH_RESPONSE_UNKNOWN_ORIGINAL_REQUEST_NOT_SENT') from None
        fixture[service + '_token'] = data['access_token']
        fixture['authentication'][service] = {'refresh_token': data['refresh_token'],
                                             'expires_at': time.time() + data['expires_in']}
        _save(fixture_path, fixture)
        entry.update(status='observed', expires_at=fixture['authentication'][service]['expires_at'])
        _save(journal_path, journal)
        return data['access_token']
    finally:
        lock.unlink()
