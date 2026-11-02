"""公共读取仅按明确限流政策续读，原预算与写入不自动重放的边界验证。"""
from __future__ import annotations

import io
import json
import unittest
from email.message import Message
from email.utils import formatdate
from urllib.error import HTTPError
from unittest.mock import patch

from client import ApiFailure, Client, read_until, retry_after_seconds


class ReadRateLimitTest(unittest.TestCase):
    """只替代HTTP传输与时钟，验证实际客户端请求编码和等待/重发边界。"""

    def test_retry_after_reads_keep_original_budget_and_query(self):
        """两次合法限流等待消耗同一预算，每次GET携带原游标，HTTP超时随剩余预算缩短。"""
        now=[0.0]
        calls=[]

        class Opener:
            def open(self,request,timeout):
                """前两次返回带明确头的429，之后返回现有Run状态，不创建任何写入。"""
                calls.append((request,timeout))
                if len(calls)<3:
                    headers=Message();headers['Retry-After']='3'
                    raise HTTPError(request.full_url,429,'limited',headers,io.BytesIO(b'{"code":"RATE_LIMITED"}'))
                result=io.BytesIO(b'{"code":"OK","data":{"status":"completed"}}');result.status=200
                return result

        client=Client('http://127.0.0.1:1');client.opener=Opener()
        with patch('client.time.monotonic',side_effect=lambda:now[0]), \
                patch('client.time.sleep',side_effect=lambda seconds:now.__setitem__(0,now[0]+seconds)) as sleep:
            result=read_until(client,'/open-platform/sessions/status',{'sessionId':'s/1','cursor':'opaque/+='},10.0)
        self.assertEqual({'status':'completed'},result)
        self.assertEqual([10.0,7.0,4.0],[timeout for _,timeout in calls])
        self.assertEqual([3.0,3.0],[call.args[0] for call in sleep.call_args_list])
        self.assertEqual(1,len({request.full_url for request,_ in calls}))
        self.assertTrue(all(request.get_method()=='GET' for request,_ in calls))

    def test_retry_after_exceeding_original_budget_does_not_wait_or_resubmit(self):
        """服务器要求等待长于剩余预算时明确停止，不延长Run等待或提前违反Retry-After重读。"""
        client=Client('http://127.0.0.1:1')
        with patch('client.time.monotonic',return_value=2.0), \
                patch.object(client,'request',side_effect=ApiFailure(429,'RATE_LIMITED',60.0)) as request, \
                patch('client.time.sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError,'RUN_READ_BUDGET_EXHAUSTED'):
                read_until(client,'/open-platform/sessions/events',{'sessionId':'s'},30.0)
        self.assertEqual(1,request.call_count);sleep.assert_not_called()

    def test_missing_retry_policy_and_non_rate_failure_are_not_retried(self):
        """没有明确限流政策及普通认证错误原样返回，客户端不自行猜测等待或更换身份。"""
        for error in (ApiFailure(429,'RATE_LIMITED'),ApiFailure(403,'FORBIDDEN'),RuntimeError('HTTP_OUTCOME_UNKNOWN')):
            client=Client('http://127.0.0.1:1')
            with self.subTest(error=str(error)),patch('client.time.monotonic',return_value=0.0), \
                    patch.object(client,'request',side_effect=error) as request,patch('client.time.sleep') as sleep:
                with self.assertRaises(type(error)):
                    read_until(client,'/open-platform/sessions/status',{'sessionId':'s'},30.0)
                self.assertEqual(1,request.call_count);sleep.assert_not_called()

    def test_write_with_rate_limit_is_never_automatically_replayed(self):
        """写入429只返回原错误及等待信息，稳定幂等键不意味着客户端可擅自重放写请求。"""
        calls=[]

        class Opener:
            def open(self,request,timeout):
                """记录唯一写入尝试，并按真实HTTPError形态返回限流。"""
                calls.append(request)
                headers=Message();headers['Retry-After']='60'
                raise HTTPError(request.full_url,429,'limited',headers,io.BytesIO(b'{"code":"RATE_LIMITED"}'))

        client=Client('http://127.0.0.1:1');client.opener=Opener()
        with patch('client.time.sleep') as sleep,self.assertRaises(ApiFailure) as failed:
            client.request('POST','/open-platform/sessions/messages',{'session_id':'s'},idempotency_key='original-key')
        self.assertEqual(60.0,failed.exception.retry_after_seconds)
        self.assertEqual(1,len(calls));sleep.assert_not_called()
        self.assertEqual('POST',calls[0].get_method())
        self.assertEqual({'session_id':'s'},json.loads(calls[0].data))

    def test_http_date_policy_and_malformed_headers(self):
        """Retry-After兼容正式HTTP日期；缺失或非法头不获得默认重试政策。"""
        with patch('client.time.time',return_value=1000.0):
            self.assertEqual(5.0,retry_after_seconds(formatdate(1005,usegmt=True)))
        for value in (None,'','-3','not-a-date'):
            self.assertIsNone(retry_after_seconds(value))

    def test_exhausted_budget_never_sends_another_read(self):
        """原deadline已耗尽时不再发送GET，更不创建新Run或游标。"""
        client=Client('http://127.0.0.1:1')
        with patch('client.time.monotonic',return_value=10.0),patch.object(client,'request') as request:
            with self.assertRaisesRegex(RuntimeError,'RUN_READ_BUDGET_EXHAUSTED'):
                read_until(client,'/open-platform/sessions/status',{},10.0)
            request.assert_not_called()


if __name__=='__main__':
    unittest.main()
