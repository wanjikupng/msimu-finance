const API_BASE = 'http://localhost:8080/api/v1';

const fallbackProducts = [
  {
    id: 1,
    name: 'Samsung Galaxy A15',
    icon: '📱',
    fullPrice: 18000,
    downPayment: 4000,
    dailyAmount: 70,
    description: '6.5" display • 128GB storage',
  },
  {
    id: 2,
    name: 'Tecno Camon 20',
    icon: '📲',
    fullPrice: 22000,
    downPayment: 5000,
    dailyAmount: 90,
    description: 'AI camera • 256GB memory',
  },
  {
    id: 3,
    name: 'Infinix Hot 40',
    icon: '📞',
    fullPrice: 16000,
    downPayment: 3500,
    dailyAmount: 60,
    description: 'Large battery • dual camera',
  },
];

const fallbackContracts = [
  {
    customerId: 'C-1001',
    customerName: 'Jane Muthoni',
    product: { name: 'Samsung Galaxy A15' },
    depositPaid: 4000,
    amountRemaining: 14000,
    dailyAmount: 70,
    deviceStatus: 'ACTIVE',
  },
  {
    customerId: 'C-1002',
    customerName: 'Peter Otieno',
    product: { name: 'Tecno Camon 20' },
    depositPaid: 5000,
    amountRemaining: 18000,
    dailyAmount: 90,
    deviceStatus: 'OVERDUE',
  },
  {
    customerId: 'C-1003',
    customerName: 'Aisha Juma',
    product: { name: 'Infinix Hot 40' },
    depositPaid: 3500,
    amountRemaining: 0,
    dailyAmount: 60,
    deviceStatus: 'PAID_OFF',
  },
];

const state = {
  selectedProductId: 1,
  products: [],
  contracts: [],
};

const userState = {
  customer: null,
  dashboard: null,
  token: null,
};

const TOKEN_STORAGE_KEY = 'tracom.token';
const USER_STORAGE_KEY = 'tracom.user';

function authHeaders(extra) {
  const headers = { 'Content-Type': 'application/json', ...(extra || {}) };
  if (userState.token) {
    headers.Authorization = `Bearer ${userState.token}`;
  }
  return headers;
}

function saveSession(token, user) {
  userState.token = token;
  userState.customer = user;
  try {
    sessionStorage.setItem(TOKEN_STORAGE_KEY, token);
    sessionStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user));
  } catch (storageError) {
    // Private browsing etc.: keep the session in memory only.
  }
}

function restoreSession() {
  try {
    const token = sessionStorage.getItem(TOKEN_STORAGE_KEY);
    const user = sessionStorage.getItem(USER_STORAGE_KEY);
    if (token && user) {
      userState.token = token;
      userState.customer = JSON.parse(user);
      return true;
    }
  } catch (storageError) {
    // Ignore and treat as signed out.
  }
  return false;
}

function clearSession() {
  userState.token = null;
  userState.customer = null;
  userState.dashboard = null;
  try {
    sessionStorage.removeItem(TOKEN_STORAGE_KEY);
    sessionStorage.removeItem(USER_STORAGE_KEY);
  } catch (storageError) {
    // Ignore.
  }
}

function handleUnauthorized() {
  clearSession();
  syncAuthUi();
  opsState.merchants = [];
  opsState.agents = [];
  opsState.devices = [];
  renderOperations();
  financingState.applications = [];
  financingState.reviewQueue = [];
  renderApplicationTable();
  renderTransactionTable([]);
  const reviewSection = document.getElementById('review');
  if (reviewSection) reviewSection.style.display = 'none';
  const summary = document.getElementById('user-summary');
  if (summary) {
    summary.innerHTML = '<p class="error-text">Your session has expired. Please sign in again.</p>';
  }
  const loginSection = document.getElementById('customer');
  if (loginSection) {
    loginSection.scrollIntoView({ behavior: 'smooth' });
  }
}

function syncAuthUi() {
  const nameInput = document.getElementById('customer-name');
  if (nameInput && userState.customer) {
    nameInput.value = userState.customer.fullName || '';
  }
  const logoutButton = document.getElementById('logout-btn');
  if (logoutButton) {
    logoutButton.style.display = userState.customer ? 'inline-flex' : 'none';
  }
}

function formatCurrency(value) {
  const number = Number(value || 0);
  return new Intl.NumberFormat('en-KE', {
    style: 'currency',
    currency: 'KES',
    maximumFractionDigits: 0,
  }).format(number);
}

function normalizeContract(contract) {
  const productName = contract.productName || contract.product?.name || 'Phone';
  const status = String(contract.deviceStatus || contract.status || 'ACTIVE').toUpperCase();

  return {
    ...contract,
    id: contract.id,
    customerId: contract.customerId,
    customerName: contract.customerName || 'Unknown customer',
    productName,
    depositPaid: Number(contract.depositPaid ?? 0),
    amountRemaining: Number(contract.amountRemaining ?? contract.balanceRemaining ?? 0),
    dailyAmount: Number(contract.dailyAmount ?? 0),
    deviceStatus: status,
    status,
  };
}

function getSelectedProduct() {
  return state.products.find((product) => product.id === state.selectedProductId) || state.products[0] || fallbackProducts[0];
}

function getActiveContract() {
  const select = document.getElementById('contract-select');
  if (!select || !select.value) return null;
  const selectedId = Number(select.value);
  return state.contracts.find((contract) => Number(contract.id) === selectedId) || null;
}

async function loadProducts() {
  try {
    const response = await fetch(`${API_BASE}/devices`, { headers: authHeaders() });
    if (response.status === 401) {
      handleUnauthorized();
      state.products = fallbackProducts;
    } else if (!response.ok) {
      throw new Error('Backend unavailable');
    } else {
      const data = await response.json();
      if (Array.isArray(data) && data.length > 0) {
        state.products = data;
      } else {
        state.products = fallbackProducts;
      }
    }
  } catch (error) {
    state.products = fallbackProducts;
  }

  if (!state.selectedProductId && state.products.length > 0) {
    state.selectedProductId = state.products[0].id;
  }

  renderProducts();
  syncSelectedProductFields();
}

