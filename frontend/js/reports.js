const $ = id => document.getElementById(id);
const reportNames = Object.fromEntries([...$('report-type').options].map(o => [o.value, o.text]));
const help = {
  TOTAL_LIABILITY: 'Current active balances plus accrued interest, rounded per account. Date filters do not apply.',
  PORTFOLIO_SUMMARY: 'Current account details; dates filter when the account opened. Leave blank for all opening dates.',
  MONTHLY_GROWTH: 'Posted deposit balances and flows. Default: the last twelve calendar months through the business date. Paid-out interest does not grow the deposit; renewals are shown separately.',
  TDS_COMPLIANCE: 'Recorded deductions only; this report does not calculate tax. Default: April 1 through the business date. Empty results mean no matching recorded deductions.',
  MATURITY_SCHEDULE: 'Active accounts maturing in the selected period. Default: business date through twelve months ahead. Maturity amounts are booked projections.',
  ACCOUNT_STATEMENT: 'Posted transactions with opening and closing balances. Default: account opening through the business date.'
};
const columns = {
  TOTAL_LIABILITY: ['accountNumber', 'customerName', 'currency', 'depositBalance', 'accruedInterest', 'totalLiability', 'status'],
  PORTFOLIO_SUMMARY: ['accountNumber', 'customerName', 'productName', 'currency', 'principal', 'depositBalance', 'accruedInterest', 'status', 'openDate', 'maturityDate'],
  MATURITY_SCHEDULE: ['accountNumber', 'customerName', 'currency', 'maturityDate', 'projectedMaturityAmount', 'depositBalance', 'status'],
  MONTHLY_GROWTH: ['month', 'currency', 'openingBalance', 'closingBalance', 'netGrowth', 'growthPercent', 'newDeposits', 'closurePayouts', 'renewalIn', 'renewalOut', 'accountsOpened', 'accountsClosed'],
  TDS_COMPLIANCE: ['date', 'accountNumber', 'customerName', 'currency', 'netDeduction', 'financialYear', 'pan', 'taxRate', 'transactionId'],
  ACCOUNT_STATEMENT: ['date', 'type', 'direction', 'amount', 'balanceChange', 'runningBalance', 'currency', 'remarks', 'transactionId']
};
let submittedQuery = null;
let busy = false;
function showError(id, error) { $(id).textContent = error.message; $(id).hidden = false; }
function title(field) { return field.replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase()); }
function table(target, rows, selectedFields) {
  target.replaceChildren();
  if (!rows.length) { const p = document.createElement('p'); p.className = 'hint'; p.textContent = 'No matching records.'; target.append(p); return; }
  const fields = selectedFields || [...new Set(rows.flatMap(row => Object.keys(row)))];
  const element = document.createElement('table');
  const head = element.createTHead().insertRow();
  for (const field of fields) { const th = document.createElement('th'); th.scope = 'col'; th.textContent = title(field); head.append(th); }
  const body = element.createTBody();
  for (const row of rows) {
    const tr = body.insertRow();
    for (const field of fields) {
      const td = tr.insertCell(); td.textContent = row[field] === null || row[field] === undefined ? '—' : String(row[field]);
      if (typeof row[field] === 'string' && /^-?\d+(\.\d+)?$/.test(row[field])) td.className = 'num';
    }
  }
  target.append(element);
}
function query() {
  const params = new URLSearchParams({ reportType: $('report-type').value });
  for (const [id, name] of [['report-customer', 'custId'], ['report-from', 'fromDate'], ['report-to', 'toDate']]) {
    if (!$(id).disabled && $(id).value.trim()) params.set(name, $(id).value.trim());
  }
  if ($('report-type').value === 'ACCOUNT_STATEMENT') params.set('accountId', $('report-account').value);
  return params;
}
async function loadAccounts() {
  $('load-accounts').disabled = true;
  $('report-error').hidden = true;
  const selected = $('report-account').value;
  $('report-account').replaceChildren(new Option('Choose an account', ''));
  try {
    const params = new URLSearchParams({ reportType: 'PORTFOLIO_SUMMARY' });
    if ($('report-customer').value.trim()) params.set('custId', $('report-customer').value.trim());
    const result = await apiRequest('GET', '/reports/fd-accounts?' + params);
    for (const account of result.rows) $('report-account').add(new Option(`${account.accountNumber} · ${account.currency} · ${account.status}`, account.accountId));
    if ([...$('report-account').options].some(o => o.value === selected)) $('report-account').value = selected;
  } catch (error) { showError('report-error', error); }
  finally { $('load-accounts').disabled = false; }
}
$('report-type').addEventListener('change', () => {
  const type = $('report-type').value;
  for (const id of ['report-from', 'report-to']) { $(id).disabled = type === 'TOTAL_LIABILITY'; $(id).value = ''; }
  $('account-filter').hidden = type !== 'ACCOUNT_STATEMENT'; $('report-account').required = type === 'ACCOUNT_STATEMENT';
  $('report-help').textContent = help[type];
  if (type === 'ACCOUNT_STATEMENT') loadAccounts();
});
$('report-customer').addEventListener('change', () => { if ($('report-type').value === 'ACCOUNT_STATEMENT') loadAccounts(); });
$('load-accounts').addEventListener('click', loadAccounts);
$('report-form').addEventListener('submit', async event => {
  event.preventDefault(); if (busy || !event.currentTarget.reportValidity()) return;
  busy = true; $('generate-report').disabled = true; $('download-csv').disabled = true;
  $('report-error').hidden = true; $('report-status').textContent = 'Generating report…';
  $('report-results').hidden = true;
  try {
    const params = query(); const report = await apiRequest('GET', '/reports/fd-accounts?' + params);
    submittedQuery = params.toString();
    $('results-heading').textContent = reportNames[report.reportType];
    $('row-count').textContent = `${report.rows.length} rows`;
    const period = report.reportType === 'TOTAL_LIABILITY' ? 'Current position' : `${report.fromDate || 'All opening dates'} → ${report.toDate}`;
    $('report-period').textContent = `${period} · Business date ${report.businessDate} · Generated ${new Date(report.generatedAt).toLocaleString()}`;
    $('report-warnings').textContent = report.warnings.join(' '); $('report-warnings').hidden = !report.warnings.length;
    $('totals-label').hidden = !report.totals.length;
    table($('report-totals'), report.totals, report.reportType === 'ACCOUNT_STATEMENT' ? ['accountNumber', 'customerName', 'currency', 'openingBalance', 'closingBalance'] : undefined); $('report-totals').hidden = !report.totals.length;
    table($('report-table'), report.rows, columns[report.reportType]); $('report-results').hidden = false;
    $('report-status').textContent = report.rows.length ? 'Report ready.' : 'Report ready. No matching records.';
    $('download-csv').disabled = false;
  } catch (error) { submittedQuery = null; showError('report-error', error); $('report-status').textContent = ''; }
  finally { busy = false; $('generate-report').disabled = false; }
});
$('download-csv').addEventListener('click', async () => {
  if (!submittedQuery || busy) return;
  busy = true; $('download-csv').disabled = true; $('generate-report').disabled = true; $('report-error').hidden = true;
  try {
    const params = new URLSearchParams(submittedQuery); params.set('format', 'csv');
    const response = await fetch(`${API_BASE_URL}/reports/fd-accounts?${params}`);
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || `Download failed (${response.status})`); }
    const url = URL.createObjectURL(await response.blob()); const link = document.createElement('a');
    link.href = url; link.download = params.get('reportType').toLowerCase() + '.csv'; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (error) { showError('report-error', error); }
  finally { busy = false; $('download-csv').disabled = false; $('generate-report').disabled = false; }
});
$('fx-form').addEventListener('submit', async event => {
  event.preventDefault(); if ($('convert-fx').disabled || !event.currentTarget.reportValidity()) return;
  $('convert-fx').disabled = true; $('fx-error').hidden = true; $('fx-result').textContent = 'Converting…'; $('fx-rates').replaceChildren();
  try {
    const params = new URLSearchParams({ from: $('fx-from').value, to: $('fx-to').value, amount: $('fx-amount').value });
    if ($('fx-date').value) params.set('date', $('fx-date').value);
    const rateParams = new URLSearchParams({ base: $('fx-from').value }); if ($('fx-date').value) rateParams.set('date', $('fx-date').value);
    const [quote, rates] = await Promise.all([apiRequest('GET', '/exchange-rates/convert?' + params), apiRequest('GET', '/exchange-rates?' + rateParams)]);
    $('fx-result').textContent = `${quote.amount} ${quote.from} = ${quote.convertedAmount} ${quote.to} · DEMO · Rate effective ${quote.effectiveDate} (requested ${quote.requestedDate})`;
    table($('fx-rates'), Object.entries(rates.rates).map(([currency, rate]) => ({ base: rates.base, currency, rate })));
  } catch (error) { $('fx-result').textContent = ''; showError('fx-error', error); }
  finally { $('convert-fx').disabled = false; }
});
apiRequest('GET', '/business-clock').then(clock => { $('business-date').textContent = '· ' + clock.businessDate; }).catch(error => showError('report-error', error));
