import { pathToFileURL } from 'node:url';

export async function checkGateway(base = process.env.FD_API_BASE || 'http://localhost:8080/fd/v1') {
  const checks = [
    ['FD clock', '/business-clock', 200, data => /^\d{4}-\d{2}-\d{2}$/.test(data.businessDate)],
    ['Reports', '/reports/fd-accounts?reportType=TOTAL_LIABILITY', 200, data => data.reportType === 'TOTAL_LIABILITY' && Array.isArray(data.rows)],
    ['Demo FX', '/exchange-rates?base=INR', 200, data => data.source === 'DEMO' && data.base === 'INR' && data.rates.INR === '1.000000000000'],
    ['Validation propagation', '/reports/fd-accounts?reportType=INVALID', 400, data => data.code === 'VALIDATION_ERROR'],
    ['Unknown route', '/unknown', 404, data => data.code === 'NOT_FOUND'],
    ['PDF download', '/reports/fd-accounts?reportType=TOTAL_LIABILITY&format=pdf', 200, (text, res) => res.headers.get('content-type')?.startsWith('application/pdf') && res.headers.get('content-disposition')?.includes('.pdf') && text.startsWith('%PDF-')],
    ['CSV download', '/reports/fd-accounts?reportType=TOTAL_LIABILITY&format=csv', 200, (text, res) => res.headers.get('content-type')?.startsWith('text/csv') && res.headers.get('content-disposition')?.includes('.csv') && text.includes('totalLiability')],
  ];
  const results = await Promise.all(checks.map(async ([name, path, expected, validate]) => {
    const start = Date.now();
    try {
      const response = await fetch(base.replace(/\/$/, '') + path, { signal: AbortSignal.timeout(12000) });
      const body = ['CSV download', 'PDF download'].includes(name) ? await response.text() : await response.json();
      return { name, pass: response.status === expected && Boolean(validate(body, response)), status: response.status, expected, durationMs: Date.now() - start };
    } catch (error) { return { name, pass:false, error:error.message, durationMs:Date.now()-start }; }
  }));
  return { base, pass:results.every(r => r.pass), checks:results };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const result = await checkGateway();
  if (!process.argv.includes('--json')) for (const check of result.checks) console.log(`${check.pass ? 'PASS' : 'FAIL'} ${check.name}: ${check.status ?? check.error}`);
  console.log(JSON.stringify(result, null, 2));
  if (!result.pass) process.exitCode = 1;
}
