// Creates synthetic accounts only on an explicitly isolated, empty local lab database.
// A saved manifest makes subsequent runs verify the same fixture without adding accounts.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
const base = process.env.FD_API_BASE || 'http://localhost:8080/fd/v1';
const manifestPath = new URL('../output/demo-fixture.json', import.meta.url);
const api = async (method, path, body) => {
  const response = await fetch(base + path, { method, signal:AbortSignal.timeout(30000),
    headers: body === undefined ? {} : {'Content-Type':'application/json'}, body:body===undefined?undefined:JSON.stringify(body) });
  const data = await response.json(); assert(response.ok, `${method} ${path}: ${response.status} ${JSON.stringify(data)}`); return data;
};
assert.equal(process.env.FD_E2E_ISOLATED_DB, '1', 'Set FD_E2E_ISOLATED_DB=1 only for your isolated local lab database.');
const existing = await api('GET','/fd-accounts?size=200');
let fixture;
if (existing.totalElements) {
  fixture = JSON.parse(await readFile(manifestPath, 'utf8').catch(() => { throw new Error('Database is nonempty and has no fixture manifest. Use a fresh isolated stack.'); }));
  assert.equal(fixture.base, base, 'Manifest belongs to another API base');
  assert.equal(existing.totalElements, 5, 'Unexpected accounts in the fixture database');
} else {
  await api('PUT','/business-clock',{businessDate:'2026-01-01'});
  const create = (principal, tenure, type = 'COMPOUND', payout = 'ON_MATURITY') => api('POST','/fd-accounts',{
    custId:'CUST-001',productCode:'FD-REG',principal,tenureMonths:tenure,rateId:`RATE-${tenure}M`,currencyCode:'INR',interestType:type,
    compoundingFreq:'MONTHLY',payoutFreq:payout,initialRoles:[{custId:'CUST-001',roleType:'OWNER',isPrimary:true},{custId:'CUST-003',roleType:'NOMINEE',isPrimary:false}]
  });
  const payout=await create(100000,1), original=await create(100000,1), withdrawal=await create(50000,12), active=await create(75000,12,'SIMPLE','MONTHLY');
  await api('PUT','/business-clock',{businessDate:'2026-01-16'});
  const quote=await api('POST',`/fd-accounts/${withdrawal.fdaId}/withdrawal-quote`);
  await api('POST',`/fd-accounts/${withdrawal.fdaId}/withdraw`,{expectedQuote:quote,remarks:'Synthetic lab withdrawal'});
  await api('PUT','/business-clock',{businessDate:'2026-02-01'});
  const renewed=await api('POST',`/fd-accounts/${original.fdaId}/renew`,{tenureMonths:6});
  await api('POST','/batch-jobs/MATURITY/runs',{businessDate:'2026-02-01'});
  await api('PUT','/business-clock',{businessDate:'2026-02-02'});
  await api('POST','/batch-jobs/ACCRUAL/runs',{businessDate:'2026-02-02'});
  fixture={base,payout:payout.fdaId,original:original.fdaId,renewed:renewed.fdaId,withdrawal:withdrawal.fdaId,active:active.fdaId};
  await mkdir(new URL('../output/',import.meta.url),{recursive:true}); await writeFile(manifestPath,JSON.stringify(fixture,null,2));
}
const accounts = await Promise.all([fixture.payout,fixture.original,fixture.renewed,fixture.withdrawal,fixture.active].map(id=>api('GET',`/fd-accounts/${id}`)));
assert.deepEqual(accounts.map(a=>a.status),['MATURED_CLOSED','MATURED_RENEWED','ACTIVE','CLOSED','ACTIVE']);
assert.equal(accounts[2].renewedFromId,fixture.original);
const payoutTx = await api('GET',`/fd-accounts/${fixture.payout}/transactions?size=200`);
const rerun = await api('POST','/batch-jobs/MATURITY/runs',{businessDate:'2026-02-02'});
assert.equal(rerun.readCnt,0);
assert.equal((await api('GET',`/fd-accounts/${fixture.payout}/transactions?size=200`)).totalElements,payoutTx.totalElements);
const before = await Promise.all(accounts.map(a=>api('GET',`/fd-accounts/${a.fdaId}/transactions?size=200`)));
const clock = await api('GET','/business-clock'); const gl = await api('GET','/gl-accounts');
for (const type of ['PORTFOLIO_SUMMARY','TOTAL_LIABILITY','MONTHLY_GROWTH','TDS_COMPLIANCE','MATURITY_SCHEDULE','ACCOUNT_STATEMENT']) {
  const params = new URLSearchParams({reportType:type,custId:'CUST-001'});
  if (type==='MONTHLY_GROWTH') { params.set('fromDate','2026-01-01'); params.set('toDate','2026-02-02'); }
  if (type==='ACCOUNT_STATEMENT') params.set('accountId',fixture.payout);
  const report=await api('GET','/reports/fd-accounts?'+params);
  if (type==='ACCOUNT_STATEMENT') assert.equal(report.totals[0].closingBalance,'0.00');
  if (type==='MONTHLY_GROWTH') {
    assert.equal(report.rows[0].newDeposits,'325000.00');
    assert.equal(report.rows[1].newDeposits,'0.00');
    assert.equal(report.rows[1].renewalIn,report.rows[1].renewalOut);
  }
  const csv=await fetch(base+'/reports/fd-accounts?'+params+'&format=csv'); assert.equal(csv.status,200); assert((await csv.text()).includes('businessDate'));
}
assert.deepEqual(await Promise.all(accounts.map(a=>api('GET',`/fd-accounts/${a.fdaId}/transactions?size=200`))),before);
assert.deepEqual(await api('GET','/business-clock'),clock); assert.deepEqual(await api('GET','/gl-accounts'),gl);
assert.deepEqual(await Promise.all(accounts.map(a=>api('GET',`/fd-accounts/${a.fdaId}`))),accounts);
assert.equal((await api('GET','/exchange-rates/convert?from=USD&to=INR&amount=100')).convertedAmount,'8333.33');
console.log('PASS: account creation, withdrawal, maturity idempotency, linked renewal, all reports, CSV, demo FX, and read-only report behavior.');
console.log(JSON.stringify(fixture,null,2));
