// Operations console: business clock, batch runs, account ledger, GL balances.
// Everything is rendered with textContent — no server value is ever parsed as HTML.

const byId = (id) => document.getElementById(id);
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

let currentAccountId = null;
let currentBusinessDate = null;
let currentCurrency = 'INR';

function formatMoney(value, currency = currentCurrency) {
  return Money.exact(value, currency);
}

function formatTimestamp(value) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—';
}

function showError(error) {
  const box = byId('global-error');
  if (!error) {
    box.hidden = true;
    box.textContent = '';
    return;
  }
  box.hidden = false;
  box.textContent = error.message;
}

async function guarded(action) {
  showError(null);
  try {
    await action();
  } catch (error) {
    showError(error);
  }
}

/** Runs an action with the button disabled so a slow batch can't be double-triggered. */
function withBusyButton(button, action) {
  return guarded(async () => {
    button.disabled = true;
    try {
      await action();
    } finally {
      button.disabled = false;
    }
  });
}

function renderKeyValues(list, pairs) {
  list.replaceChildren();
  for (const [label, value] of pairs) {
    const wrapper = document.createElement('div');
    const term = document.createElement('dt');
    term.textContent = label;
    const detail = document.createElement('dd');
    if (value instanceof Node) {
      detail.append(value);
    } else {
      detail.textContent = value === null || value === undefined || value === '' ? '—' : value;
    }
    wrapper.append(term, detail);
    list.append(wrapper);
  }
}

/** columns: [{ value: row => any, numeric?: boolean }] */
function renderTable(body, rows, columns, emptyText) {
  body.replaceChildren();
  if (rows.length === 0) {
    const tr = document.createElement('tr');
    const td = document.createElement('td');
    td.colSpan = columns.length;
    td.className = 'empty';
    td.textContent = emptyText;
    tr.append(td);
    body.append(tr);
    return;
  }
  for (const row of rows) {
    const tr = document.createElement('tr');
    for (const column of columns) {
      const td = document.createElement('td');
      if (column.numeric) td.className = 'num';
      const value = column.value(row);
      if (value instanceof Node) {
        td.append(value);
      } else {
        td.textContent = value === null || value === undefined ? '—' : value;
      }
      tr.append(td);
    }
    body.append(tr);
  }
}

function badge(status) {
  const span = document.createElement('span');
  span.className = `badge badge-${String(status || '').toLowerCase()}`;
  span.textContent = status || '—';
  return span;
}

// --- Business clock --------------------------------------------------------

async function loadClock() {
  const clock = await apiRequest('GET', '/business-clock');
  currentBusinessDate = clock.businessDate;
  renderKeyValues(byId('clock-view'), [
    ['Business date', clock.businessDate],
    ['Previous date', clock.previousBusinessDate],
    ['EOD status', badge(clock.eodStatus)],
    ['Last EOD', formatTimestamp(clock.lastEodTs)],
  ]);
  byId('clock-date').value = clock.businessDate;
  if (!byId('batch-date').dataset.touched) {
    byId('batch-date').value = clock.businessDate;
  }
}

byId('clock-set-btn').addEventListener('click', (event) =>
  withBusyButton(event.currentTarget, async () => {
    await apiRequest('PUT', '/business-clock', { businessDate: byId('clock-date').value });
    await refreshAll();
  })
);

byId('clock-advance-btn').addEventListener('click', (event) =>
  withBusyButton(event.currentTarget, async () => {
    await apiRequest('POST', '/business-clock/advance', { days: Number(byId('clock-days').value) });
    await refreshAll();
  })
);

byId('eod-btn').addEventListener('click', (event) =>
  withBusyButton(event.currentTarget, async () => {
    await apiRequest('POST', '/business-clock/eod');
    await refreshAll();
  })
);

// --- Batch runs ------------------------------------------------------------

byId('batch-date').addEventListener('input', (event) => {
  event.target.dataset.touched = 'true';
});