async function loadContracts() {
  // Staff see the full ledger; customers see only their own contracts.
  const staff = userState.customer && ['ADMIN', 'LOAN_OFFICER'].includes(userState.customer.role);
  const endpoint = staff ? `${API_BASE}/loans` : `${API_BASE}/loans/me`;
  try {
    const response = await fetch(endpoint, { headers: authHeaders() });
    if (response.status === 401) {
      handleUnauthorized();
      state.contracts = [];
    } else if (!response.ok) {
      throw new Error('No loan list');
    } else {
      const data = await response.json();
      state.contracts = Array.isArray(data) ? data.map(normalizeContract) : [];
    }
  } catch (error) {
    state.contracts = [];
  }

  renderDashboard();
}

function updateHeroSummary() {
  const activeCount = state.contracts.filter((contract) => (contract.deviceStatus || contract.status) === 'ACTIVE').length;
  const overdueCount = state.contracts.filter((contract) => (contract.deviceStatus || contract.status) === 'OVERDUE').length;
  const paidCount = state.contracts.filter((contract) => (contract.deviceStatus || contract.status) === 'PAID_OFF').length;

  const systemStatus = document.getElementById('system-status');
  const activeEl = document.getElementById('active-count');
  const overdueEl = document.getElementById('overdue-count');
  const paidEl = document.getElementById('paid-count');

  if (systemStatus) {
    systemStatus.textContent = `${state.contracts.length} total contracts`;
  }
  if (activeEl) activeEl.textContent = String(activeCount);
  if (overdueEl) overdueEl.textContent = String(overdueCount);
  if (paidEl) paidEl.textContent = String(paidCount);
}

function renderProducts() {
  const grid = document.getElementById('product-grid');
  if (!grid) return;

  grid.innerHTML = state.products
    .map((product) => {
      const price = Number(product.fullPrice ?? product.totalPrice ?? 0);
      const deposit = Number(product.downPayment ?? product.deposit ?? 0);
      const daily = Number(product.dailyAmount ?? 0);
      const icon = product.icon || '📱';
      const description = product.description || 'Smartphone installment plan';

      return `
        <article class="product-card ${product.id === state.selectedProductId ? 'selected' : ''}" data-id="${product.id}">
          <div class="product-image">${icon}</div>
          <h3>${product.name}</h3>
          <p>${description}</p>
          <div class="price-row">
            <div>
              <span>Total</span>
              <strong>${formatCurrency(price)}</strong>
            </div>
            <div>
              <span>Deposit</span>
              <strong>${formatCurrency(deposit)}</strong>
            </div>
          </div>
          <div class="price-row">
            <div>
              <span>Daily</span>
              <strong>${formatCurrency(daily)}</strong>
            </div>
            <div>
              <span>Status</span>
              <strong>Available</strong>
            </div>
          </div>
        </article>
      `;
    })
    .join('');

  grid.querySelectorAll('.product-card').forEach((card) => {
    card.addEventListener('click', () => {
      state.selectedProductId = Number(card.dataset.id);
      renderProducts();
      syncSelectedProductFields();
    });
  });
}

function syncSelectedProductFields() {
  const selectedProduct = getSelectedProduct();
  const selectedPhone = document.getElementById('selected-phone');
  const selectedDeposit = document.getElementById('selected-deposit');
  const selectedDaily = document.getElementById('selected-daily');

  if (selectedPhone) selectedPhone.value = selectedProduct.name;
  if (selectedDeposit) selectedDeposit.textContent = formatCurrency(selectedProduct.downPayment ?? selectedProduct.deposit ?? 0);
  if (selectedDaily) selectedDaily.textContent = formatCurrency(selectedProduct.dailyAmount ?? 0);
}

function renderContractSelect() {
  const select = document.getElementById('contract-select');
  if (!select) return;

  const normalizedContracts = state.contracts.map(normalizeContract);
  select.innerHTML = normalizedContracts
    .map(
      (contract) => `
        <option value="${contract.id}">${contract.customerName} - ${contract.productName}</option>
      `
    )
    .join('');

  if (normalizedContracts.length > 0) {
    select.value = normalizedContracts[normalizedContracts.length - 1].id;
  }
}

function renderContractTable() {
  const tableBody = document.getElementById('contract-table');
  if (!tableBody) return;

  tableBody.innerHTML = state.contracts
    .map((contract) => {
      const normalized = normalizeContract(contract);
      const badgeClass = normalized.status === 'PAID_OFF' ? 'paid' : normalized.status.toLowerCase();
      const badgeText = normalized.status === 'PAID_OFF' ? 'PAID OFF' : normalized.status.replace('_', ' ');

      return `
        <tr>
          <td>${normalized.customerName}</td>
          <td>${normalized.productName}</td>
          <td>${formatCurrency(normalized.amountRemaining)}</td>
          <td>${formatCurrency(normalized.dailyAmount)}</td>
          <td><span class="status-badge ${badgeClass}">${badgeText}</span></td>
        </tr>
      `;
    })
    .join('');
}

function updateLockScreen() {
  const contract = getActiveContract();
  const title = document.getElementById('lock-title');
  const message = document.getElementById('lock-message');
  const screen = document.getElementById('device-lock-screen');

  if (!contract) {
    if (title) title.textContent = 'No contract';
    if (message) message.textContent = 'Select a customer to view status.';
    if (screen) screen.className = 'device-screen status-overdue';
    return;
  }

  const normalized = normalizeContract(contract);
  if (normalized.status === 'PAID_OFF') {
    if (title) title.textContent = 'Owned';
    if (message) message.textContent = `${normalized.customerName} has fully paid for ${normalized.productName}.`;
    if (screen) screen.className = 'device-screen status-paid';
    return;
  }

  if (normalized.status === 'OVERDUE') {
    if (title) title.textContent = 'Locked';
    if (message) message.textContent = `Please pay ${formatCurrency(normalized.dailyAmount)} to restore access.`;
    if (screen) screen.className = 'device-screen status-overdue';
    return;
  }

  if (title) title.textContent = 'Unlocked';
  if (message) message.textContent = `Next payment due: ${formatCurrency(normalized.dailyAmount)} today.`;
  if (screen) screen.className = 'device-screen status-active';
}

