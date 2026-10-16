const test = require('node:test');
const assert = require('node:assert/strict');
const Money = require('../js/money.js');
test('exact grouping and minor units without floating point loss', () => {
  assert.equal(Money.exact('1234567.89', 'INR'), 'INR 12,34,567.89');
  assert.equal(Money.exact('1234567.89', 'USD'), 'USD 1,234,567.89');
  assert.equal(Money.exact('1234567', 'JPY'), 'JPY 1,234,567');
  assert.equal(Money.exact('1234.567', 'KWD'), 'KWD 1,234.567');
  assert.equal(Money.exact('9007199254740991.123', 'BHD'), 'BHD 9,007,199,254,740,991.123');
  assert.equal(Money.exact('-1.005', 'INR'), 'INR -1.01');
  assert.equal(Money.exact('999.9995', 'KWD'), 'KWD 1,000.000');
  assert.equal(Money.exact('0.5', 'JPY'), 'JPY 1');
});
test('input validation is exact and matches supported precision', () => {
  assert.equal(Money.validate('123.456', 'KWD'), '');
  assert.equal(Money.validate('123.4560', 'BHD'), '');
  assert.match(Money.validate('123.456', 'INR'), /2 decimal/);
  assert.match(Money.validate('123.4', 'JPY'), /0 decimal/);
  assert.match(Money.validate('12345678901234567', 'USD'), /16 integer/);
  assert.match(Money.validate('0.000', 'USD'), /positive/);
  assert.throws(() => Money.digits('ABC'), /ISO/);
});
test('compact labels supplement exact values', () => {
  assert.equal(Money.compact('1234567.89', 'INR'), '12.35 lakh');
  assert.equal(Money.compact('123456789.12', 'INR'), '12.35 crore');
  assert.equal(Money.compact('1234567.89', 'USD'), '1.23 million');
  assert.equal(Money.compact('-1234567890', 'JPY'), '-1.23 billion');
  assert.equal(Money.compact('1234.567', 'KWD'), '');
});
