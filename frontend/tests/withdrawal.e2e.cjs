// Optional browser check: requires Playwright and an isolated, running FD backend.
// Never run against a shared/demo database: this script sets its business clock.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.FD_PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  assert.equal(process.env.FD_E2E_ISOLATED_DB, '1', 'Set FD_E2E_ISOLATED_DB=1 only for an isolated test backend.');
  const browser = await chromium.launch({ headless: true, channel: process.env.FD_BROWSER_CHANNEL || undefined });
  const page = await browser.newPage({ viewport: { width: 1100, height: 1000 } });
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  let submissions = 0;
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/withdraw')) submissions++;
  });
  const api = async (method, endpoint, data) => {
    const response = await page.request.fetch(`http://localhost:8080${endpoint}`, { method, data });
    assert(response.ok(), `${method} ${endpoint}: ${response.status()} ${await response.text()}`);
    return response.json();
  };
  try {
    await api('PUT', '/business-clock', { businessDate: '2026-01-01' });
    const account = await api('POST', '/fd-accounts', {
      custId: 'CUST-001', productCode: 'FD-REG', principal: 100000, tenureMonths: 12,
      rateId: 'RATE-12M', currencyCode: 'INR', interestType: 'COMPOUND',
      compoundingFreq: 'QUARTERLY', payoutFreq: 'ON_MATURITY',
      initialRoles: [{ custId: 'CUST-001', roleType: 'OWNER', isPrimary: true }],
    });
    await api('PUT', '/business-clock', { businessDate: '2026-07-16' });
    await page.goto('http://localhost:3000/withdraw.html');
    await page.getByLabel('Account number', { exact: true }).fill('does-not-exist');
    await page.getByRole('button', { name: 'Find account', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'could not find' }).waitFor();
    await page.getByLabel('Account number', { exact: true }).fill(account.acctNum);
    await page.getByRole('button', { name: 'Find account', exact: true }).click();
    await page.locator('#withdraw-account').waitFor();
    await page.getByLabel('Reason for closing (optional)', { exact: true }).fill('Customer request');
    await page.getByRole('button', { name: 'Review withdrawal', exact: true }).click();
    await page.locator('#quote-section').waitFor();
    const text = await page.locator('#quote-summary').innerText();
    for (const expected of ['2026-07-16', '3,525.06 INR', '275.80 INR', '35.25 INR', '1,03,489.81 INR']) {
      assert(text.includes(expected), `Missing quote value ${expected}`);
    }
    assert(await page.getByRole('button', { name: 'Confirm and close FD', exact: true }).isDisabled());
    assert.equal((await api('GET', `/fd-accounts/${account.fdaId}/transactions`)).totalElements, 1);
    const screenshots = path.resolve(__dirname, '../../backend/fdservice/docs/screenshots');
    fs.mkdirSync(screenshots, { recursive: true });
    await page.screenshot({ path: path.join(screenshots, 'withdrawal-quote.png'), fullPage: true });
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    assert(await page.locator('#quote-section').isHidden());
    assert.equal((await api('GET', `/fd-accounts/${account.fdaId}/transactions`)).totalElements, 1);

    // A quote's date changed elsewhere: confirmation must reject without closing.
    await page.getByRole('button', { name: 'Review withdrawal', exact: true }).click();
    await page.locator('#quote-section').waitFor();
    await api('PUT', '/business-clock', { businessDate: '2026-07-17' });
    await page.getByRole('checkbox').check();
    await page.getByRole('button', { name: 'Confirm and close FD', exact: true }).click();
    await page.getByRole('alert').filter({ hasText: 'date or amounts have changed' }).waitFor();
    assert.equal((await api('GET', `/fd-accounts/${account.fdaId}`)).status, 'ACTIVE');
    assert.equal((await api('GET', `/fd-accounts/${account.fdaId}/transactions`)).totalElements, 1);

    await api('PUT', '/business-clock', { businessDate: '2026-07-16' });
    await page.getByRole('button', { name: 'Review withdrawal', exact: true }).click();
    await page.locator('#quote-section').waitFor();
    await page.getByRole('checkbox').check();
    // Two synchronous submit events exercise the busy guard before either fetch resolves.
    await page.locator('#confirm-form').evaluate((form) => {
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    });
    await page.locator('#receipt-section').waitFor();
    assert.equal(submissions, 2, 'One rejected stale quote plus one successful closure; no duplicate submission.');
    const receipt = await page.locator('#receipt-summary').innerText();
    for (const value of [account.acctNum, 'CLOSED', '35.25 INR', '1,03,489.81 INR', 'Customer request']) {
      assert(receipt.includes(value), `Missing receipt value ${value}`);
    }
    await page.screenshot({ path: path.join(screenshots, 'withdrawal-receipt.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth));
    await page.screenshot({ path: path.join(screenshots, 'withdrawal-receipt-mobile.png'), fullPage: true });
    const transactions = (await api('GET', `/fd-accounts/${account.fdaId}/transactions`)).content;
    assert.equal(transactions.length, 6);
    assert.equal(transactions.at(-1).remarks, 'Customer request');
    assert.equal(transactions.at(-1).balAfter, 0);
    await page.getByRole('button', { name: 'Find account', exact: true }).click();
    await page.locator('#withdraw-account').waitFor();
    assert(await page.getByRole('button', { name: 'Review withdrawal', exact: true }).isDisabled());
    assert.deepEqual(errors, []);
    console.log('PASS: lookup, quote, cancellation, stale-quote rejection, single submission, receipt, closed-account guard and mobile layout.');
    console.log(`Screenshots: ${screenshots}`);
  } finally {
    await browser.close();
  }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
