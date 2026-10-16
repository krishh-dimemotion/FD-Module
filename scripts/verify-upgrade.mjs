// Complete upgrade workflow against an explicitly isolated, disposable stack.
import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
const base = process.env.FD_API_BASE || 'http://localhost:8180/fd/v1';
assert.equal(process.env.FD_E2E_ISOLATED_DB, '1', 'Use only on an isolated synthetic database');
const manifestUrl = new URL('../output/upgrade-fixture.json',import.meta.url);
const pdfDirectory = new URL('../output/pdf/',import.meta.url);
async function api(method,path,body) {
  const res=await fetch(base+path,{method,headers:body===undefined?{}:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});
  const result=await res.json();assert(res.ok,`${method} ${path}: ${res.status} ${JSON.stringify(result)}`);return result;
}
let manifest;
const existing=await api('GET','/fd-accounts?size=200');
if(existing.totalElements) {
  manifest=JSON.parse(await readFile(manifestUrl,'utf8').catch(()=>{throw new Error('Nonempty database without upgrade manifest; refusing to post fixtures');}));
  assert.equal(manifest.base,base);assert.equal(existing.totalElements,manifest.ids.length);
} else {
  const book=(currency,principal,tenure=12,type='COMPOUND',customer='UPGRADE-'+currency)=>api('POST','/fd-accounts',{
    custId:customer,productCode:'FD-REG',principal,currencyCode:currency,tenureMonths:tenure,rateId:'RATE-'+tenure+'M',
    interestType:type,compoundingFreq:'QUARTERLY',payoutFreq:type==='SIMPLE'?'MONTHLY':'ON_MATURITY',
    initialRoles:[{custId:customer,roleType:'OWNER',isPrimary:true}]
  });
  await api('PUT','/business-clock',{businessDate:'2026-01-01'});
  const ids=[];
  for(const [currency,amount] of [['INR','1234567.89'],['USD','1000000.55'],['JPY','1000001'],['BHD','123456.789'],['KWD','900719925474099.123']]) {
    const a=await book(currency,amount);assert.equal(a.principalAmt,amount);assert.equal(a.taxEnabled,true);ids.push(a.fdaId);
  }
  const early=await book('BHD','100000.123',12,'SIMPLE','UPGRADE-WITHDRAW');ids.push(early.fdaId);
  const original=await book('KWD','100000.123',1,'COMPOUND','UPGRADE-RENEW');ids.push(original.fdaId);
  await api('PUT','/business-clock',{businessDate:'2026-02-01'});
  const renewed=await api('POST',`/fd-accounts/${original.fdaId}/renew`,{tenureMonths:12});ids.push(renewed.fdaId);
  assert.equal(renewed.renewedFromId,original.fdaId);assert.equal(renewed.taxEnabled,true);
  await api('PUT','/business-clock',{businessDate:'2026-07-15'});
  const quote=await api('POST',`/fd-accounts/${early.fdaId}/withdrawal-quote`);
  const closed=await api('POST',`/fd-accounts/${early.fdaId}/withdraw`,{expectedQuote:quote,remarks:'Synthetic upgrade fixture'});
  assert.equal(closed.withdrawalAmount,quote.withdrawalAmount);assert.equal(closed.taxDeducted,quote.taxDeducted);
  const accrual=await api('POST','/batch-jobs/ACCRUAL/runs',{businessDate:'2026-07-15'});assert.equal(accrual.skipCnt,0);
  const rerun=await api('POST','/batch-jobs/ACCRUAL/runs',{businessDate:'2026-07-15'});assert.equal(rerun.readCnt,0);
  manifest={base,ids,statementAccount:early.fdaId,renewed:renewed.fdaId,original:original.fdaId};
  await mkdir(new URL('../output/',import.meta.url),{recursive:true});await writeFile(manifestUrl,JSON.stringify(manifest,null,2));
}
await mkdir(pdfDirectory,{recursive:true});
const snapshot=async()=>({accounts:await Promise.all(manifest.ids.map(id=>api('GET','/fd-accounts/'+id))),
 transactions:await Promise.all(manifest.ids.map(id=>api('GET',`/fd-accounts/${id}/transactions?size=200`))),
 clock:await api('GET','/business-clock'),gl:await api('GET','/gl-accounts')});
const before=await snapshot();
for(const type of ['TOTAL_LIABILITY','PORTFOLIO_SUMMARY','MONTHLY_GROWTH','TDS_COMPLIANCE','MATURITY_SCHEDULE','ACCOUNT_STATEMENT']) {
  const params=new URLSearchParams({reportType:type});
  if(type==='ACCOUNT_STATEMENT')params.set('accountId',manifest.statementAccount);
  const report=await api('GET','/reports/fd-accounts?'+params);
  if(type==='TDS_COMPLIANCE') {
    assert(report.rows.length>0);assert(report.rows.every(r=>r.policyVersion && r.taxableAmount && r.pan===null));
    for(const r of report.rows) assert.equal(typeof r.netDeduction,'string');
  }
  for(const format of ['csv','pdf']) {
    const res=await fetch(base+'/reports/fd-accounts?'+params+'&format='+format);assert.equal(res.status,200);
    assert.equal(res.headers.get('cache-control'),'no-store');const bytes=Buffer.from(await res.arrayBuffer());
    if(format==='pdf') { assert(bytes.subarray(0,5).equals(Buffer.from('%PDF-')));await writeFile(new URL(type.toLowerCase()+'.pdf',pdfDirectory),bytes); }
    else assert(bytes.toString().includes('businessDate'));
  }
}
assert.deepEqual(await snapshot(),before,'Report generation mutated banking state');
for(const [currency,pattern] of [['JPY',/^\d+$/],['BHD',/^\d+\.\d{3}$/],['KWD',/^\d+\.\d{3}$/],['INR',/^\d+\.\d{2}$/]]) {
  const fx=await api('GET',`/exchange-rates/convert?from=USD&to=${currency}&amount=100`);assert.match(fx.convertedAmount,pattern);
}
console.log('PASS: five currencies, precise API strings, net renewal/withdrawal, posted tax, batch rerun, all report formats, FX, and unchanged read snapshots.');
console.log(JSON.stringify(manifest,null,2));
