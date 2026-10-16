// Exercise historical shared-GL attribution and repeatability on disposable synthetic data only.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
assert.equal(process.env.FD_E2E_ISOLATED_DB,'1');
assert(process.env.COMPOSE_FILE,'An explicit disposable COMPOSE_FILE is required');
function compose(args,input) {
  const r=spawnSync(resolve(root,'scripts/compose.sh'),['--project-directory',root,...args],{cwd:root,input,encoding:'utf8',maxBuffer:64*1024*1024});
  assert.equal(r.status,0,r.stderr);return r.stdout;
}
const config=JSON.parse(compose(['config','--format','json']));
assert.match(config.name,/smoke|migration-test/,'Migration verification is restricted to disposable projects');
function sql(q) { return compose(['exec','-T','mysql','sh','-c','MYSQL_PWD="$FD_DB_PASSWORD" mysql -N -B -r -ufd_app fd_db'],q); }
function snapshot() {
  return [
    sql("SELECT JSON_OBJECT('id',FDA_ID,'amount',CAST(FDA_PRINCIPAL_AMT AS CHAR),'balance',CAST(FDA_PRINCIPAL_BAL AS CHAR),'tax',CAST(FDA_TAX_ENABLED AS UNSIGNED),'accrued',CAST(FDA_ACCRUED_INT_AMT AS CHAR)) FROM FD_ACCOUNTS ORDER BY FDA_ID;"),
    sql("SELECT JSON_OBJECT('id',CAST(FDGE_ID AS CHAR),'code',FDGE_GL_CD,'amount',CAST(FDGE_AMT AS CHAR),'source',CAST(FDGE_FDT_ID AS CHAR)) FROM FD_GL_ENTRIES ORDER BY FDGE_ID;"),
    sql('SELECT * FROM FD_TAX_EVENTS ORDER BY SOURCE_FDT_ID;'),sql('SELECT * FROM FD_TAX_YEARS ORDER BY TAX_KEY;')
  ];
}
function migrate(mode,expect=0) {
  const r=spawnSync(process.execPath,[resolve(root,'scripts/migrate-money.mjs'),mode],{cwd:root,env:process.env,encoding:'utf8',maxBuffer:4*1024*1024});
  assert.equal(r.status,expect,r.stderr+'\n'+r.stdout);return r.stdout+r.stderr;
}
compose(['stop','fd','reports']);
const before=snapshot();
const accrual=sql("SELECT FDGE_ID FROM FD_GL_ENTRIES WHERE FDGE_FDT_ID IS NULL LIMIT 1;").trim();
assert.match(accrual,/^\d+$/,'Seed the disposable upgrade fixtures before migration verification');
// Refusal must be read-only and must not guess a currency for unknown accrual history.
sql(`UPDATE FD_GL_ENTRIES SET FDGE_NARRATIVE=CONCAT('UNRECOGNIZED ',FDGE_NARRATIVE) WHERE FDGE_ID=${accrual};`);
let refused;
try { refused=migrate('--check',1); }
finally { sql(`UPDATE FD_GL_ENTRIES SET FDGE_NARRATIVE=SUBSTRING(FDGE_NARRATIVE,14) WHERE FDGE_ID=${accrual};`); }
assert.match(refused,/Cannot attribute/);
assert.deepEqual(snapshot(),before);
// Simulate the previous shared INR GL design while retaining every monetary amount.
sql(`START TRANSACTION;
 UPDATE FD_GL_ENTRIES e JOIN FD_GL_ACCOUNTS g ON e.FDGE_GL_CD=g.FDGL_CD
 SET e.FDGE_GL_CD=LEFT(g.FDGL_CD,CHAR_LENGTH(g.FDGL_CD)-4) WHERE g.FDGL_CCY_CD<>'INR';
 UPDATE FD_GL_ACCOUNTS g LEFT JOIN
 (SELECT FDGE_GL_CD,SUM(CASE WHEN FDGE_DR_CR='D' THEN FDGE_AMT ELSE -FDGE_AMT END) s FROM FD_GL_ENTRIES GROUP BY FDGE_GL_CD) e
 ON e.FDGE_GL_CD=g.FDGL_CD SET g.FDGL_CURRENT_BAL=COALESCE(e.s,0)*CASE WHEN g.FDGL_TYP IN ('ASSET','EXPENSE') THEN 1 ELSE -1 END;
 COMMIT;`);
const check=migrate('--check');assert(!check.includes('0 entries require currency attribution.'),'Expected foreign entries to migrate');
migrate('--apply');assert.deepEqual(snapshot(),before,'Migration changed money, eligibility, assessments, or historical GL attribution');
migrate('--apply');assert.deepEqual(snapshot(),before,'Repeated migration changed history');
console.log('PASS: unknown-history refusal, mixed-currency historical attribution, exact values/eligibility/assessments preserved, and repeatable migration.');
