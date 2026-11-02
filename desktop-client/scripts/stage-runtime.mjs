import { access, cp, mkdir, readFile, lstat, realpath, rm } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync, spawn } from 'node:child_process'
import electron from 'electron'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const host = path.resolve(
  root,
  '../backend-java/agent-runtime-desktop-host/build/install/agent-runtime-desktop-host'
)
const staging = path.join(root, 'staging')
const javaHome = process.env.JAVA_HOME
const targetArchitecture = process.argv[2] ?? process.arch
const expectedPlatform =
  process.platform === 'darwin' ? 'Darwin' : process.platform === 'win32' ? 'Windows' : 'Linux'
if (targetArchitecture !== process.arch)
  throw new Error('请在目标架构机器上打包，并提供同平台同架构的 Java 21 JDK')
if (!javaHome) throw new Error('打包需要 JAVA_HOME 指向当前平台的 Java 21 JDK')
const release = await readFile(path.join(javaHome, 'release'), 'utf8')
if (!/^JAVA_VERSION="21(?:\.|"|-)/m.test(release)) throw new Error('打包需要 Java 21 JDK')
const expectedArchitectures =
  process.arch === 'x64'
    ? ['amd64', 'x86_64']
    : process.arch === 'arm64'
      ? ['aarch64', 'arm64']
      : [process.arch]
const jdkArchitecture = /^OS_ARCH="([^"]+)"/m.exec(release)?.[1]
if (!jdkArchitecture || !expectedArchitectures.includes(jdkArchitecture))
  throw new Error('JDK 架构与当前打包进程不一致，请分别在对应架构构建')
if (!release.includes(`OS_NAME="${expectedPlatform}"`))
  throw new Error('JDK 平台与当前打包平台不一致')
await access(path.join(host, 'lib'))
await access(electron)
const electronIdentity = execFileSync(electron, ['-p', 'process.platform + "/" + process.arch'], {
  encoding: 'utf8',
  timeout: 10000,
  windowsHide: true,
  env: { ...process.env, ELECTRON_RUN_AS_NODE: '1' }
}).trim()
if (electronIdentity !== `${process.platform}/${targetArchitecture}`)
  throw new Error('已安装的 Electron 平台或架构与目标不一致，请在对应架构环境重新安装依赖')
try {
  const information = await lstat(staging)
  const resolved = await realpath(staging)
  const expected = path.join(await realpath(root), 'staging')
  const equal =
    process.platform === 'win32'
      ? resolved.toLowerCase() === expected.toLowerCase()
      : resolved === expected
  // 仅替换本模块拥有的生成目录；拒绝经符号链接或重解析点迁出的目标。
  if (information.isSymbolicLink() || !equal)
    throw new Error('staging 必须是本模块内的普通生成目录')
  await rm(staging, { recursive: true, force: true })
} catch (error) {
  if (error.code !== 'ENOENT') throw error
}
await mkdir(staging, { recursive: true })
await cp(path.join(host, 'lib'), path.join(staging, 'host', 'lib'), {
  recursive: true,
  force: true
})
const runtime = path.join(staging, 'jre')
await new Promise((resolve, reject) => {
  const jlink = spawn(
    path.join(javaHome, 'bin', process.platform === 'win32' ? 'jlink.exe' : 'jlink'),
    [
      '--add-modules',
      'java.se,jdk.crypto.ec,jdk.unsupported',
      '--strip-debug',
      '--no-header-files',
      '--no-man-pages',
      '--output',
      runtime
    ],
    { stdio: 'inherit', windowsHide: true }
  )
  jlink.once('error', reject)
  jlink.once('exit', (code) =>
    code === 0 ? resolve() : reject(new Error(`jlink 失败，退出码 ${code}`))
  )
})
console.log(`Host 与 Java 21 已准备：${staging}`)
