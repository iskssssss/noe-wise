"""又拍云 FUSE 小文件验证器；仅用于隔离测试目录，不提供生产 POSIX 保证。"""
import errno
import functools
import io
import json
import os
import stat
import sys
import time

import upyun
import requests
from fuse import FUSE, FuseOSError, Operations


def api_errors(method):
    @functools.wraps(method)
    def call(*args, **kwargs):
        try:
            return method(*args, **kwargs)
        except upyun.UpYunServiceException as error:
            # 不输出包含认证上下文的异常正文。
            code = getattr(error, 'status', None) or getattr(error, 'status_code', None)
            print('API_ERROR operation=' + method.__name__ + ' status=' + str(code), flush=True)
            raise FuseOSError(errno.ENOENT if str(code) == '404' else errno.EIO)
    return call


class Workspace(Operations):
    def __init__(self, config):
        self.prefix = config['prefix'].rstrip('/')
        if not self.prefix.startswith('/workspace-poc/') or '..' in self.prefix:
            raise ValueError('必须使用专用测试目录')
        self.client = upyun.UpYun(config['bucket'], config['operator'], config['password'],
                                  endpoint='v0.api.upyun.com', timeout=15)
        # 此版本 SDK 默认 HTTP；在签名后强制 HTTPS，禁止重定向携带认证头。
        def https_request(method, host, uri, value=None, headers=None, stream=False, files=None):
            for attempt in range(4):
                response = requests.request(method, 'https://v0.api.upyun.com' + uri,
                                            data=value, headers=headers, stream=stream,
                                            timeout=15, allow_redirects=False)
                if response.status_code != 429 or attempt == 3:
                    break
                # 文件创建后紧接写入可能触发服务端限流，仅对明确拒绝做有限退避。
                response.close()
                time.sleep(2 ** attempt)
            if response.status_code // 100 != 2:
                raise upyun.UpYunServiceException('poc', response.status_code, 'API failed', '')
            response.encoding = 'utf-8'
            return response
        self.client.hp.do_http_pipe = https_request
        self.client.mkdir(self.prefix)

    def content(self, path):
        buffer = io.BytesIO()
        self.client.get(self.key(path), buffer)
        return buffer.getvalue()

    def key(self, path):
        if '..' in path.split('/'):
            raise FuseOSError(errno.EACCES)
        return self.prefix + path

    @api_errors
    def getattr(self, path, fh=None):
        info = self.client.getinfo(self.key(path))
        directory = info['file-type'] == 'folder'
        return dict(st_mode=(stat.S_IFDIR | 0o755) if directory else (stat.S_IFREG | 0o644),
                    st_nlink=2 if directory else 1, st_uid=10001, st_gid=10001,
                    st_size=int(info.get('file-size', 0)), st_mtime=time.time(),
                    st_ctime=time.time(), st_atime=time.time())

    @api_errors
    def readdir(self, path, fh):
        return ['.', '..'] + [item['name'] for item in self.client.getlist(self.key(path))]

    @api_errors
    def mkdir(self, path, mode):
        self.client.mkdir(self.key(path))
        return 0

    @api_errors
    def create(self, path, mode, fi=None):
        self.client.put(self.key(path), b'')
        return 0

    def open(self, path, flags):
        self.getattr(path)
        return 0

    @api_errors
    def read(self, path, size, offset, fh):
        self.check_size(path)
        return self.content(path)[offset:offset + size]

    def check_size(self, path):
        if self.getattr(path)['st_size'] > 1024 * 1024:
            raise FuseOSError(errno.EFBIG)

    @api_errors
    def write(self, path, data, offset, fh):
        self.check_size(path)
        if offset + len(data) > 1024 * 1024:
            raise FuseOSError(errno.EFBIG)
        previous = self.content(path)
        content = previous[:offset].ljust(offset, b'\0') + data
        content += previous[offset + len(data):]
        self.client.put(self.key(path), content)
        return len(data)

    @api_errors
    def truncate(self, path, length, fh=None):
        self.check_size(path)
        if length > 1024 * 1024:
            raise FuseOSError(errno.EFBIG)
        content = self.content(path) if length else b''
        self.client.put(self.key(path), content[:length].ljust(length, b'\0'))
        return 0

    @api_errors
    def unlink(self, path):
        self.client.delete(self.key(path))
        return 0

    rmdir = unlink

    @api_errors
    def rename(self, old, new):
        # 测试只支持小文件；复制后删除不具备原子重命名语义。
        if stat.S_ISDIR(self.getattr(old)['st_mode']):
            raise FuseOSError(errno.ENOTSUP)
        self.check_size(old)
        self.client.put(self.key(new), self.content(old))
        self.client.delete(self.key(old))
        return 0


if __name__ == '__main__':
    try:
        config = json.loads(sys.stdin.readline())
        workspace = Workspace(config)
        del config
        print('UPYUN_CONNECTED', flush=True)
        FUSE(workspace, '/workspace', foreground=True, nothreads=True,
             allow_other=True, default_permissions=True, attr_timeout=0, entry_timeout=0)
    except Exception as error:
        print('MOUNT_FAILED type=' + type(error).__name__ + ' status=' +
              str(getattr(error, 'status', 'unknown')), flush=True)
        sys.exit(1)
