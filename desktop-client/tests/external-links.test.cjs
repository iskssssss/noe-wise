const test = require('node:test')
const assert = require('node:assert/strict')
const { externalUrl } = require('../electron/external-links.cjs')

test('外部链接只接受完整 HTTP(S) 地址，规范化后交给系统浏览器', () => {
  assert.equal(
    externalUrl('https://example.com/docs?q=1#heading'),
    'https://example.com/docs?q=1#heading'
  )
  assert.equal(externalUrl('http://127.0.0.1:3000'), 'http://127.0.0.1:3000/')
})
test('文件、脚本、数据、自定义协议、凭据与控制字符不能通过外部链接边界', () => {
  for (const value of [
    'file:///C:/secret.txt',
    'javascript:alert(1)',
    'data:text/html,test',
    'vscode://file/test',
    'shell:AppsFolder',
    'https://user:pass@example.com',
    'https://example.com\n',
    '/relative',
    null
  ]) {
    assert.throws(() => externalUrl(value))
  }
})
