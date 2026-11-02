import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'

const target = process.argv[2]
if (!target) throw new Error('用法：node tools/desktop-prototype/create-demo.mjs <新的示例目录>')
const directory = path.resolve(target)
// 不覆盖已有目录；示例只在调用者明确指定的新目录中生成。
await mkdir(directory, { recursive: false })
await writeFile(path.join(directory, 'package.json'), JSON.stringify({
  name: 'noewise-desktop-example', private: true, type: 'module',
  scripts: { test: 'node --test' },
}, null, 2) + '\n')
await writeFile(path.join(directory, 'sum.js'),
  'export function sum(a, b) {\n  return a - b\n}\n')
await writeFile(path.join(directory, 'sum.test.js'), [
  "import test from 'node:test'",
  "import assert from 'node:assert/strict'",
  "import { sum } from './sum.js'",
  '',
  "test('adds positive numbers', () => assert.equal(sum(2, 3), 5))",
  "test('adds signed numbers', () => assert.equal(sum(-2, 1), -1))",
  '',
].join('\n'))
console.log('示例已创建：' + directory)
console.log('在桌面原型中选择此目录，输入：修复 sum 的实现，运行 node --test 验证，并说明修改。')
