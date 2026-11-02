import { describe, expect, it } from 'vitest'
import { browserPairingAddress } from './browserPairing'

describe('官方扩展配对地址', () => {
  it('保留本机 WebSocket 地址中的个人配对片段', () => {
    const address = 'ws://127.0.0.1:18300/extension/owner#token=synthetic-pair'
    expect(browserPairingAddress(address)).toBe(address)
  })

  it('接受远程 WSS 与其他 loopback 地址', () => {
    for (const address of [
      'wss://browser.example.com/extension/owner#token=synthetic-pair',
      'ws://localhost:18300/extension/owner#token=synthetic-pair',
      'ws://[::1]:18300/extension/owner#token=synthetic-pair'
    ]) {
      expect(browserPairingAddress(address)).toBe(address)
    }
  })

  it('拒绝网页链接、脚本、带用户名密码及非 loopback 明文地址', () => {
    for (const address of [
      'https://browser.example.com/extension/owner',
      'javascript:alert(1)',
      'wss://user:password@browser.example.com/extension/owner',
      'ws://browser.example.com/extension/owner',
      '/extension/owner'
    ]) {
      expect(() => browserPairingAddress(address)).toThrow()
    }
  })
})
