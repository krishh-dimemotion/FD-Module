/* Exact decimal formatting. All monetary arithmetic uses strings and BigInt. */
(function (root) {
  'use strict';
  const monetaryFields = new Set(['principal','principalAmt','principalBal','matAmt','accruedIntAmt','depositBalance',
    'accruedInterest','totalLiability','projectedMaturityAmount','openingBalance','closingBalance','netGrowth',
    'newDeposits','closurePayouts','renewalIn','renewalOut','netDeduction','amount','balanceChange','runningBalance',
    'taxableAmount','estimatedTax','taxDeducted']);
  function digits(currency) {
    const code = String(currency).trim().toUpperCase();
    if (!/^[A-Z]{3}$/.test(code) || (Intl.supportedValuesOf && !Intl.supportedValuesOf('currency').includes(code))) throw new Error('Enter a supported ISO currency code.');
    return new Intl.NumberFormat('en', { style: 'currency', currency: code }).resolvedOptions().maximumFractionDigits;
  }
  function units(value, decimals) {
    const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value));
    if (!match) throw new Error('Amount must be a decimal number.');
    const fraction = match[3] || '';
    let n = BigInt(match[2] + fraction.slice(0, decimals).padEnd(decimals, '0'));
    if (fraction.length > decimals && fraction[decimals] >= '5') n += 1n;
    return match[1] ? -n : n;
  }
  function exact(value, currency, includeCode = true) {
    if (value === null || value === undefined) return '—';
    const d = digits(currency), n = units(value, d), negative = n < 0n;
    const raw = (negative ? -n : n).toString().padStart(d + 1, '0');
    let whole = d ? raw.slice(0, -d) : raw;
    if (currency === 'INR' && whole.length > 3) {
      whole = whole.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',') + ',' + whole.slice(-3);
    } else whole = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    const amount = (negative ? '-' : '') + whole + (d ? '.' + raw.slice(-d) : '');
    return (includeCode ? currency + ' ' : '') + amount;
  }
  function compact(value, currency) {
    const d = digits(currency), n = units(value, d), abs = n < 0n ? -n : n;
    const choices = currency === 'INR' ? [[10000000n, 'crore'], [100000n, 'lakh']] : [[1000000000n, 'billion'], [1000000n, 'million']];
    for (const [base, label] of choices) {
      const divisor = base * 10n ** BigInt(d);
      if (abs >= divisor) {
        const hundredths = (abs * 100n + divisor / 2n) / divisor;
        return `${n < 0n ? '-' : ''}${hundredths / 100n}.${(hundredths % 100n).toString().padStart(2, '0')} ${label}`;
      }
    }
    return '';
  }
  function validate(value, currency) {
    const d = digits(currency);
    if (!/^\d+(?:\.\d+)?$/.test(String(value)) || !/[1-9]/.test(String(value))) return 'Enter a positive decimal amount.';
    const [whole, fraction = ''] = String(value).split('.');
    if (whole.replace(/^0+/, '').length > 16) return 'Use at most 16 integer digits.';
    if (fraction.replace(/0+$/, '').length > d) return `${currency} accepts ${d} decimal place(s).`;
    return '';
  }
  root.Money = { digits, exact, compact, validate, monetaryFields };
  if (typeof module !== 'undefined') module.exports = root.Money;
})(globalThis);