function renderLoanSummary() {
  const container = document.getElementById('loan-summary');
  if (!container) return;

  const contract = getActiveContract();
  if (!contract) {
    container.innerHTML = '<p>No active contract selected.</p>';
    return;
  }

  const normalized = normalizeContract(contract);
  container.innerHTML = `
    <div class="loan-box">
      <span>Customer</span>
      <strong>${normalized.customerName}</strong>
    </div>
    <div class="loan-box">
      <span>Phone</span>
      <strong>${normalized.productName}</strong>
    </div>
    <div class="loan-box">
      <span>Balance left</span>
      <strong>${formatCurrency(normalized.amountRemaining)}</strong>
    </div>
    <div class="loan-box">
      <span>Deposit paid</span>
      <strong>${formatCurrency(normalized.depositPaid)}</strong>
    </div>
  `;
}

function renderDashboard() {
  renderContractSelect();
  updateLockScreen();
  renderLoanSummary();
  renderContractTable();
  updateHeroSummary();
}

const financingState = {
  eligibleDevices: [],
  plans: [],
  applications: [],
  reviewQueue: [],
  summary: null,
};

function selectedFinancingDevice() {
  const select = document.getElementById('financing-device');
  const id = Number(select?.value);
  return financingState.eligibleDevices.find((device) => Number(device.id) === id) || null;
}

function selectedFinancingPlan() {
  const select = document.getElementById('financing-plan');
  const id = Number(select?.value);
  return financingState.plans.find((plan) => Number(plan.id) === id) || null;
}

async function loadFinancingLookups() {
  if (!userState.token) return;
  const [devices, plans] = await Promise.all([
    fetchOps('/financing/eligible-devices'),
    fetchOps('/financing/payment-plans'),
  ]);
  financingState.eligibleDevices = devices || [];
  financingState.plans = plans || [];

  const deviceSelect = document.getElementById('financing-device');
  if (deviceSelect) {
    deviceSelect.innerHTML = financingState.eligibleDevices
      .map((device) => `<option value="${device.id}">${device.deviceCode} • ${device.productName}</option>`)
      .join('');
  }
  const planSelect = document.getElementById('financing-plan');
  if (planSelect) {
    planSelect.innerHTML = financingState.plans
      .map((plan) => `<option value="${plan.id}">${plan.planName} (${plan.durationDays} days)</option>`)
      .join('');
  }
  await refreshQuote();
}

async function refreshQuote() {
  const device = selectedFinancingDevice();
  const plan = selectedFinancingPlan();
  const depositInput = document.getElementById('financing-deposit');
  const deposit = Number(depositInput?.value || 0);

  const setQuote = (price, principal, installment, total) => {
    const priceEl = document.getElementById('quote-price');
    const principalEl = document.getElementById('quote-principal');
    const installmentEl = document.getElementById('quote-installment');
    const totalEl = document.getElementById('quote-total');
    if (priceEl) priceEl.textContent = formatCurrency(price);
    if (principalEl) principalEl.textContent = formatCurrency(principal);
    if (installmentEl) installmentEl.textContent = formatCurrency(installment);
    if (totalEl) totalEl.textContent = formatCurrency(total);
  };

  if (!device || !plan || !userState.token) {
    setQuote(0, 0, 0, 0);
    return;
  }

  try {
    const params = new URLSearchParams({
      deviceId: String(device.id),
      paymentPlanId: String(plan.id),
      deposit: String(deposit),
    });
    const response = await fetch(`${API_BASE}/financing/quote?${params}`, { headers: authHeaders() });
    if (response.status === 401) {
      handleUnauthorized();
      return;
    }
    if (!response.ok) {
      setQuote(0, 0, 0, 0);
      return;
    }
    const quote = await response.json();
    setQuote(quote.devicePrice, quote.requestedPrincipal, quote.installmentAmount, quote.totalPayable);
  } catch (error) {
    setQuote(0, 0, 0, 0);
  }
}

async function submitApplication() {
  if (!userState.customer || !userState.token) {
    alert('Please sign in before applying for financing.');
    return;
  }

  const device = selectedFinancingDevice();
  const plan = selectedFinancingPlan();
  const deposit = Number(document.getElementById('financing-deposit')?.value || 0);

  if (!device || !plan) {
    alert('Select a device and a payment plan first.');
    return;
  }

  try {
    const created = await adminOpsRequest('/financing/applications', 'POST', {
      deviceId: Number(device.id),
      depositAmount: deposit,
      paymentPlanId: Number(plan.id),
    });
    if (!created) return;
    alert(`Application ${created.applicationNumber} submitted. Status: ${created.status}.`);
    await loadContracts();
    await loadFinancingLookups();
    await loadMyApplications();
  } catch (error) {
    alert(error.message || 'Could not submit the application.');
  }
}

async function loadMyApplications() {
  if (!userState.token) {
    financingState.applications = [];
    renderApplicationTable();
    return;
  }
  const applications = await fetchOps('/financing/applications/me');
  financingState.applications = applications || [];
  renderApplicationTable();
}

function renderApplicationTable() {
  const tableBody = document.getElementById('application-table');
  if (!tableBody) return;

  if (financingState.applications.length === 0) {
    tableBody.innerHTML = '<tr><td colspan="7">No applications yet. Approved contracts appear in the ledger below.</td></tr>';
    return;
  }

  tableBody.innerHTML = financingState.applications
    .map((application) => {
      const cancellable = application.status === 'SUBMITTED' || application.status === 'UNDER_REVIEW';
      return `
        <tr>
          <td>${application.applicationNumber}</td>
          <td>${application.productName} (${application.deviceCode})</td>
          <td>${formatCurrency(application.depositAmount)}</td>
          <td>${formatCurrency(application.requestedPrincipal)}</td>
          <td><span class="status-badge ${String(application.status).toLowerCase()}">${application.status}</span></td>
          <td>${application.decisionReason || application.reviewedByName || '—'}</td>
          <td>${cancellable ? `<button class="ghost-btn" data-cancel-application="${application.id}" type="button">Cancel</button>` : ''}</td>
        </tr>
      `;
    })
    .join('');

  tableBody.querySelectorAll('[data-cancel-application]').forEach((button) => {
    button.addEventListener('click', () => cancelApplication(Number(button.dataset.cancelApplication)));
  });
}

async function cancelApplication(applicationId) {
  try {
    await adminOpsRequest(`/financing/applications/me/${applicationId}/cancel`, 'POST');
    await loadMyApplications();
    await loadFinancingLookups();
  } catch (error) {
    alert(error.message || 'Could not cancel the application.');
  }
}

function reviewRole() {
  return opsRole() === 'ADMIN' || opsRole() === 'LOAN_OFFICER';
}