async function loadRuns() {
  const [accrual, maturity] = await Promise.all([
    apiRequest('GET', '/batch-jobs/ACCRUAL/runs'),
    apiRequest('GET', '/batch-jobs/MATURITY/runs'),
  ]);
  const runs = [...accrual, ...maturity].sort((a, b) => b.fdjbId - a.fdjbId).slice(0, 25);
  renderTable(byId('runs-body'), runs, [
    { value: (r) => r.fdjbId },
    { value: (r) => r.jobName },
    { value: (r) => r.businessDt },
    { value: (r) => badge(r.status) },
    { value: (r) => r.readCnt, numeric: true },
    { value: (r) => r.writeCnt, numeric: true },
    { value: (r) => r.skipCnt, numeric: true },
    { value: (r) => formatTimestamp(r.endTs) },
    { value: (r) => r.errorMsg || '' },
  ], 'No batch runs yet');
}

document.querySelectorAll('.run-job').forEach((button) => {
  button.addEventListener('click', () =>
    withBusyButton(button, async () => {
      const businessDate = byId('batch-date').value;
      if (!businessDate) throw new Error('Pick a business date for the batch run.');
      await apiRequest('POST', `/batch-jobs/${button.dataset.job}/runs`, { businessDate });
      await refreshAll();
    })
  );
});

// --- Account ---------------------------------------------------------------

async function resolveAccountId(key) {
  if (UUID_PATTERN.test(key)) return key;
  const page = await apiRequest('GET', `/fd-accounts?acctNum=${encodeURIComponent(key)}`);
  if (page.content.length === 0) throw new Error(`No FD account with number ${key}.`);
  return page.content[0].fdaId;
}

async function loadAccount(fdaId) {
  const [account, transactions] = await Promise.all([
    apiRequest('GET', `/fd-accounts/${fdaId}`),
    apiRequest('GET', `/fd-accounts/${fdaId}/transactions?size=200`),
  ]);
  currentAccountId = account.fdaId;
  currentCurrency = account.currencyCode;
  byId('account-section').hidden = false;

  renderKeyValues(byId('account-view'), [
    ['Account number', account.acctNum],
    ['Status', badge(account.status)],
    ['Customer', `${account.custNameSnap || ''} (${account.custId})`],
    ['Product', account.prdNameSnap || account.productCode],
    ['Principal', formatMoney(account.principalAmt)],
    ['Current balance', formatMoney(account.principalBal)],
    ['Accrued interest', formatMoney(account.accruedIntAmt)],
    ['Rate', account.intRt === null ? '—' : `${Number(account.intRt).toFixed(2)}%`],
    ['Interest type', account.interestType],
    ['Compounding / payout', `${account.compoundFreq || '—'} / ${account.payoutFreq || '—'}`],
    ['Value date', account.valueDt],
    ['Maturity date', account.matDt],
    ['Demo tax', account.taxEnabled ? 'Applies at settlement; estimates depend on other account activity' : 'Disabled for this contract'],
    ['Projected net maturity', formatMoney(account.matAmt)],
    ['Last accrual', account.lastAccrualDt],
    ['Last capitalization', account.lastCaptlzDt],
    ['Closed on', account.closureDt],
  ]);

  renderTable(byId('txn-body'), transactions.content, [
    { value: (t) => t.fdtId },
    { value: (t) => t.txnType },
    { value: (t) => t.drCr },
    { value: (t) => formatMoney(t.amt), numeric: true },
    { value: (t) => formatMoney(t.balBefore), numeric: true },
    { value: (t) => formatMoney(t.balAfter), numeric: true },
    { value: (t) => t.txnDt },
    { value: (t) => t.valueDt },
    { value: (t) => t.remarks },
  ], 'No transactions');

  // The preview runs on the current balance, which already includes capitalized interest —
  // so the sensible default period starts at the last capitalization, not the value date.
  if (!byId('preview-start').value) byId('preview-start').value = account.lastCaptlzDt || account.valueDt;
  if (!byId('preview-end').value) byId('preview-end').value = account.matDt;

  const isAtOrPastMaturity = currentBusinessDate && currentBusinessDate >= account.matDt;
  byId('withdraw-block').hidden = account.status !== 'ACTIVE' || isAtOrPastMaturity;
  byId('withdraw-confirm').hidden = true;
  byId('withdraw-btn').hidden = false;
  byId('renew-block').hidden = account.status !== 'ACTIVE' || !isAtOrPastMaturity;
  byId('renew-confirm').hidden = true;
  byId('renew-btn').hidden = false;
}

