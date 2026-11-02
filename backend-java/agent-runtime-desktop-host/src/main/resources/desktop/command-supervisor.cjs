'use strict';

// 命令只作为独立数据交给本机 shell；监督脚本不把它拼入自己的 JavaScript。
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { spawn } = require('node:child_process');

const directory = process.argv[2];
const nonce = process.argv[3];
const CANCEL_EXIT_CODE = 123;
const BOOTSTRAP_FAILURE_EXIT_CODE = 125;
const CLEANUP_FAILURE_EXIT_CODE = 126;
const POLL_MILLIS = 10;
const FORCE_AFTER_MILLIS = 3000;
const CLEANUP_TIMEOUT_MILLIS = 4000;
const initialParent = process.ppid;
const permittedEnvironment = new Set([
  'PATH', 'HOME', 'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL', 'TERM', 'USER', 'SHELL',
]);

let child;
let groupId;
let started = false;
let childExited = false;
let stopping = false;
let stopRequested = false;
let cleanupStarted;
let forced = false;
let exitCode = BOOTSTRAP_FAILURE_EXIT_CODE;
let timer;

function cancelled() {
  try {
    return fs.readFileSync(path.join(directory, 'cancel'), 'utf8') === nonce;
  } catch (error) {
    if (error.code === 'ENOENT') return false;
    throw error;
  }
}

function groupAlive() {
  if (groupId === undefined) return false;
  try {
    process.kill(-groupId, 0);
    return true;
  } catch (error) {
    if (error.code === 'ESRCH') return false;
    throw error;
  }
}

function signalGroup(signal) {
  if (groupId === undefined) return;
  try {
    process.kill(-groupId, signal);
  } catch (error) {
    if (error.code !== 'ESRCH') throw error;
  }
}

function parentAlive() {
  if (initialParent <= 1) return false;
  try {
    process.kill(initialParent, 0);
    return true;
  } catch (error) {
    if (error.code === 'ESRCH') return false;
    throw error;
  }
}

function finish(confirmed) {
  clearInterval(timer);
  if (confirmed) {
    const receipt = { nonce, started, exitCode, processGroupGone: true };
    const descriptor = fs.openSync(path.join(directory, 'receipt.json'), 'wx', 0o600);
    try {
      fs.writeFileSync(descriptor, JSON.stringify(receipt), 'utf8');
      fs.fsyncSync(descriptor);
    } finally {
      fs.closeSync(descriptor);
    }
  }
  process.exit(confirmed ? exitCode : CLEANUP_FAILURE_EXIT_CODE);
}

function beginCleanup() {
  if (stopping) return;
  stopping = true;
  cleanupStarted = performance.now();
  signalGroup('SIGTERM');
}

function requestStop() {
  stopRequested = true;
  exitCode = CANCEL_EXIT_CODE;
  beginCleanup();
}

function tick() {
  try {
    if (cancelled() || !parentAlive()) requestStop();
    if (!stopping) return;
    if (!groupAlive() && childExited) return finish(true);
    const elapsed = performance.now() - cleanupStarted;
    if (!forced && elapsed >= FORCE_AFTER_MILLIS) {
      forced = true;
      signalGroup('SIGKILL');
    }
    if (elapsed >= CLEANUP_TIMEOUT_MILLIS) finish(false);
  } catch (error) {
    process.stderr.write('POSIX command supervisor cleanup failed: ' + (error.code || error.name) + '\n');
    try { signalGroup('SIGKILL'); } catch (_) { /* 保持缺失回执，不能声称已清理。 */ }
    finish(false);
  }
}

process.on('SIGTERM', requestStop);
process.on('SIGINT', requestStop);

try {
  if (process.platform === 'win32') throw new Error('POSIX supervisor requires a POSIX platform');
  if (cancelled() || initialParent <= 1) {
    childExited = true;
    requestStop();
  } else {
    const environment = Object.fromEntries(Object.entries(process.env)
      .filter(([name]) => permittedEnvironment.has(name)));
    const command = fs.readFileSync(path.join(directory, 'command.txt'), 'utf8');
    const shell = process.platform === 'darwin' ? '/bin/bash' : '/bin/sh';
    const args = process.platform === 'darwin' ? ['--noprofile', '--norc', '-c', command] : ['-c', command];
    // libuv 的 detached 在 exec 之前创建新 session/进程组，避免启动后再枚举后代的竞态。
    child = spawn(shell, args, { detached: true, stdio: ['ignore', 'inherit', 'inherit'], env: environment });
    child.once('spawn', () => {
      groupId = child.pid;
      started = true;
      if (stopRequested) signalGroup('SIGTERM');
    });
    child.once('error', error => {
      process.stderr.write('POSIX command could not start: ' + (error.code || error.name) + '\n');
      childExited = true;
      exitCode = BOOTSTRAP_FAILURE_EXIT_CODE;
      beginCleanup();
    });
    child.once('exit', (code, signal) => {
      childExited = true;
      if (!stopRequested) exitCode = code === null ? 128 + (os.constants.signals[signal] || 0) : code;
      beginCleanup();
    });
  }
  timer = setInterval(tick, POLL_MILLIS);
} catch (error) {
  process.stderr.write('POSIX command supervisor bootstrap failed: ' + (error.code || error.name) + '\n');
  childExited = true;
  beginCleanup();
  timer = setInterval(tick, POLL_MILLIS);
}