async function loadReviewQueue() {
  const section = document.getElementById('review');
  if (!reviewRole()) {
    if (section) section.style.display = 'none';
    financingState.reviewQueue = [];
    return;
  }
  if (section) section.style.display = 'block';

  const statusFilter = document.getElementById('review-status-filter')?.value || '';
  const [queue, summary] = await Promise.all([
    fetchOps(`/admin/financing/applications${statusFilter ? `?status=${statusFilter}` : ''}`),
    fetchOps('/admin/financing/applications/summary'),
  ]);
  financingState.reviewQueue = queue || [];
  financingState.summary = summary || null;
  renderReviewQueue();
}

function renderReviewQueue() {
  const summary = financingState.summary;
  const summaryEl = document.getElementById('review-summary');
  if (summaryEl && summary) {
    summaryEl.innerHTML = `
      <div><strong>${summary.totalApplications}</strong><span>Total applications</span></div>
      <div><strong>${summary.submitted + summary.underReview}</strong><span>Pending review</span></div>
      <div><strong>${summary.approved}</strong><span>Approved</span></div>
    `;
  }

  const tableBody = document.getElementById('review-table');
  if (tableBody) {
    tableBody.innerHTML = financingState.reviewQueue
      .map((application) => `
        <tr>
          <td>${application.applicationNumber}</td>
          <td>${application.customerName}</td>
          <td>${application.productName} (${application.deviceCode})</td>
          <td>${formatCurrency(application.requestedPrincipal)}</td>
          <td><span class="status-badge ${String(application.status).toLowerCase()}">${application.status}</span></td>
          <td>${application.reviewedByName || '—'}</td>
          <td><button class="ghost-btn" data-review-application="${application.id}" type="button">Review</button></td>
        </tr>
      `)
      .join('');

    tableBody.querySelectorAll('[data-review-application]').forEach((button) => {
      button.addEventListener('click', () => {
        const select = document.getElementById('review-application');
        if (select) select.value = button.dataset.reviewApplication;
        renderReviewDetail();
      });
    });
  }

  const select = document.getElementById('review-application');
  if (select) {
    const current = select.value;
    select.innerHTML = financingState.reviewQueue
      .map((application) => `<option value="${application.id}">${application.applicationNumber} • ${application.status}</option>`)
      .join('');
    if (current) select.value = current;
  }
  renderReviewDetail();
}

function renderReviewDetail() {
  const container = document.getElementById('review-detail');
  if (!container) return;
  const select = document.getElementById('review-application');
  const application = financingState.reviewQueue.find((item) => String(item.id) === select?.value);

  if (!application) {
    container.innerHTML = '<p>Select an application to inspect it.</p>';
    return;
  }

  container.innerHTML = `
    <div class="loan-box"><span>Customer</span><strong>${application.customerName} (${application.customerCode})</strong></div>
    <div class="loan-box"><span>Device</span><strong>${application.productName} • ${application.deviceCode}</strong></div>
    <div class="loan-box"><span>IMEI</span><strong>${application.imeiMasked || '—'}</strong></div>
    <div class="loan-box"><span>Device price</span><strong>${formatCurrency(application.devicePrice)}</strong></div>
    <div class="loan-box"><span>Deposit</span><strong>${formatCurrency(application.depositAmount)}</strong></div>
    <div class="loan-box"><span>Requested principal</span><strong>${formatCurrency(application.requestedPrincipal)}</strong></div>
    <div class="loan-box"><span>Plan</span><strong>${application.paymentPlanName}</strong></div>
    <div class="loan-box"><span>Status</span><strong>${application.status}</strong></div>
  `;
}

async function reviewDecision(action) {
  const select = document.getElementById('review-application');
  const applicationId = select?.value;
  if (!applicationId) {
    alert('Select an application first.');
    return;
  }
  const reason = document.getElementById('review-reason')?.value?.trim() || '';

  try {
    if (action === 'review') {
      await adminOpsRequest(`/admin/financing/applications/${applicationId}/review`, 'POST');
    } else if (action === 'approve') {
      const contract = await adminOpsRequest(`/admin/financing/applications/${applicationId}/approve`, 'POST');
      if (contract) alert(`Approved. Contract ${contract.contractNumber} is now ACTIVE.`);
    } else if (action === 'reject') {
      if (!reason) {
        alert('A decision reason is required to reject.');
        return;
      }
      await adminOpsRequest(`/admin/financing/applications/${applicationId}/reject`, 'POST', {
        decisionReason: reason,
      });
    }
    const reasonInput = document.getElementById('review-reason');
    if (reasonInput) reasonInput.value = '';
    await loadReviewQueue();
    await loadContracts();
    await loadFinancingLookups();
    await loadMyApplications();
  } catch (error) {
    alert(error.message || 'Decision failed.');
  }
}

