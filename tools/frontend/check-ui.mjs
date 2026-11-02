#!/usr/bin/env node
/**
 * 前端 UI 静态门禁：业务代码不得重新引入已迁移的 Ui 控件、AgentForgeUi，
 * 也不得直接调用浏览器阻塞式对话框。
 *
 * Element Plus（El*）和领域组件是当前允许的组件语义；
 * 输入类型由这些组件自身表达，本检查不限制其专业输入语义。
 *
 * 用法：node tools/frontend/check-ui.mjs
 */
import { readdir, readFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const applications = ['frontend-admin', 'frontend-platform', 'frontend-chat', 'frontend-shared']
const sourceExtensions = new Set(['.vue', '.ts', '.html'])
const ignoredDirectories = new Set([
  'node_modules',
  'dist',
  'coverage',
  'playwright-report',
  'test-results'
])
const testArtifact =
  /(?:^|\/)(?:test|tests|__tests__|e2e)(?:\/|$)|(?:^|\/)[^/]*(?:\.test|\.spec)(?:\.[^/]+)*$|(?:^|\/)[^/]*(?:mock|stub)[^/]*\.(?:vue|ts|html)$/i
const prohibitedFramework = /(?:@vben(?:-core)?\/|@skillforge\/shared\/console|useVben[A-Z]\w*)/g
const prohibitedChatShared = /(?:@skillforge\/shared|frontend-shared)/g
const prohibitedLegacyUi = /\b(?:AgentForgeUi|Ui[A-Z][\w-]*)\b/g
const prohibitedDirectBrowserDialogs = /(?<![\w$.])(?:alert|confirm|prompt)\s*(?:\?\.)?\s*\(/g
const prohibitedGlobalBrowserDialogs =
  /\b(?:window|globalThis)(?:\s*(?:\?\.|\.)\s*(?:alert|confirm|prompt)\b|\s*(?:\?\.)?\s*\[\s*["'](?:alert|confirm|prompt)["']\s*\])\s*(?:\?\.)?\s*\(/g

async function collectFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const files = await Promise.all(
    entries.map(async (entry) => {
      const target = resolve(directory, entry.name)
      if (entry.isDirectory()) return ignoredDirectories.has(entry.name) ? [] : collectFiles(target)
      return entry.isFile() && sourceExtensions.has(extensionOf(entry.name)) ? [target] : []
    })
  )
  return files.flat()
}

function extensionOf(file) {
  return file.slice(file.lastIndexOf('.'))
}

function lineAt(source, offset) {
  return source.slice(0, offset).split('\n').length
}

function removeComments(source) {
  return source
    .replace(/<!--([\s\S]*?)-->/g, (match) => match.replace(/[^\n]/g, ' '))
    .replace(/\/\*([\s\S]*?)\*\//g, (match) => match.replace(/[^\n]/g, ' '))
    .replace(
      /(^|[^:])\/\/[^\n]*/gm,
      (match, prefix) => `${prefix}${' '.repeat(match.length - prefix.length)}`
    )
}

export function findViolations(file, source) {
  const violations = []
  const withoutComments = removeComments(source)
  // 对象/类方法的声明不是浏览器调用；方法体内的真正调用仍由下方规则检查。
  const methodDeclarations = new Set()
  const syntax = ts.createSourceFile(file, withoutComments, ts.ScriptTarget.Latest, true)
  function visit(node) {
    if (ts.isMethodDeclaration(node) && node.body)
      methodDeclarations.add(node.name.getStart(syntax))
    ts.forEachChild(node, visit)
  }
  visit(syntax)
  // 从公共反馈显式导入的 confirm 是 Element Plus 确认接口，不是 window.confirm。
  const importedConfirm = Array.from(
    source.matchAll(/import\s*\{[^}]*\bconfirm\b[^}]*\}\s*from\s*["']([^"']+)["']/g)
  ).some(
    ([, specifier]) =>
      specifier === '@skillforge/shared/feedback' ||
      (specifier.startsWith('.') &&
        resolve(dirname(file), specifier) === resolve(root, 'frontend-shared/src/feedback'))
  )
  for (const { expression, message } of [
    { expression: prohibitedFramework, message: '禁止重新引入已移除的后台框架' },
    ...(file.includes('frontend-chat')
      ? [{ expression: prohibitedChatShared, message: '会话端必须独立于后台共享层' }]
      : []),
    { expression: prohibitedLegacyUi, message: '禁止重新引入旧 Ui 控件或 AgentForgeUi' },
    { expression: prohibitedDirectBrowserDialogs, message: '禁止直接调用浏览器对话框' },
    { expression: prohibitedGlobalBrowserDialogs, message: '禁止直接调用浏览器对话框' }
  ]) {
    expression.lastIndex = 0
    let match
    while ((match = expression.exec(withoutComments))) {
      if (expression === prohibitedDirectBrowserDialogs && methodDeclarations.has(match.index))
        continue
      if (
        importedConfirm &&
        expression === prohibitedDirectBrowserDialogs &&
        match[0].startsWith('confirm')
      )
        continue
      violations.push({
        file,
        line: lineAt(source, match.index),
        message: `${message} ${match[0].trim()}`
      })
    }
  }
  return violations
}

async function main() {
  const businessSourceFiles = (
    await Promise.all(applications.map((application) => collectFiles(resolve(root, application))))
  )
    .flat()
    .filter((file) => !testArtifact.test(relative(root, file).replaceAll('\\', '/')))

  const violations = (
    await Promise.all(
      businessSourceFiles.map(async (file) => findViolations(file, await readFile(file, 'utf8')))
    )
  ).flat()

  if (violations.length) {
    console.error(`[FAIL] UI 门禁发现 ${violations.length} 处违规：`)
    for (const violation of violations.sort(
      (a, b) => a.file.localeCompare(b.file) || a.line - b.line
    )) {
      console.error(
        `${relative(root, violation.file).replaceAll('\\', '/')}:${violation.line}: ${violation.message}`
      )
    }
    process.exitCode = 1
  } else {
    console.log(`[OK] UI 门禁通过：检查 ${businessSourceFiles.length} 个业务源文件。`)
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main()
