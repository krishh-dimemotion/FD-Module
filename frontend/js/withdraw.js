/* Customer withdrawal: lookup -> read-only quote -> explicit confirmation -> receipt. */
const element = (id) => document.getElementById(id);
const withdrawalErrors = {
  ACCOUNT_NOT_ACTIVE: 'This FD is already closed or is not active. Please load its current details.',
  WITHDRAWAL_BEFORE_VALUE_DATE: 'This FD cannot be withdrawn before its value date.',
  ACCOUNT_MATURED: 'This FD has matured; it will be paid out by maturity processing.',
  ACCRUAL_AHEAD_OF_BUSINESS_DATE: 'The business date is earlier than the interest already processed. Please contact a bank officer.',
  PARTIAL_WITHDRAWAL_NOT_SUPPORTED: 'Only full closure is supported. Please review a new withdrawal quote.',
  WITHDRAWAL_QUOTE_CHANGED: 'The date or amounts have changed. Review a new quote before confirming.',
  NOT_FOUND: 'We could not find that FD. Check the account number and try again.',
  VALIDATION_ERROR: 'Check your details. The reason must be no longer than 255 characters.',
  INVALID_INTEREST_TERMS: 'This FD has incomplete interest terms. Please contact a bank officer.',
  GL_ACCOUNT_NOT_CONFIGURED: 'The payment could not be completed. Please contact a bank officer.',
};
let selectedAccount = null;
let currentQuote = null;
let quotedReason = '';
let busy = false;

function showError(error, confirmation = false) {
  const known = withdrawalErrors[error.body?.code];
  element('withdraw-error').textContent = known || (confirmation
    ? 'We could not confirm the result. Look up this account again to check its status before trying another withdrawal.'
    : 'We could not load your FD. Please try again shortly.');
  element('withdraw-error').hidden = false;
}

function clearQuote() {
  currentQuote = null;
  element('quote-section').hidden = true;
  element('acknowledge').checked = false;
  element('confirm-button').disabled = true;
}

function setBusy(value) {
  busy = value;
  for (const id of ['lookup-button', 'account-number', 'withdraw-reason', 'cancel-button', 'acknowledge']) {
    element(id).disabled = value;
  }
  element('quote-button').disabled = value || !selectedAccount || selectedAccount.status !== 'ACTIVE';
  element('confirm-button').disabled = value || !currentQuote || !element('acknowledge').checked;
}

function renderValues(id, entries) {
  const list = element(id);
  list.replaceChildren();
  for (const [label, value] of entries) {
    const pair = document.createElement('div');
    const term = document.createElement('dt');
    const detail = document.createElement('dd');
    term.textContent = label;
    detail.textContent = value == null ? 'Unavailable' : String(value);
    pair.append(term, detail);
    list.append(pair);
  }
}

function money(value) {
  const decimals = selectedAccount.ccyDecimals ?? 2;
  return `${Number(value).toLocaleString('en-IN', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })} ${selectedAccount.currencyCode}`;
}

function quoteValues(quote) {
  return [
    ['Closure date', quote.withdrawalDate],
    ['Lifetime interest earned', money(quote.interestEarned)],
    ['Final interest credited', money(quote.finalInterest)],
    ['Penalty', money(quote.penaltyApplied)],
    ['Amount to be paid', money(quote.withdrawalAmount)],
  ];
}

element('withdraw-lookup').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (busy || !event.currentTarget.reportValidity()) return;
  clearQuote();
  selectedAccount = null;
  element('withdraw-account').hidden = true;
  element('receipt-section').hidden = true;
  element('withdraw-error').hidden = true;
  setBusy(true);
  try {
    const number = element('account-number').value.trim();
    if (!number) throw { body: { code: 'NOT_FOUND' } };
    const result = await apiRequest('GET', `/fd-accounts?acctNum=${encodeURIComponent(number)}`);
    if (!result.content.length) throw { body: { code: 'NOT_FOUND' } };
    const [account, clock] = await Promise.all([
      apiRequest('GET', `/fd-accounts/${encodeURIComponent(result.content[0].fdaId)}`),
      apiRequest('GET', '/business-clock'),
    ]);
    selectedAccount = account;
    renderValues('account-summary', [
      ['Account number', account.acctNum], ['Status', account.status],
      ['Original principal', money(account.principalAmt)], ['Current balance', money(account.principalBal)],
      ['Interest rate', account.intRt == null ? 'No contracted rate' : `${account.intRt}%`],
      ['Value date', account.valueDt], ['Maturity date', account.matDt],
      ['Current business date', clock.businessDate],
    ]);
    element('eligibility-message').textContent = account.status === 'ACTIVE'
      ? 'Review a quote to check eligibility and see the exact payment.' : withdrawalErrors.ACCOUNT_NOT_ACTIVE;
    element('withdraw-account').hidden = false;
  } catch (error) { showError(error); }
  finally { setBusy(false); }
});

// Editing either input invalidates the review; it can never confirm a different FD or reason.
element('account-number').addEventListener('input', () => {
  clearQuote();
  selectedAccount = null;
  element('withdraw-account').hidden = true;
  element('receipt-section').hidden = true;
});
element('withdraw-reason').addEventListener('input', clearQuote);

element('quote-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (busy || !selectedAccount || selectedAccount.status !== 'ACTIVE' || !event.currentTarget.reportValidity()) return;
  clearQuote();
  element('withdraw-error').hidden = true;
  setBusy(true);
  try {
    currentQuote = await apiRequest('POST', `/fd-accounts/${selectedAccount.fdaId}/withdrawal-quote`);
    quotedReason = element('withdraw-reason').value.trim();
    renderValues('quote-summary', quoteValues(currentQuote));
    element('quoted-reason').textContent = `Reason: ${quotedReason || 'Not provided'}`;
    element('quote-section').hidden = false;
    element('quote-heading').focus();
  } catch (error) { showError(error); }
  finally { setBusy(false); }
});

element('acknowledge').addEventListener('change', () => setBusy(busy));
element('cancel-button').addEventListener('click', clearQuote);
element('confirm-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (busy || !currentQuote || !element('acknowledge').checked || !event.currentTarget.reportValidity()) return;
  element('withdraw-error').hidden = true;
  setBusy(true);
  try {
    const result = await apiRequest('POST', `/fd-accounts/${selectedAccount.fdaId}/withdraw`, {
      remarks: quotedReason, expectedQuote: currentQuote,
    });
    selectedAccount = result.account;
    clearQuote();
    element('withdraw-account').hidden = true;
    renderValues('receipt-summary', [
      ['Account number', result.account.acctNum], ['Status', result.account.status],
      ...quoteValues(result), ['Reason', quotedReason || 'Not provided'],
      ['Transaction group', result.txnGrpId], ['Transaction references', result.transactionIds.join(', ') || 'No monetary postings'],
    ]);
    element('receipt-section').hidden = false;
    element('receipt-heading').focus();
  } catch (error) {
    clearQuote();
    showError(error, true);
    // A lost response can hide a committed withdrawal. Require a fresh lookup.
    if (!error.body?.code) {
      selectedAccount = null;
      element('withdraw-account').hidden = true;
    }
  } finally { setBusy(false); }
});