async function initiatePayment() {
  const contract = getActiveContract();
  const paymentInput = document.getElementById('payment-amount');
  const channelSelect = document.getElementById('payment-channel');
  const scenarioSelect = document.getElementById('payment-scenario');
  const resultEl = document.getElementById('payment-result');

  if (!contract || !paymentInput) {
    alert('Select a customer contract before making a payment.');
    return;
  }

  const payment = Number(paymentInput.value);
  if (!Number.isFinite(payment) || payment <= 0) {
    alert('Enter a valid payment amount greater than zero.');
    return;
  }

  // The server owns status, references, and balances. The browser sends only
  // contract + amount + channel (+ a mock simulation knob). A fresh
  // idempotency key per click makes accidental double-clicks safe.
  const key = (window.crypto?.randomUUID && window.crypto.randomUUID())
    || `key-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const payload = {
    contractId: Number(contract.id),
    amount: payment,
    channel: channelSelect?.value || 'MPESA',
    mockScenario: scenarioSelect?.value || 'AUTO',
  };

  try {
    const response = await fetch(`${API_BASE}/payments`, {
      method: 'POST',
      headers: { ...authHeaders(), 'Idempotency-Key': key },
      body: JSON.stringify(payload),
    });

    if (response.status === 401) {
      handleUnauthorized();
      return;
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.message || 'Payment failed.');
    }

    const transaction = await response.json();
    renderPaymentResult(transaction);
    await loadContracts();
    await loadCustomerDashboard();
    await loadMyTransactions();
  } catch (error) {
    if (resultEl) {
      resultEl.innerHTML = `<p class="error-text">${error.message || 'Payment processing failed.'}</p>`;
    } else {
      alert(error.message || 'Payment processing failed.');
    }
  }
}

function renderPaymentResult(transaction) {
  const resultEl = document.getElementById('payment-result');
  if (!resultEl || !transaction) return;

  const statusNote = {
    POSTED: 'Posted. Balance reduced and receipt issued.',
    SUCCESS: 'Confirmed by provider. Awaiting posting — balance unchanged.',
    PENDING: 'Pending with provider. Balance unchanged until posted.',
    FAILED: `Failed: ${transaction.failureReason || 'provider declined'}. Balance unchanged.`,
  }[transaction.status] || transaction.status;

  resultEl.innerHTML = `
    <div class="loan-box"><span>Reference</span><strong>${transaction.transactionReference}</strong></div>
    <div class="loan-box"><span>Status</span><strong>${transaction.status}</strong></div>
    <div class="loan-box"><span>Provider ref</span><strong>${transaction.providerReference || '—'}</strong></div>
    <div class="loan-box"><span>Receipt</span><strong>${transaction.receiptNumber || '—'}</strong></div>
    <div class="loan-box"><span>Balance</span><strong>${formatCurrency(transaction.remainingBalance)}</strong></div>
    <p class="ops-hint">${statusNote}</p>
  `;
}

async function loadMyTransactions() {
  if (!userState.token) {
    renderTransactionTable([]);
    return;
  }
  const transactions = await fetchOps('/payments/me');
  renderTransactionTable(transactions || []);
}

function staffPaymentsRole() {
  return opsRole() === 'ADMIN' || opsRole() === 'PAYMENTS_OPERATOR';
}

async function loadStaffPayments() {
  if (!userState.token || !staffPaymentsRole()) {
    renderStaffPayments([]);
    const panel = document.getElementById('staff-payments-panel');
    if (panel) panel.style.display = 'none';
    return;
  }
  const panel = document.getElementById('staff-payments-panel');
  if (panel) panel.style.display = 'block';
  const transactions = await fetchOps('/admin/payments');
  renderStaffPayments(transactions || []);
}

function renderStaffPayments(transactions) {
  const tableBody = document.getElementById('staff-payments-table');
  if (!tableBody) return;

  const actionable = transactions.filter((tx) => tx.status === 'SUCCESS' || tx.status === 'PENDING');
  const rows = actionable.length > 0 ? actionable : transactions;

  if (rows.length === 0) {
    tableBody.innerHTML = '<tr><td colspan="6">No transactions to display.</td></tr>';
    return;
  }

  tableBody.innerHTML = rows
    .map((tx) => `
      <tr>
        <td>${tx.transactionReference}</td>
        <td>${tx.customerName || ''}</td>
        <td>${formatCurrency(tx.amount)}</td>
        <td><span class="status-badge ${String(tx.status).toLowerCase()}">${tx.status}</span></td>
        <td>${tx.providerReference || '—'}</td>
        <td>${tx.status === 'SUCCESS' ? `<button class="ghost-btn" data-post-payment="${tx.transactionReference}" type="button">Post</button>` : ''}</td>
      </tr>
    `)
    .join('');

  tableBody.querySelectorAll('[data-post-payment]').forEach((button) => {
    button.addEventListener('click', () => postDeferredPayment(button.dataset.postPayment));
  });
}

async function postDeferredPayment(reference) {
  try {
    const receipt = await adminOpsRequest(`/payments/${reference}/post`, 'POST');
    if (receipt) alert(`Posted. Receipt ${receipt.receiptNumber}. Balance ${formatCurrency(receipt.remainingBalance)}.`);
    await loadStaffPayments();
    await loadMyTransactions();
    await loadContracts();
    await loadCustomerDashboard();
  } catch (error) {
    alert(error.message || 'Could not post the payment.');
  }
}

function renderTransactionTable(transactions) {
  const tableBody = document.getElementById('transaction-table');
  if (!tableBody) return;

  if (transactions.length === 0) {
    tableBody.innerHTML = '<tr><td colspan="6">No payment activity yet.</td></tr>';
    return;
  }

  const formatDate = (value) => {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();
  };

  tableBody.innerHTML = transactions
    .map((tx) => {
      const note = tx.status === 'POSTED'
        ? (tx.receiptNumber || '')
        : (tx.failureReason || (tx.status === 'PENDING' ? 'awaiting provider' : 'awaiting posting'));
      return `
        <tr>
          <td>${formatDate(tx.requestedAt)}</td>
          <td>${tx.transactionReference}</td>
          <td>${formatCurrency(tx.amount)}</td>
          <td>${tx.channel}</td>
          <td><span class="status-badge ${String(tx.status).toLowerCase()}">${tx.status}</span></td>
          <td>${note || '—'}</td>
        </tr>
      `;
    })
    .join('');
}

const opsState = {
  merchants: [],
  agents: [],
  devices: [],
};

function opsRole() {
  return userState.customer?.role || null;
}

function renderDeviceTable() {
  const tableBody = document.getElementById('device-table');
  if (!tableBody) return;

  if (opsState.devices.length === 0) {
    tableBody.innerHTML = '<tr><td colspan="6">No devices to display. Sign in to view inventory.</td></tr>';
    return;
  }

  tableBody.innerHTML = opsState.devices
    .map((device) => `
        <tr>
          <td>${device.deviceCode || device.id}</td>
          <td>${device.productName || '—'}</td>
          <td>${device.imei || '—'}</td>
          <td><span class="status-badge ${(device.status || '').toLowerCase()}">${device.status || '—'}</span></td>
          <td>${device.merchantName || '—'}</td>
          <td>${device.customerName || '—'}</td>
        </tr>
      `)
    .join('');
}

function renderMerchantTable() {
  const tableBody = document.getElementById('merchant-table');
  if (!tableBody) return;

  if (opsState.merchants.length === 0) {
    tableBody.innerHTML = '<tr><td colspan="5">No merchants visible for this account.</td></tr>';
    return;
  }

  tableBody.innerHTML = opsState.merchants
    .map((merchant) => `
        <tr>
          <td>${merchant.merchantCode || merchant.id}</td>
          <td>${merchant.businessName || '—'}</td>
          <td><span class="status-badge ${(merchant.status || '').toLowerCase()}">${merchant.status || '—'}</span></td>
          <td>${merchant.agentCount ?? '—'}</td>
          <td>${merchant.deviceCount ?? '—'}</td>
        </tr>
      `)
    .join('');
}

function renderAgentTable() {
  const tableBody = document.getElementById('agent-table');
  if (!tableBody) return;

  if (opsState.agents.length === 0) {
    tableBody.innerHTML = '<tr><td colspan="4">No agents visible for this account.</td></tr>';
    return;
  }

  tableBody.innerHTML = opsState.agents
    .map((agent) => `
        <tr>
          <td>${agent.agentCode || agent.id}</td>
          <td>${agent.fullName || '—'}</td>
          <td>${agent.merchantName || '—'}</td>
          <td><span class="status-badge ${(agent.status || '').toLowerCase()}">${agent.status || '—'}</span></td>
        </tr>
      `)
    .join('');
}

function populateOpsSelects() {
  const productSelect = document.getElementById('new-device-product');
  if (productSelect) {
    productSelect.innerHTML = state.products
      .map((product) => `<option value="${product.id}">${product.name}</option>`)
      .join('');
  }

  const deviceOptions = opsState.devices
    .map((device) => `<option value="${device.id}">${device.deviceCode} • ${device.status}</option>`)
    .join('');
  for (const id of ['assign-device', 'status-device']) {
    const select = document.getElementById(id);
    if (select) select.innerHTML = deviceOptions;
  }

  const merchantSelect = document.getElementById('assign-merchant');
  if (merchantSelect) {
    merchantSelect.innerHTML = opsState.merchants
      .map((merchant) => `<option value="${merchant.id}">${merchant.businessName}</option>`)
      .join('');
  }

  const agentSelect = document.getElementById('assign-agent');
  if (agentSelect) {
    agentSelect.innerHTML = opsState.agents
      .map((agent) => `<option value="${agent.id}">${agent.fullName} (${agent.agentCode})</option>`)
      .join('');
  }

  const customerSelect = document.getElementById('assign-customer');
  if (customerSelect) {
    const seen = new Map();
    state.contracts.forEach((contract) => {
      if (contract.customerId != null && !seen.has(contract.customerId)) {
        seen.set(contract.customerId, contract.customerName);
      }
    });
    customerSelect.innerHTML = [...seen.entries()]
      .map(([id, name]) => `<option value="${id}">${name}</option>`)
      .join('');
  }
}

function renderOperations() {
  renderDeviceTable();
  renderMerchantTable();
  renderAgentTable();
  populateOpsSelects();
  const adminPanel = document.getElementById('admin-ops-panel');
  if (adminPanel) {
    adminPanel.style.display = opsRole() === 'ADMIN' ? 'block' : 'none';
  }
}

async function fetchOps(path) {
  const response = await fetch(`${API_BASE}${path}`, { headers: authHeaders() });
  if (response.status === 401) {
    handleUnauthorized();
    return null;
  }
  if (!response.ok) {
    return null;
  }
  return response.json();
}

async function loadOperations() {
  const role = opsRole();
  opsState.merchants = [];
  opsState.agents = [];
  opsState.devices = [];

  if (!userState.token) {
    renderOperations();
    return;
  }

  const statusFilter = document.getElementById('device-status-filter')?.value || '';

  if (role === 'ADMIN') {
    const [merchants, agents, devices] = await Promise.all([
      fetchOps('/admin/merchants'),
      fetchOps('/admin/agents'),
      fetchOps(`/admin/devices${statusFilter ? `?status=${statusFilter}` : ''}`),
    ]);
    opsState.merchants = merchants || [];
    opsState.agents = agents || [];
    opsState.devices = devices || [];
  } else if (role === 'AGENT') {
    const [merchant, devices] = await Promise.all([
      fetchOps('/agents/me/merchant'),
      fetchOps('/agents/me/devices'),
    ]);
    opsState.merchants = merchant ? [merchant] : [];
    opsState.devices = devices || [];
  } else if (role === 'MERCHANT') {
    const [merchant, agents, devices] = await Promise.all([
      fetchOps('/merchants/me'),
      fetchOps('/merchants/me/agents'),
      fetchOps('/merchants/me/devices'),
    ]);
    opsState.merchants = merchant ? [merchant] : [];
    opsState.agents = agents || [];
    opsState.devices = devices || [];
  } else {
    const devices = await fetchOps('/customer/devices');
    opsState.devices = devices || [];
  }

  renderOperations();
}

async function adminOpsRequest(path, method, body) {
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers: authHeaders(),
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 401) {
    handleUnauthorized();
    return null;
  }
  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.message || 'Operation failed.');
  }
  return response.json();
}

async function createDevice() {
  const productSelect = document.getElementById('new-device-product');
  const imeiInput = document.getElementById('new-device-imei');
  const serialInput = document.getElementById('new-device-serial');

  try {
    await adminOpsRequest('/admin/devices', 'POST', {
      productId: Number(productSelect?.value),
      imei: imeiInput?.value?.trim() || null,
      serialNumber: serialInput?.value?.trim() || null,
    });
    if (imeiInput) imeiInput.value = '';
    if (serialInput) serialInput.value = '';
    await loadOperations();
  } catch (error) {
    alert(error.message || 'Could not register the device.');
  }
}

async function assignDevice() {
  const deviceSelect = document.getElementById('assign-device');
  const typeSelect = document.getElementById('assign-target-type');
  const type = typeSelect?.value || 'merchant';

  const payload = {};
  if (type === 'merchant') payload.merchantId = Number(document.getElementById('assign-merchant')?.value);
  if (type === 'agent') payload.agentId = Number(document.getElementById('assign-agent')?.value);
  if (type === 'customer') payload.customerId = Number(document.getElementById('assign-customer')?.value);

  try {
    await adminOpsRequest(`/admin/devices/${deviceSelect?.value}/assign`, 'POST', payload);
    await loadOperations();
  } catch (error) {
    alert(error.message || 'Could not assign the device.');
  }
}

async function releaseDevice() {
  const deviceSelect = document.getElementById('assign-device');
  try {
    await adminOpsRequest(`/admin/devices/${deviceSelect?.value}/release`, 'POST');
    await loadOperations();
  } catch (error) {
    alert(error.message || 'Could not release the device.');
  }
}

async function changeDeviceStatus() {
  const deviceSelect = document.getElementById('status-device');
  const statusSelect = document.getElementById('status-value');

  try {
    await adminOpsRequest(`/admin/devices/${deviceSelect?.value}/status`, 'PATCH', {
      status: statusSelect?.value,
    });
    await loadOperations();
  } catch (error) {
    alert(error.message || 'Could not change the device status.');
  }
}

function syncAssignTargetVisibility() {
  const type = document.getElementById('assign-target-type')?.value || 'merchant';
  const merchantWrap = document.getElementById('assign-merchant-wrap');
  const agentWrap = document.getElementById('assign-agent-wrap');
  const customerWrap = document.getElementById('assign-customer-wrap');
  if (merchantWrap) merchantWrap.style.display = type === 'merchant' ? 'block' : 'none';
  if (agentWrap) agentWrap.style.display = type === 'agent' ? 'block' : 'none';
  if (customerWrap) customerWrap.style.display = type === 'customer' ? 'block' : 'none';
}

async function resetDemo() {
  state.selectedProductId = 1;
  document.getElementById('payment-amount').value = '70';
  syncAuthUi();
  await loadProducts();
  await loadContracts();
  if (userState.token) {
    await loadCustomerDashboard();
    await loadOperations();
    await loadFinancingLookups();
    await loadMyApplications();
    await loadReviewQueue();
    await loadMyTransactions();
    await loadStaffPayments();
  }
}

async function loginCustomer(event) {
  if (event && typeof event.preventDefault === 'function') {
    event.preventDefault();
  }

  const emailInput = document.getElementById('customer-email');
  const passwordInput = document.getElementById('customer-password');

  const payload = {
    email: emailInput?.value?.trim() || '',
    password: passwordInput?.value || '',
  };

  if (!payload.email || !payload.password) {
    const summary = document.getElementById('user-summary');
    if (summary) {
      summary.innerHTML = '<p class="error-text">Enter your email and password to sign in.</p>';
    }
    return;
  }

  try {
    const response = await fetch(`${API_BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });

    if (!response.ok) {
      throw new Error('Login failed. Check your credentials and try again.');
    }

    const auth = await response.json();
    // Session identity comes from the server response; the password is never stored.
    saveSession(auth.token, {
      id: auth.userId,
      customerCode: auth.customerCode,
      fullName: auth.fullName,
      role: auth.role,
    });
    if (passwordInput) {
      passwordInput.value = '';
    }
    syncAuthUi();
    await loadProducts();
    await loadContracts();
    await loadCustomerDashboard();
    await loadOperations();
    await loadFinancingLookups();
    await loadMyApplications();
    await loadReviewQueue();
    await loadMyTransactions();
    await loadStaffPayments();
  } catch (error) {
    const summary = document.getElementById('user-summary');
    if (summary) {
      summary.innerHTML = `<p class="error-text">${error.message || 'Unable to sign in.'}</p>`;
    }
  }
}

