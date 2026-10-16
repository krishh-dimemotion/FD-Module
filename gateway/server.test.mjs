import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createGateway } from './server.mjs';

async function serve(t, server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  return `http://127.0.0.1:${server.address().port}`;
}
test('routes by service and preserves paths, queries, bodies, status, and CSV headers', async t => {
  const requests = [];
  const upstream = name => http.createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    requests.push({ name, method: req.method, url: req.url, body: Buffer.concat(chunks).toString() });
    res.writeHead(201, { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="demo.csv"' });
    res.end('currency,amount\r\nINR,100.00\r\n');
  });
  const fd = await serve(t, upstream('fd')); const reports = await serve(t, upstream('reports'));
  const gateway = await serve(t, createGateway({ fdUpstream: fd, reportUpstream: reports }));
  for (const [path, name] of [['fd-accounts','fd'],['business-clock/advance','fd'],['batch-jobs/MATURITY/runs','fd'],['gl-accounts','fd'],['reports/fd-accounts','reports'],['exchange-rates/convert','reports']]) {
    const res = await fetch(`${gateway}/fd/v1/${path}?q=A%26B`, { method: 'POST', body: '{"amount":"1.00"}', headers: { 'Content-Type':'application/json' } });
    assert.equal(res.status, 201); assert.equal(res.headers.get('content-disposition'), 'attachment; filename="demo.csv"');
    assert.equal(await res.text(), 'currency,amount\r\nINR,100.00\r\n');
    assert.deepEqual(requests.at(-1), { name, method:'POST', url:`/${path}?q=A%26B`, body:'{"amount":"1.00"}' });
  }
});
test('owns CORS and preflight, and rejects unknown routes', async t => {
  const upstream = await serve(t, http.createServer((req,res) => { res.setHeader('Access-Control-Allow-Origin', '*'); res.end('{}'); }));
  const gateway = await serve(t, createGateway({ fdUpstream:upstream, reportUpstream:upstream }));
  const preflight = await fetch(gateway + '/fd/v1/fd-accounts', { method:'OPTIONS', headers:{Origin:'http://localhost:3000'} });
  assert.equal(preflight.status,204); assert.equal(preflight.headers.get('access-control-allow-origin'),'http://localhost:3000');
  const allowed = await fetch(gateway + '/fd/v1/fd-accounts', {headers:{Origin:'http://localhost:3000'}});
  assert.equal(allowed.headers.get('access-control-allow-origin'),'http://localhost:3000');
  const denied = await fetch(gateway + '/fd/v1/fd-accounts', {headers:{Origin:'https://elsewhere.example'}});
  assert.equal(denied.headers.get('access-control-allow-origin'),null);
  for (const path of ['/fd/v1/reports-other','/fd/v1/private','/fd/v11/fd-accounts']) assert.equal((await fetch(gateway+path)).status,404);
});
test('unavailable upstreams return 502 and deadlines return 504', async t => {
  const dummy = http.createServer(); await new Promise(resolve => dummy.listen(0,'127.0.0.1',resolve));
  const closed = `http://127.0.0.1:${dummy.address().port}`; await new Promise(resolve=>dummy.close(resolve));
  const slow = await serve(t, http.createServer(() => {}));
  const gateway = await serve(t, createGateway({fdUpstream:closed,reportUpstream:slow,timeout:50}));
  const unavailable = await fetch(gateway+'/fd/v1/business-clock'); assert.equal(unavailable.status,502); assert.equal((await unavailable.json()).code,'UPSTREAM_UNAVAILABLE');
  const timeout = await fetch(gateway+'/fd/v1/reports/fd-accounts'); assert.equal(timeout.status,504); assert.equal((await timeout.json()).code,'UPSTREAM_TIMEOUT');
});
test('upstream validation errors pass through unchanged', async t => {
  const upstream = await serve(t,http.createServer((req,res)=>{res.writeHead(400,{'Content-Type':'application/json'});res.end('{"code":"VALIDATION_ERROR","message":"bad date"}');}));
  const gateway = await serve(t,createGateway({fdUpstream:upstream,reportUpstream:upstream}));
  const response=await fetch(gateway+'/fd/v1/reports/fd-accounts'); assert.equal(response.status,400);assert.equal((await response.json()).message,'bad date');
});

test('forwards PDF bytes and attachment headers without changing binary data', async t => {
  const bytes = Buffer.from([37,80,68,70,45,49,46,55,10,0,255,128,10]);
  const reports = await serve(t, http.createServer((req,res) => {
    assert.equal(req.url, '/reports/fd-accounts?reportType=TDS_COMPLIANCE&format=pdf');
    res.writeHead(200, {'Content-Type':'application/pdf','Content-Disposition':'attachment; filename="tax.pdf"','Cache-Control':'no-store'});
    res.end(bytes);
  }));
  const gateway = await serve(t, createGateway({ fdUpstream: reports, reportUpstream: reports }));
  const result = await fetch(gateway+'/fd/v1/reports/fd-accounts?reportType=TDS_COMPLIANCE&format=pdf');
  assert.equal(result.headers.get('content-type'),'application/pdf');
  assert.equal(result.headers.get('content-disposition'),'attachment; filename="tax.pdf"');
  assert.equal(result.headers.get('cache-control'),'no-store');
  assert.deepEqual(Buffer.from(await result.arrayBuffer()),bytes);
});
