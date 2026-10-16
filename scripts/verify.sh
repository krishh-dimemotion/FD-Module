#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./scripts/compose.sh up -d --wait
./scripts/compose.sh run --rm verify
./scripts/compose.sh run --rm --no-deps gateway node --test
node --test frontend/tests/money.test.cjs
node scripts/check-gateway.mjs
# This must fail because the report database user has SELECT privilege only.
permissions=$(./scripts/compose.sh exec -T mysql sh -c 'MYSQL_PWD="$REPORT_DB_PASSWORD" mysql -ufd_report fd_db -e "UPDATE FD_BUSINESS_CLOCK SET FDBC_BUSINESS_DT=FDBC_BUSINESS_DT WHERE 1=0"' 2>&1) && {
  echo 'FAIL: report user can update tables'; exit 1;
}
printf '%s\n' "$permissions" | grep -q 'UPDATE command denied' >/dev/null || { echo 'FAIL: could not verify report privileges'; exit 1; }
echo 'PASS: reporting database user cannot write'
if [ "${FD_E2E_ISOLATED_DB:-}" = 1 ]; then
  node scripts/demo.mjs
  result=$(./scripts/compose.sh exec -T mysql sh -c 'MYSQL_PWD="$FD_DB_PASSWORD" mysql -N -ufd_app fd_db' < scripts/reconcile-demo.sql)
  printf '%s\n' "$result"
  if printf '%s\n' "$result" | grep FAIL; then exit 1; fi
fi