function signOut() {
  clearSession();
  syncAuthUi();
  const summary = document.getElementById('user-summary');
  if (summary) {
    summary.innerHTML = '<p>Sign in to see your active plans, balances, and cash-loan requests.</p>';
  }
  const overview = document.getElementById('user-payment-overview');
  if (overview) {
    overview.innerHTML = '<p>No payment activity available yet.</p>';
  }
  state.contracts = [];
  renderDashboard();
  opsState.merchants = [];
  opsState.agents = [];
  opsState.devices = [];
  renderOperations();
  financingState.eligibleDevices = [];
  financingState.plans = [];
  financingState.applications = [];
  financingState.reviewQueue = [];
  financingState.summary = null;
  renderApplicationTable();
  renderTransactionTable([]);
  renderStaffPayments([]);
  const reviewSection = document.getElementById('review');
  if (reviewSection) reviewSection.style.display = 'none';
}

async function applyCashLoan(event) {
  if (event && typeof event.preventDefault === 'function') {
    event.preventDefault();
  }

  if (!userState.customer || !userState.token) {
    alert('Please log in before applying for a cash loan.');
    return;
  }

  const amountInput = document.getElementById('cash-loan-amount');
  const rateInput = document.getElementById('cash-loan-rate');
  const purposeInput = document.getElementById('cash-loan-purpose');

  // The loan is recorded for the signed-in user server-side; no id is sent.
  const payload = {
    amountRequested: Number(amountInput?.value || 0),
    monthlyRate: Number(rateInput?.value || 0),
    purpose: purposeInput?.value || 'General personal loan',
  };

  try {
    const response = await fetch(`${API_BASE}/cash-loans`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(payload),
    });

    if (response.status === 401) {
      handleUnauthorized();
      return;
    }

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new Error(errorData.message || 'Could not submit your cash loan request.');
    }

    await loadCustomerDashboard();
    alert('Cash loan request submitted successfully.');
  } catch (error) {
    alert(error.message || 'Something went wrong while submitting your cash loan.');
  }
}

