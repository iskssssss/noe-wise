/* Two fresh real Vite consumers. API responses are never mocked or rewritten. */
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { writeFile } from 'node:fs/promises';

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(resolve(repo, 'frontend-admin/package.json'));
const { createServer } = await import(pathToFileURL(require.resolve('vite')).href);
const target = process.env.FULL_TEST_GUARD_PROXY;
const receipt = process.env.FULL_TEST_FRONTEND_RECEIPT;
if (!target || !receipt || !/^http:\/\/127\.0\.0\.1:\d+$/.test(target)) {
  throw new Error('private_guard_proxy_and_receipt_required');
}
const servers = [];
async function start(name, origin) {
  const server = await createServer({
    root: resolve(repo, name),
    configFile: resolve(repo, name, 'vite.config.ts'),
    server: {
      host: '127.0.0.1', port: 0, strictPort: false,
      proxy: {
        '/api': {
          target, changeOrigin: true,
          configure(proxy) {
            proxy.on('proxyReq', request => {
              // Isolated random-port consumers use existing allowed local origins.
              // Credentials, HMAC path/body and real service responses stay intact.
              request.setHeader('Origin', origin);
            });
          }
        },
        '/health': { target, changeOrigin: true }
      }
    }
  });
  servers.push(server);
  await server.listen();
  const address = server.httpServer.address();
  if (!address || typeof address === 'string') throw new Error('vite_bound_address_required');
  return `http://127.0.0.1:${address.port}`;
}
try {
  const admin = await start('frontend-admin', 'http://localhost:3000');
  const chat = await start('frontend-chat', 'http://localhost:3200');
  await writeFile(receipt, JSON.stringify({ schema_version: 1, pid: process.pid,
    admin_origin: admin, chat_origin: chat, guard_proxy: target,
    real_responses_only: true, origin_adaptation: ['http://localhost:3000', 'http://localhost:3200'] }, null, 2));
  const close = async () => {
    await Promise.allSettled(servers.map(server => server.close()));
    process.exit(0);
  };
  process.on('SIGTERM', close);
  process.on('SIGINT', close);
} catch (error) {
  await Promise.allSettled(servers.map(server => server.close()));
  process.stderr.write(`frontend_bridge_failed:${error?.name || 'Error'}\n`);
  process.exitCode = 1;
}
