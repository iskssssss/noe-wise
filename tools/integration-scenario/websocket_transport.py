"""仅用于loopback场景的真实WebSocket文本帧；业务路由与状态由模拟服务持有。"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import os
import socket
import struct

LOG = logging.getLogger(__name__)
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_FRAME = 1024 * 1024


def accept_key(key: str) -> str:
    """校验16字节握手nonce并计算RFC6455接收值；该nonce不承担业务认证。"""
    LOG.debug("计算模拟WebSocket握手: phase=entry")
    if len(base64.b64decode(key, validate=True)) != 16:
        raise ValueError("WEBSOCKET_KEY_INVALID")
    result = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()
    LOG.debug("计算模拟WebSocket握手: phase=exit")
    return result


def read_exact(stream, count: int) -> bytes:
    """读取完整帧字段，半途断线拒绝解析为完整业务结果。"""
    LOG.debug("读取模拟WebSocket字段: phase=entry bytes=%s", count)
    result = bytearray()
    while len(result) < count:
        part = stream.read(count - len(result))
        if not part:
            raise EOFError("WEBSOCKET_DISCONNECTED")
        result.extend(part)
    LOG.debug("读取模拟WebSocket字段: phase=exit bytes=%s", count)
    return bytes(result)


def read_frame(stream, masked: bool) -> tuple[int, bytes]:
    """只处理有界完整帧；拒绝错误方向mask、分片及未声明协议扩展。"""
    LOG.debug("读取模拟WebSocket帧: phase=entry")
    first, second = read_exact(stream, 2)
    if not first & 0x80 or first & 0x70 or bool(second & 0x80) != masked:
        raise ValueError("BOUNDED_UNFRAGMENTED_WEBSOCKET_FRAME_REQUIRED")
    size = second & 0x7F
    if size == 126:
        size = struct.unpack("!H", read_exact(stream, 2))[0]
    elif size == 127:
        size = struct.unpack("!Q", read_exact(stream, 8))[0]
    if size > MAX_FRAME:
        raise ValueError("WEBSOCKET_FRAME_TOO_LARGE")
    mask = read_exact(stream, 4) if masked else b""
    payload = read_exact(stream, size)
    if masked:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    LOG.debug("读取模拟WebSocket帧: phase=exit bytes=%s", size)
    return first & 0xF, payload


def send_frame(stream, payload: bytes, masked: bool, opcode=1):
    """编码单个有界文本/控制帧；客户端mask按连接消息独立随机生成。"""
    LOG.debug("发送模拟WebSocket帧: phase=entry bytes=%s", len(payload))
    if len(payload) > MAX_FRAME:
        raise ValueError("WEBSOCKET_FRAME_TOO_LARGE")
    mask_bit, size = 0x80 if masked else 0, len(payload)
    header = bytes((0x80 | opcode, mask_bit | size)) if size < 126 else bytes((0x80 | opcode, mask_bit | (126 if size < 65536 else 127)))
    if size >= 126:
        header += struct.pack("!H" if size < 65536 else "!Q", size)
    mask = os.urandom(4) if masked else b""
    if masked:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    stream.write(header + mask + payload)
    stream.flush()
    LOG.debug("发送模拟WebSocket帧: phase=exit")


def roundtrip(port: int, request: dict) -> dict:
    """向本地模拟业务服务建立真实WebSocket并传递一次公开消息快照。"""
    LOG.debug("调用模拟WebSocket: phase=entry")
    key = base64.b64encode(os.urandom(16)).decode()
    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection, connection.makefile("rwb") as stream:
        stream.write((f"GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\n"
                      f"Connection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {key}\r\n\r\n").encode())
        stream.flush()
        if b" 101 " not in stream.readline():
            raise ValueError("WEBSOCKET_UPGRADE_REQUIRED")
        headers = {}
        while True:
            line = stream.readline()
            if line == b"\r\n":
                break
            name, _, value = line.decode().partition(":")
            headers[name.lower()] = value.strip()
        if headers.get("sec-websocket-accept") != accept_key(key):
            raise ValueError("WEBSOCKET_HANDSHAKE_MISMATCH")
        send_frame(stream, json.dumps(request, ensure_ascii=False).encode(), True)
        opcode, payload = read_frame(stream, False)
        if opcode != 1:
            raise ValueError("WEBSOCKET_TEXT_RESPONSE_REQUIRED")
        result = json.loads(payload)
        send_frame(stream, b"", True, opcode=8)
    LOG.debug("调用模拟WebSocket: phase=exit")
    return result
