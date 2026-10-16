#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { mkdirSync, openSync, closeSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const apply = process.argv.includes('--apply');
if (process.argv.some(x => !['--apply', '--check'].includes(x) && x !== process.argv[0] && x !== process.argv[1])) throw new Error('Use --check or --apply');
function compose(args, options = {}) {
  const result = spawnSync(resolve(root, 'scripts/compose.sh'), ['--project-directory',root,...args], { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...options });
  if (result.status !== 0) throw new Error(result.stderr || 'Compose command failed');
  return result.stdout;
}
function sql(query) {
  return compose(['exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$FD_DB_PASSWORD" mysql -ufd_app --batch --raw --skip-column-names fd_db'], { input: query });
}
const literal = value => value === null ? 'NULL' : `CONVERT(0x${Buffer.from(String(value)).toString('hex')} USING utf8mb4)`;
function rows(query) { return sql(query).trim().split('\n').filter(Boolean).map(JSON.parse); }
function preflight() {
  const accounts = rows("SELECT JSON_OBJECT('id',FDA_ID,'number',FDA_ACCT_NUM,'currency',FDA_CCY_CD) FROM FD_ACCOUNTS;");
  const byId = new Map(accounts.map(a => [a.id, a])), byNumber = new Map(accounts.map(a => [a.number, a]));
  const txns = rows("SELECT JSON_OBJECT('id',CAST(FDT_ID AS CHAR),'account',FDA_ID,'currency',FDT_CCY_CD) FROM FD_TRANSACTIONS;");
  const byTxn = new Map(txns.map(t => [t.id, t]));
  for (const t of txns) if (!byId.has(t.account) || t.currency !== byId.get(t.account).currency) throw new Error(`Transaction currency mismatch: ${t.id}`);
  const gl = rows("SELECT JSON_OBJECT('code',FDGL_CD,'name',FDGL_NAME,'type',FDGL_TYP,'currency',FDGL_CCY_CD,'balance',CAST(FDGL_CURRENT_BAL AS CHAR)) FROM FD_GL_ACCOUNTS;");
  const byGl = new Map(gl.map(g => [g.code, g]));
  const entries = rows("SELECT JSON_OBJECT('id',CAST(FDGE_ID AS CHAR),'code',FDGE_GL_CD,'source',CAST(FDGE_FDT_ID AS CHAR),'narrative',FDGE_NARRATIVE) FROM FD_GL_ENTRIES;");
  const changes = [], definitions = new Map();
  for (const e of entries) {
    let account;
    if (e.source !== null) account = byId.get(byTxn.get(e.source)?.account);
    else {
      const match = /^(?:INTEREST_ACCRUAL|INTEREST_ACCRUAL_REVERSAL) (\S+) to \d{4}-\d{2}-\d{2}$/.exec(e.narrative || '');
      if (match) account = byNumber.get(match[1]);
    }
    if (!account) throw new Error(`Cannot attribute GL entry ${e.id}; no changes applied. Supply an explicit migration mapping first.`);
    const ledger = byGl.get(e.code);
    if (!ledger) throw new Error(`Missing GL definition ${e.code}`);
    const currency = account.currency;
    try { new Intl.NumberFormat('en', { style: 'currency', currency }).format(0); } catch { throw new Error(`Invalid currency on ${account.number}`); }
    const code = ledger.currency === currency ? ledger.code : ledger.code + '_' + currency;
    if (code.length > 64) throw new Error(`GL code exceeds 64 characters: ${code}`);
    if (byGl.has(code) && byGl.get(code).currency !== currency) throw new Error(`Conflicting GL currency: ${code}`);
    if (code !== e.code) { definitions.set(code, { ...ledger, code, currency }); changes.push({ id: e.id, code }); }
  }
  // Existing balances must already reconcile before any rebuilding occurs.
  const gaps = sql(`SELECT COUNT(*) FROM FD_GL_ACCOUNTS g LEFT JOIN
    (SELECT FDGE_GL_CD,SUM(CASE WHEN FDGE_DR_CR='D' THEN FDGE_AMT ELSE -FDGE_AMT END) signed FROM FD_GL_ENTRIES GROUP BY FDGE_GL_CD) e
    ON e.FDGE_GL_CD=g.FDGL_CD WHERE g.FDGL_CURRENT_BAL<>COALESCE(e.signed,0)*CASE WHEN g.FDGL_TYP IN ('ASSET','EXPENSE') THEN 1 ELSE -1 END;`).trim();
  if (gaps !== '0') throw new Error('GL balances do not reconcile before migration. Resolve the discrepancy before applying.');
  console.log(`Preflight passed: ${accounts.length} accounts, ${txns.length} transactions, ${entries.length} GL entries; ${changes.length} entries require currency attribution.`);
  return { changes, definitions };
}
let state = preflight();
if (!apply) { console.log('Read-only check complete. Run node scripts/migrate-money.mjs --apply to back up and migrate.'); process.exit(0); }
compose(['stop', 'fd', 'reports']);
state = preflight();
mkdirSync(resolve(root, 'output/backups'), { recursive: true });
const backup = resolve(root, 'output/backups/fd-before-money-' + new Date().toISOString().replace(/[:.]/g, '-') + '.sql');
const fd = openSync(backup, 'wx', 0o600);
try { compose(['exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$FD_DB_PASSWORD" mysqldump -ufd_app --single-transaction --no-tablespaces --skip-lock-tables fd_db'], { encoding: null, stdio: ['ignore', fd, 'pipe'] }); }
finally { closeSync(fd); }
console.log('Backup saved: ' + backup);
const foreignKeys = rows("SELECT JSON_OBJECT('name',CONSTRAINT_NAME) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fd_gl_entries' AND COLUMN_NAME='FDGE_GL_CD' AND REFERENCED_TABLE_NAME IS NOT NULL;");
const commands = [];
for (const fk of foreignKeys) {
  if (!/^[A-Za-z0-9_]+$/.test(fk.name)) throw new Error('Unexpected foreign key identifier');
  commands.push(`ALTER TABLE FD_GL_ENTRIES DROP FOREIGN KEY \`${fk.name}\`;`);
}
commands.push('ALTER TABLE FD_GL_ACCOUNTS MODIFY FDGL_CD VARCHAR(64) NOT NULL, MODIFY FDGL_CURRENT_BAL DECIMAL(20,4);');
commands.push('ALTER TABLE FD_GL_ENTRIES MODIFY FDGE_GL_CD VARCHAR(64) NOT NULL, MODIFY FDGE_AMT DECIMAL(20,4) NOT NULL;');
commands.push('ALTER TABLE FD_GL_ENTRIES ADD CONSTRAINT FK_FDGE_FDGL FOREIGN KEY (FDGE_GL_CD) REFERENCES FD_GL_ACCOUNTS(FDGL_CD);');
commands.push(`ALTER TABLE FD_ACCOUNTS MODIFY FDA_PRINCIPAL_AMT DECIMAL(20,4) NOT NULL, MODIFY FDA_PRINCIPAL_BAL DECIMAL(20,4), MODIFY FDA_ACCRUED_INT_AMT DECIMAL(20,4), MODIFY FDA_MAT_AMT DECIMAL(20,4);`);
commands.push('ALTER TABLE FD_TRANSACTIONS MODIFY FDT_AMT DECIMAL(20,4) NOT NULL, MODIFY FDT_BAL_BEFORE DECIMAL(20,4), MODIFY FDT_BAL_AFTER DECIMAL(20,4), MODIFY FDT_TDS_RT DECIMAL(9,4);');
const taxColumn = sql("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='fd_accounts' AND COLUMN_NAME='FDA_TAX_ENABLED';").trim();
if (taxColumn === '0') commands.push('ALTER TABLE FD_ACCOUNTS ADD FDA_TAX_ENABLED BIT(1) NULL;');
sql(commands.join('\n'));
const updates = ['START TRANSACTION;'];
for (const d of state.definitions.values()) updates.push(`INSERT IGNORE INTO FD_GL_ACCOUNTS (FDGL_CD,FDGL_NAME,FDGL_TYP,FDGL_CCY_CD,FDGL_CURRENT_BAL,FDGL_IS_ACTIVE,USER_ID,CRUD_VALUE) VALUES (${literal(d.code)},${literal(d.name)},${literal(d.type)},${literal(d.currency)},0,1,'FD_SYSTEM','C');`);
for (const c of state.changes) updates.push(`UPDATE FD_GL_ENTRIES SET FDGE_GL_CD=${literal(c.code)} WHERE FDGE_ID=${literal(c.id)};`);
updates.push(`UPDATE FD_GL_ACCOUNTS g LEFT JOIN
  (SELECT FDGE_GL_CD,SUM(CASE WHEN FDGE_DR_CR='D' THEN FDGE_AMT ELSE -FDGE_AMT END) signed FROM FD_GL_ENTRIES GROUP BY FDGE_GL_CD) e
  ON g.FDGL_CD=e.FDGE_GL_CD SET g.FDGL_CURRENT_BAL=COALESCE(e.signed,0)*CASE WHEN g.FDGL_TYP IN ('ASSET','EXPENSE') THEN 1 ELSE -1 END;`);
updates.push('COMMIT;');sql(updates.join('\n'));
preflight();
console.log('Migration completed. Existing contracts retain NULL/false tax eligibility. Starting updated services.');
compose(['up', '-d', '--wait', 'fd', 'reports']);
