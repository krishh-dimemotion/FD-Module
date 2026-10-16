import { randomBytes } from 'node:crypto';
import { writeFile } from 'node:fs/promises';
const file = new URL('../.env', import.meta.url);
const keys = ['MYSQL_ROOT_PASSWORD','FD_DB_PASSWORD','REPORT_DB_PASSWORD','TEST_DB_PASSWORD'];
try {
  await writeFile(file, keys.map(key => `${key}=${randomBytes(20).toString('hex')}`).join('\n') + '\n', { flag:'wx', mode:0o600 });
  console.log('Created ignored .env with generated local database passwords.');
} catch (error) {
  if (error.code !== 'EEXIST') throw error;
  console.log('Keeping existing .env.');
}