async function loadCustomerDashboard() {
  try {
    const response = await fetch(`${API_BASE}/users/me/dashboard`, { headers: authHeaders() });
    if (response.status === 401) {
      handleUnauthorized();
      return;
    }
    if (!response.ok) {
      throw new Error('Could not load user dashboard.');
    }

    userState.dashboard = await response.json();
    renderUserSummary();
    renderPaymentOverview();
  } catch (error) {
    const summary = document.getElementById('user-summary');
    if (summary) {
      summary.innerHTML = `<p class="error-text">${error.message || 'Unable to load dashboard.'}</p>`;
    }
  }
}

function renderUserSummary() {
  const summary = document.getElementById('user-summary');
  if (!summary) return;

  const customer = userState.customer || userState.dashboard?.customer;
  const totalBalance = Number(userState.dashboard?.totalBalance || 0);
  const totalPaid = Number(userState.dashboard?.totalPaid || 0);

  if (!customer) {
    summary.innerHTML = '<p>Sign in to see your active plans, balances, and cash-loan requests.</p>';
    return;
  }

  summary.innerHTML = `
    <div class="user-summary-card">
      <span>Customer</span>
      <strong>${customer.fullName}</strong>
    </div>
    <div class="user-summary-card">
      <span>Account</span>
      <strong>${customer.customerCode}</strong>
    </div>
    <div class="user-summary-card">
      <span>Balance due</span>
      <strong>${formatCurrency(totalBalance)}</strong>
    </div>
    <div class="user-summary-card">
      <span>Total paid</span>
      <strong>${formatCurrency(totalPaid)}</strong>
    </div>
  `;
}

