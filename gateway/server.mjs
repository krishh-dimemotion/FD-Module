import http from 'node:http';
import https from 'node:https';
import { pathToFileURL } from 'node:url';

export function createGateway({ fdUpstream = process.env.FD_UPSTREAM || 'http://localhost:8081',
  reportUpstream = process.env.REPORT_UPSTREAM || 'http://localhost:8083',
  allowedOrigin = process.env.ALLOWED_ORIGIN || 'http://localhost:3000', timeout = 10000 } = {}) {
  const upstreams = { fd: new URL(fdUpstream), reports: new URL(reportUpstream) };
  for (const upstream of Object.values(upstreams)) {
    if (!['http:', 'https:'].includes(upstream.protocol) || upstream.username || upstream.password || upstream.pathname !== '/') {
      throw new Error('Upstreams must be HTTP(S) origins without credentials or paths');
    }
  }
  return http.createServer((req, res) => {
    if (req.headers.origin === allowedOrigin) res.setHeader('Access-Control-Allow-Origin', allowedOrigin);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Expose-Headers', 'Content-Disposition');
    const error = (status, code, message) => {
      if (res.headersSent) { res.destroy(); return; }
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ code, message }));
    };
    let url;
    try { url = new URL(req.url, 'http://gateway'); } catch { error(400, 'VALIDATION_ERROR', 'Invalid URL'); return; }
    if (url.pathname === '/health' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end('{"status":"UP","service":"gateway"}'); return;
    }
    const prefix = '/fd/v1';
    const path = url.pathname.slice(prefix.length);
    if (!url.pathname.startsWith(prefix + '/') || !/^\/(fd-accounts|batch-jobs|business-clock|gl-accounts|reports|exchange-rates)(?:\/|$)/.test(path)) {
      error(404, 'NOT_FOUND', 'Unknown gateway route'); return;
    }
    if (req.method === 'OPTIONS') {
      res.writeHead(204, { 'Access-Control-Allow-Methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type,Authorization' }); res.end(); return;
    }
    const origin = /^\/(reports|exchange-rates)(?:\/|$)/.test(path) ? upstreams.reports : upstreams.fd;
    const headers = { ...req.headers, host: origin.host };
    // Remove hop-by-hop headers, including any nominated by Connection.
    for (const name of ['connection','keep-alive','proxy-authenticate','proxy-authorization','te','trailer','transfer-encoding','upgrade',
      ...(req.headers.connection || '').toLowerCase().split(',').map(x => x.trim())]) delete headers[name];
    delete headers['x-forwarded-host']; delete headers['x-forwarded-for']; delete headers['x-forwarded-proto'];
    const proxy = (origin.protocol === 'https:' ? https : http).request(new URL(path + url.search, origin), {
      method: req.method, headers,
    }, upstream => {
      const responseHeaders = { ...upstream.headers };
      for (const name of ['connection','keep-alive','transfer-encoding', ...(upstream.headers.connection || '').toLowerCase().split(',').map(x => x.trim())]) delete responseHeaders[name];
      // The gateway owns browser CORS; do not forward conflicting upstream values.
      for (const name of Object.keys(responseHeaders)) if (name.startsWith('access-control-')) delete responseHeaders[name];
      delete responseHeaders.vary;
      res.writeHead(upstream.statusCode, responseHeaders);
      upstream.on('error', () => res.destroy());
      upstream.pipe(res);
    });
    const deadline = setTimeout(() => { error(504, 'UPSTREAM_TIMEOUT', 'Upstream request timed out'); proxy.destroy(); }, timeout);
    proxy.on('close', () => clearTimeout(deadline));
    proxy.on('error', () => { if (!res.writableEnded) error(502, 'UPSTREAM_UNAVAILABLE', 'Upstream service unavailable'); });
    req.on('aborted', () => proxy.destroy());
    res.on('close', () => { if (!res.writableEnded) proxy.destroy(); });
    req.pipe(proxy);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createGateway().listen(Number(process.env.PORT || 8080), '0.0.0.0', () => console.log('FD gateway listening on port ' + (process.env.PORT || 8080)));
}
