const form = document.getElementById('fd-form');
const rolesList = document.getElementById('roles-list');
const roleRowTemplate = document.getElementById('role-row-template');
const addRoleBtn = document.getElementById('add-role-btn');
const formErrors = document.getElementById('form-errors');
const submitBtn = document.getElementById('submit-btn');
const resultPanel = document.getElementById('result-panel');
const resultTitle = document.getElementById('result-title');
const resultBody = document.getElementById('result-body');

const ROLE_TYPES = ['OWNER', 'JOINT_HOLDER', 'NOMINEE', 'GUARANTOR', 'GUARDIAN', 'BENEFICIARY'];

function addRoleRow(defaults = {}) {
  const fragment = roleRowTemplate.content.cloneNode(true);
  const row = fragment.querySelector('.role-row');

  if (defaults.custId) row.querySelector('.role-custId').value = defaults.custId;
  if (defaults.roleType) row.querySelector('.role-type').value = defaults.roleType;
  if (defaults.isPrimary) row.querySelector('.role-primary').checked = true;

  row.querySelector('.remove-role-btn').addEventListener('click', () => {
    // Always leave at least one role row behind.
    if (rolesList.querySelectorAll('.role-row').length > 1) {
      row.remove();
    }
  });

  rolesList.appendChild(row);
}

addRoleBtn.addEventListener('click', () => addRoleRow());

// Seed with a single OWNER role, kept in sync with the Customer ID field above
// until the user edits it directly.
addRoleRow({ roleType: 'OWNER', isPrimary: true });

document.getElementById('custId').addEventListener('input', (event) => {
  const firstRoleCustId = rolesList.querySelector('.role-row .role-custId');
  if (firstRoleCustId && !firstRoleCustId.dataset.touched) {
    firstRoleCustId.value = event.target.value;
  }
});
rolesList.addEventListener('input', (event) => {
  if (event.target.classList.contains('role-custId')) {
    event.target.dataset.touched = 'true';
  }
});

function readRoles() {
  return Array.from(rolesList.querySelectorAll('.role-row')).map((row) => ({
    custId: row.querySelector('.role-custId').value.trim(),
    roleType: row.querySelector('.role-type').value,
    isPrimary: row.querySelector('.role-primary').checked,
  }));
}

function validate(formValues, roles) {
  const errors = [];

  if (!formValues.custId) errors.push('Customer ID is required.');
  if (!formValues.productCode) errors.push('Product Code is required.');
  if (!formValues.rateId) errors.push('Rate ID is required.');

  try {
    const error = Money.validate(formValues.principal, formValues.currencyCode || 'USD');
    if (error) errors.push(error);
  } catch (error) { errors.push(error.message); }

  const tenureMonths = Number(formValues.tenureMonths);
  if (!formValues.tenureMonths || !Number.isInteger(tenureMonths) || tenureMonths <= 0) {
    errors.push('Tenure (months) must be a positive whole number.');
  }

  if (formValues.currencyCode && !/^[A-Za-z]{3}$/.test(formValues.currencyCode)) {
    errors.push('Currency Code must be a 3-letter ISO 4217 code (e.g. USD).');
  }

  if (roles.length === 0) {
    errors.push('At least one role is required.');
  }
  roles.forEach((role, index) => {
    if (!role.custId) errors.push(`Role #${index + 1}: Customer ID is required.`);
    if (!ROLE_TYPES.includes(role.roleType)) errors.push(`Role #${index + 1}: invalid role type.`);
  });

  const countByType = (type) => roles.filter((r) => r.roleType === type).length;
  if (countByType('GUARANTOR') > 1) errors.push('At most one GUARANTOR role is allowed.');
  if (countByType('GUARDIAN') > 1) errors.push('At most one GUARDIAN role is allowed.');

  return errors;
}

function showErrors(errors) {
  if (errors.length === 0) {
    formErrors.hidden = true;
    formErrors.innerHTML = '';
    return;
  }
  formErrors.hidden = false;
  formErrors.innerHTML = `<ul>${errors.map((e) => `<li>${escapeHtml(e)}</li>`).join('')}</ul>`;
}

function showResult(kind, title, body) {
  resultPanel.hidden = false;
  resultPanel.className = `result ${kind}`;
  resultTitle.textContent = title;
  resultBody.textContent = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
}

function escapeHtml(value) {
  const div = document.createElement('div');
  div.textContent = value;
  return div.innerHTML;
}

function buildPayload(formValues, roles) {
  const payload = {
    custId: formValues.custId,
    productCode: formValues.productCode,
    principal: formValues.principal,
    tenureMonths: Number(formValues.tenureMonths),
    rateId: formValues.rateId,
    initialRoles: roles.map((role) => ({
      custId: role.custId,
      roleType: role.roleType,
      isPrimary: role.isPrimary,
    })),
  };

  if (formValues.categoryCd) payload.categoryCd = formValues.categoryCd;
  if (formValues.currencyCode) payload.currencyCode = formValues.currencyCode.toUpperCase();
  if (formValues.interestType) payload.interestType = formValues.interestType;
  if (formValues.compoundingFreq) payload.compoundingFreq = formValues.compoundingFreq;
  if (formValues.payoutFreq) payload.payoutFreq = formValues.payoutFreq;
  if (formValues.maturityInstruction) payload.maturityInstruction = formValues.maturityInstruction;

  return payload;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();

  const formValues = Object.fromEntries(new FormData(form).entries());
  const roles = readRoles();

  const errors = validate(formValues, roles);
  showErrors(errors);
  if (errors.length > 0) {
    resultPanel.hidden = true;
    return;
  }

  const payload = buildPayload(formValues, roles);

  submitBtn.disabled = true;
  submitBtn.textContent = 'Opening...';
  try {
    const account = await createFdAccount(payload);
    showResult('success', 'Account opened', `${Money.exact(account.principalAmt, account.currencyCode)} deposited. Projected maturity: ${Money.exact(account.matAmt, account.currencyCode)}. ${account.taxEnabled ? 'Demo tax applies; projections may change with other account activity.' : 'Tax disabled for this contract.'}\n\n${JSON.stringify(account, null, 2)}`);
    form.reset();
    updateCurrencyInput();
    rolesList.innerHTML = '';
    addRoleRow({ roleType: 'OWNER', isPrimary: true });
  } catch (error) {
    showResult('failure', 'Account creation failed', error.body ?? error.message);
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Open Account';
  }
});

function updateCurrencyInput() {
  const input = document.getElementById('principal');
  try {
    const code = document.getElementById('currencyCode').value.trim().toUpperCase() || 'USD';
    const decimals = Money.digits(code);
    input.step = decimals ? '0.' + '0'.repeat(decimals - 1) + '1' : '1';
    input.min = input.step;
  } catch { input.step = 'any'; }
}
document.getElementById('currencyCode').addEventListener('input', updateCurrencyInput);
updateCurrencyInput();