function renderPaymentOverview() {
  const overview = document.getElementById('user-payment-overview');
  if (!overview) return;

  const summary = userState.dashboard?.paymentSummary || {};
  const timeline = userState.dashboard?.paymentTimeline || [];

  const summaryHtml = `
    <div class="loan-box"><span>Total financed</span><strong>${formatCurrency(summary.totalFinanced)}</strong></div>
    <div class="loan-box"><span>Total paid</span><strong>${formatCurrency(summary.paidDevices)}</strong></div>
    <div class="loan-box"><span>Outstanding</span><strong>${formatCurrency(summary.outstandingDevices)}</strong></div>
    <div class="loan-box"><span>Expected today</span><strong>${formatCurrency(summary.todayExpected)}</strong></div>
    <div class="loan-box"><span>Arrears</span><strong>${formatCurrency(summary.arrears)}</strong></div>
    <div class="loan-box"><span>Status</span><strong>${summary.currentStatus || '—'}</strong></div>
  `;

  const formatDate = (value) => {
    if (!value) return '';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleDateString();
  };

  const timelineHtml = timeline.length === 0
    ? '<p>No payment activity available yet.</p>'
    : timeline.map((entry) => `
        <div class="loan-box">
          <span>${formatDate(entry.date)} • ${entry.channel} • ${entry.status}${entry.status === 'FAILED' && entry.failureReason ? ` — ${entry.failureReason}` : ''}</span>
          <strong>${formatCurrency(entry.amount)}${entry.balanceAfter != null ? ` (bal ${formatCurrency(entry.balanceAfter)})` : ''}</strong>
        </div>
      `).join('');

  overview.innerHTML = `${summaryHtml}<div class="ops-subhead" style="margin-top: 0.6rem;">Recent activity</div>${timelineHtml}`;
}

async function setup() {
  const yearEl = document.getElementById('year');
  if (yearEl) {
    yearEl.textContent = new Date().getFullYear();
  }

  const legacyDashboard = document.getElementById('product-grid');
  if (!legacyDashboard) {
    return;
  }

  await loadProducts();
  await loadContracts();

  const paymentButton = document.getElementById('submit-payment-btn');
  const resetButton = document.getElementById('reset-demo');
  const customerForm = document.getElementById('customer-login-form');
  const cashLoanForm = document.getElementById('cash-loan-form');
  const logoutButton = document.getElementById('logout-btn');
  const applicationButton = document.getElementById('submit-application-btn');
  const financingDeviceSelect = document.getElementById('financing-device');
  const financingPlanSelect = document.getElementById('financing-plan');
  const financingDepositInput = document.getElementById('financing-deposit');
  const reviewFilter = document.getElementById('review-status-filter');
  const reviewSelect = document.getElementById('review-application');
  const reviewStartButton = document.getElementById('review-start-btn');
  const reviewApproveButton = document.getElementById('review-approve-btn');
  const reviewRejectButton = document.getElementById('review-reject-btn');

  if (paymentButton) paymentButton.addEventListener('click', initiatePayment);
  if (resetButton) resetButton.addEventListener('click', resetDemo);
  if (customerForm) customerForm.addEventListener('submit', loginCustomer);
  if (cashLoanForm) cashLoanForm.addEventListener('submit', applyCashLoan);
  if (logoutButton) logoutButton.addEventListener('click', signOut);
  if (applicationButton) applicationButton.addEventListener('click', submitApplication);
  if (financingDeviceSelect) financingDeviceSelect.addEventListener('change', refreshQuote);
  if (financingPlanSelect) financingPlanSelect.addEventListener('change', refreshQuote);
  if (financingDepositInput) financingDepositInput.addEventListener('input', refreshQuote);
  if (reviewFilter) reviewFilter.addEventListener('change', loadReviewQueue);
  if (reviewSelect) reviewSelect.addEventListener('change', renderReviewDetail);
  if (reviewStartButton) reviewStartButton.addEventListener('click', () => reviewDecision('review'));
  if (reviewApproveButton) reviewApproveButton.addEventListener('click', () => reviewDecision('approve'));
  if (reviewRejectButton) reviewRejectButton.addEventListener('click', () => reviewDecision('reject'));

  const createDeviceButton = document.getElementById('create-device-btn');
  const assignDeviceButton = document.getElementById('assign-device-btn');
  const releaseDeviceButton = document.getElementById('release-device-btn');
  const changeStatusButton = document.getElementById('change-status-btn');
  const assignTypeSelect = document.getElementById('assign-target-type');
  const deviceFilter = document.getElementById('device-status-filter');

  if (createDeviceButton) createDeviceButton.addEventListener('click', createDevice);
  if (assignDeviceButton) assignDeviceButton.addEventListener('click', assignDevice);
  if (releaseDeviceButton) releaseDeviceButton.addEventListener('click', releaseDevice);
  if (changeStatusButton) changeStatusButton.addEventListener('click', changeDeviceStatus);
  if (assignTypeSelect) assignTypeSelect.addEventListener('change', syncAssignTargetVisibility);
  if (deviceFilter) deviceFilter.addEventListener('change', loadOperations);

  const contractSelect = document.getElementById('contract-select');
  if (contractSelect) {
    contractSelect.addEventListener('change', () => {
      updateLockScreen();
      renderLoanSummary();
    });
  }

  const customerEmail = document.getElementById('customer-email');
  const customerPassword = document.getElementById('customer-password');
  if (customerEmail && !customerEmail.value) customerEmail.value = 'demo@lipamdogo.com';
  if (customerPassword) customerPassword.value = '';

  // Resume a previous browser session if a token is stored; otherwise the user
  // signs in explicitly. Never store or replay passwords.
  if (restoreSession()) {
    syncAuthUi();
    await loadProducts();
    await loadContracts();
    await loadCustomerDashboard();
    await loadOperations();
    await loadFinancingLookups();
    await loadMyApplications();
    await loadReviewQueue();
    await loadMyTransactions();
    await loadStaffPayments();
  } else {
    syncAuthUi();
    renderOperations();
    renderApplicationTable();
    renderTransactionTable([]);
  }
}

document.addEventListener('DOMContentLoaded', setup);