byId('lookup-form').addEventListener('submit', (event) => {
  event.preventDefault();
  guarded(async () => {
    const key = byId('lookup-key').value.trim();
    if (!key) throw new Error('Enter an account number or FDA_ID.');
    byId('preview-view').hidden = true;
    byId('withdraw-result').hidden = true;
    byId('renew-result').hidden = true;
    byId('preview-start').value = '';
    byId('preview-end').value = '';
    await loadAccount(await resolveAccountId(key));
  });
});

byId('preview-form').addEventListener('submit', (event) => {
  event.preventDefault();
  guarded(async () => {
    const preview = await apiRequest('POST', `/fd-accounts/${currentAccountId}/interest-calculations`, {
      periodStart: byId('preview-start').value,
      periodEnd: byId('preview-end').value,
    });
    const list = byId('preview-view');
    list.hidden = false;
    renderKeyValues(list, [
      ['Principal', formatMoney(preview.principal)],
      ['Rate', `${Number(preview.rate).toFixed(2)}%`],
      ['Days', preview.days],
      ['Day count', preview.dayCountConvention],
      ['Interest', formatMoney(preview.interestAmount, preview.currencyCode)],
      ['Paid out during period', formatMoney(preview.interestPaidOut)],
      ['Estimated demo tax', formatMoney(preview.estimatedTax)],
      ['Projected net value at period end', formatMoney(preview.maturityValue)],
    ]);
  });
});

byId('withdraw-btn').addEventListener('click', () => {
  byId('withdraw-confirm-text').textContent = 'This closes the account today and cannot be undone.';
  byId('withdraw-confirm').hidden = false;
  byId('withdraw-btn').hidden = true;
});

byId('withdraw-cancel-btn').addEventListener('click', () => {
  byId('withdraw-confirm').hidden = true;
  byId('withdraw-btn').hidden = false;
});

byId('withdraw-confirm-btn').addEventListener('click', (event) =>
  withBusyButton(event.currentTarget, async () => {
    const result = await apiRequest('POST', `/fd-accounts/${currentAccountId}/withdraw`, {});
    const list = byId('withdraw-result');
    list.hidden = false;
    renderKeyValues(list, [
      ['Withdrawal date', result.withdrawalDate],
      ['Interest earned', formatMoney(result.interestEarned)],
      ['Final interest credited', formatMoney(result.finalInterest)],
      ['Demo tax deducted', formatMoney(result.taxDeducted)],
      ['Penalty applied', formatMoney(result.penaltyApplied)],
      ['Paid out', formatMoney(result.withdrawalAmount)],
    ]);
    await refreshAll();
  })
);

byId('renew-btn').addEventListener('click', () => {
  const tenure = byId('renew-tenure').value;
  byId('renew-confirm-text').textContent = tenure
    ? `Renew this deposit for ${tenure} month(s)?`
    : 'Renew this deposit using its current tenure?';
  byId('renew-confirm').hidden = false;
  byId('renew-btn').hidden = true;
});

byId('renew-cancel-btn').addEventListener('click', () => {
  byId('renew-confirm').hidden = true;
  byId('renew-btn').hidden = false;
});

byId('renew-confirm-btn').addEventListener('click', (event) =>
  withBusyButton(event.currentTarget, async () => {
    const tenure = byId('renew-tenure').value;
    const result = await apiRequest('POST', `/fd-accounts/${currentAccountId}/renew`,
      tenure ? { tenureMonths: Number(tenure) } : {});
    const list = byId('renew-result');
    list.hidden = false;
    renderKeyValues(list, [
      ['New account number', result.acctNum],
      ['Renewed balance', formatMoney(result.principalBal)],
      ['New maturity date', result.matDt],
    ]);
    currentAccountId = result.fdaId;
    await refreshAll();
  })
);

// --- GL accounts -----------------------------------------------------------

async function loadGlAccounts() {
  const accounts = await apiRequest('GET', '/gl-accounts');
  renderTable(byId('gl-body'), accounts, [
    { value: (g) => g.glCd },
    { value: (g) => g.name },
    { value: (g) => g.type },
    { value: (g) => g.currencyCode },
    { value: (g) => formatMoney(g.currentBalance, g.currencyCode), numeric: true },
  ], 'No GL accounts');
}

byId('gl-refresh-btn').addEventListener('click', (event) => withBusyButton(event.currentTarget, refreshAll));

async function refreshAll() {
  await Promise.all([loadClock(), loadRuns(), loadGlAccounts()]);
  if (currentAccountId) await loadAccount(currentAccountId);
}

guarded(refreshAll);
