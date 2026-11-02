import assert from 'node:assert/strict'
import test from 'node:test'
import { resolve } from 'node:path'
import { findViolations } from './check-ui.mjs'

const file = resolve('frontend-chat/src/ui/controls/overlays.ts')

test('对象和类的确认方法声明不冒充浏览器调用', () => {
  assert.deepEqual(
    findViolations(
      file,
      'const box = { confirm(message: string) { return Promise.resolve(message) } }; class Box { prompt() {} }'
    ),
    []
  )
})

test('确认方法内部的浏览器调用仍被拒绝', () => {
  assert.equal(
    findViolations(
      file,
      'const box = { confirm(message: string) { return window.confirm(message) } };'
    ).length,
    1
  )
})

test('直接和全局浏览器对话框调用均被拒绝', () => {
  assert.equal(
    findViolations(
      file,
      'confirm("x"); alert("x"); prompt("x"); window.confirm("x"); globalThis["prompt"]("x");'
    ).length,
    5
  )
})
