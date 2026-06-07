// auth.js — login, register, session bootstrap, API keys, onboarding, health.

  // ── Login ──
  async function login() {
    const btn = document.querySelector('#login-fields .btn-primary');
    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;
    const errEl = document.getElementById('login-error');
    errEl.className = 'response error';
    if (!email || !password) { errEl.className = 'response error visible'; errEl.textContent = 'Email and password required.'; return; }
    setLoading(btn, true);
    try {
      const data = await api('POST', '/auth/login', { email, password });
      token = data.token;
      currentUser = { email, role: data.role, cityId: data.cityId, expiresAt: data.expiresAt };
      await showDashboard(data);
    } catch (e) {
      errEl.className = 'response error visible';
      errEl.textContent = e.status === 401 ? 'Invalid email or password.' : fmtError(e);
    } finally {
      setLoading(btn, false);
    }
  }

  // ── Register ──
  function selectRegType(el) {
    document.querySelectorAll('#reg-type-group .type-btn').forEach(b => b.classList.remove('selected'));
    el.classList.add('selected');
  }

  async function register() {
    const btn = document.querySelector('#register-fields .btn-accent');
    const name = document.getElementById('reg-name').value.trim();
    const email = document.getElementById('reg-email').value.trim();
    const password = document.getElementById('reg-password').value;
    const accountType = document.querySelector('#reg-type-group .type-btn.selected')?.dataset.type || 'C2C_CUSTOMER';
    const errEl = document.getElementById('login-error');
    errEl.className = 'response error';
    if (!name || !email || !password) { errEl.className = 'response error visible'; errEl.textContent = 'All fields required.'; return; }
    setLoading(btn, true);
    try {
      if (accountType === 'C2C_CUSTOMER') {
        const data = await api('POST', '/auth/register', { name, email, password });
        token = data.token;
        currentUser = { email, role: data.role, cityId: data.cityId, expiresAt: data.expiresAt };
        await showDashboard(data);
      } else {
        await api('POST', '/auth/request-onboarding', { name, email, password, requestedRole: accountType });
        errEl.className = 'response success visible';
        errEl.textContent = 'Request submitted! An admin will review your account. You can log in once approved.';
      }
    } catch (e) {
      errEl.className = 'response error visible';
      errEl.textContent = fmtError(e);
    } finally {
      setLoading(btn, false);
    }
  }

  async function showDashboard(data) {
    // The app serves auth responses in snake_case (jackson SNAKE_CASE); accept both.
    data.expiresAt = data.expiresAt ?? data.expires_at;
    data.cityId = data.cityId ?? data.city_id;
    data.mustChangePassword = data.mustChangePassword ?? data.must_change_password;
    currentUser.cityId = data.cityId;
    currentUser.name = data.name;   // stored account name — used to auto-fill the sender

    document.getElementById('login-view').style.display = 'none';
    document.getElementById('dashboard-view').style.display = 'flex';
    document.getElementById('user-badge').style.display = 'flex';

    document.getElementById('nav-name').textContent = currentUser.email;
    document.getElementById('nav-role').textContent = data.role;

    document.getElementById('info-role').textContent = data.role;
    document.getElementById('info-city').textContent = data.cityId || 'Global';
    document.getElementById('info-expiry').textContent = new Date(data.expiresAt).toLocaleTimeString();
    document.getElementById('info-mustchange').textContent = data.mustChangePassword ? 'Yes ⚠️' : 'No';
    document.getElementById('token-display').textContent = data.token;

    // Pre-fill from JWT subject / current user email
    try {
      const payload = JSON.parse(atob(data.token.split('.')[1]));
      currentUserId = payload.sub;
      document.getElementById('ul-email').value = currentUser.email;
      document.getElementById('pc-email').value = currentUser.email;
    } catch {}

    // Lock permission-check email to own identity unless caller can query others
    const canQueryOthers = ['ADMIN', 'CALL_CENTER_AGENT'].includes(data.role);
    const pcEmail = document.getElementById('pc-email');
    pcEmail.readOnly = !canQueryOthers;
    pcEmail.style.opacity = canQueryOthers ? '' : '0.6';
    pcEmail.style.cursor  = canQueryOthers ? '' : 'not-allowed';

    // Show/hide admin-only cards and tabs
    const isAdmin = data.role === 'ADMIN';
    const canLookupUsers = ['ADMIN', 'CALL_CENTER_AGENT'].includes(data.role);
    document.getElementById('card-create-user').style.display = isAdmin ? '' : 'none';
    document.getElementById('card-create-role').style.display = isAdmin ? '' : 'none';
    document.getElementById('card-user-lookup').style.display = canLookupUsers ? '' : 'none';
    document.getElementById('ul-admin-actions').style.display = isAdmin ? '' : 'none';
    document.getElementById('onboarding-tab-btn').style.display = isAdmin ? '' : 'none';

    // API keys only available to ADMIN, B2B_USER, B2C_CUSTOMER
    const canUseApiKeys = ['ADMIN', 'B2B_USER', 'B2C_CUSTOMER'].includes(data.role);
    document.getElementById('card-api-keys').style.display = canUseApiKeys ? '' : 'none';

    // Fetch roles for dropdowns
    await loadRolesIntoDropdowns();

    // Bring the M4 order experience online for this identity
    setupOrderExperience(data.role);
  }

  function logout() {
    token = null; currentUser = null; currentUserId = null; rolesCache = []; lookedUpUserId = null;
    document.getElementById('login-view').style.display = 'flex';
    document.getElementById('dashboard-view').style.display = 'none';
    document.getElementById('user-badge').style.display = 'none';
    document.getElementById('login-password').value = '';
    document.getElementById('ul-actions').style.display = 'none';
    document.getElementById('ak-list-container').innerHTML = '<div class="empty-state">Click Refresh to load keys</div>';
    document.getElementById('roles-list-container').innerHTML = '<div class="empty-state">Click Refresh to load roles</div>';
    document.getElementById('ob-list-container').innerHTML = '<div class="empty-state">Click Refresh to load requests</div>';
    document.getElementById('onboarding-tab-btn').style.display = 'none';
    switchTab('auth');
  }

  function copyToken() {
    navigator.clipboard.writeText(token).then(() => {
      const btn = document.querySelector('.token-row .btn');
      const orig = btn.textContent;
      btn.textContent = 'Copied!';
      setTimeout(() => btn.textContent = orig, 1500);
    });
  }

  // ── API Keys ──
  async function createApiKey() {
    const btn = document.querySelector('#card-api-key .btn-primary') ||
                document.querySelector('[onclick="createApiKey()"]');
    const label = document.getElementById('ak-label').value.trim();
    if (!label) { alert('Enter a label'); return; }
    setLoading(btn, true);
    try {
      const data = await api('POST', '/auth/api-keys', { label });
      showResponse('ak-create-response', data, false);
      document.getElementById('ak-label').value = '';
      loadApiKeys();
    } catch (e) {
      showResponse('ak-create-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  async function loadApiKeys() {
    const container = document.getElementById('ak-list-container');
    container.innerHTML = '<div class="empty-state"><span class="spinner"></span></div>';
    try {
      const keys = await api('GET', '/auth/api-keys');
      if (!keys.length) { container.innerHTML = '<div class="empty-state">No API keys yet</div>'; return; }
      container.innerHTML = `
        <table class="data-table">
          <thead><tr>
            <th>Label</th><th>Status</th><th>Last Used</th><th>Created</th><th></th>
          </tr></thead>
          <tbody>
            ${keys.map(k => `
              <tr>
                <td><strong>${esc(k.label)}</strong></td>
                <td><span class="badge ${k.active ? 'badge-green' : 'badge-gray'}">${k.active ? 'Active' : 'Revoked'}</span></td>
                <td>${k.lastUsedAt ? new Date(k.lastUsedAt).toLocaleString() : '—'}</td>
                <td>${new Date(k.createdAt).toLocaleDateString()}</td>
                <td>${k.active
                  ? `<button class="btn btn-sm btn-danger" onclick="revokeApiKey('${k.id}', this)">Revoke</button>`
                  : '—'
                }</td>
              </tr>`).join('')}
          </tbody>
        </table>`;
    } catch (e) {
      container.innerHTML = `<div class="empty-state" style="color:#e63946">${fmtError(e)}</div>`;
    }
  }

  async function revokeApiKey(keyId, btn) {
    if (!confirm('Revoke this API key?')) return;
    setLoading(btn, true);
    try {
      await api('DELETE', `/auth/api-keys/${keyId}`);
      loadApiKeys();
    } catch (e) {
      alert(fmtError(e));
      setLoading(btn, false);
    }
  }

  // ── Health Check ──
  async function checkHealth() {
    const btn = document.querySelector('[onclick="checkHealth()"]');
    setLoading(btn, true);
    try {
      const data = await api('GET', '/auth/health');
      showResponse('health-response', data, false);
    } catch (e) {
      showResponse('health-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  // ── Onboarding Requests ──
  async function loadOnboardingRequests() {
    const container = document.getElementById('ob-list-container');
    container.innerHTML = '<div class="empty-state"><span class="spinner"></span></div>';
    document.getElementById('ob-response').className = 'response';
    try {
      const reqs = await api('GET', '/onboarding-requests');
      if (!reqs.length) { container.innerHTML = '<div class="empty-state">No onboarding requests</div>'; return; }
      container.innerHTML = `
        <table class="data-table">
          <thead><tr>
            <th>Email</th><th>Name</th><th>Role</th><th>Status</th><th>Submitted</th><th>Actions</th>
          </tr></thead>
          <tbody>
            ${reqs.map(r => `
              <tr>
                <td>${esc(r.email)}</td>
                <td>${esc(r.name)}</td>
                <td><span class="badge badge-blue">${esc(r.requestedRole)}</span></td>
                <td><span class="badge ${r.status === 'PENDING' ? 'badge-gray' : r.status === 'APPROVED' ? 'badge-green' : 'badge-red'}">${esc(r.status)}</span></td>
                <td>${new Date(r.createdAt).toLocaleDateString()}</td>
                <td style="display:flex;gap:.4rem;flex-wrap:wrap">
                  ${r.status === 'PENDING' ? `
                    <button class="btn btn-sm btn-success" onclick="approveOnboarding('${r.id}', this)">
                      Approve <span class="tag tag-post" style="margin-left:.2rem">POST</span>
                    </button>
                    <button class="btn btn-sm btn-danger" onclick="rejectOnboarding('${r.id}', this)">
                      Reject <span class="tag tag-post" style="margin-left:.2rem">POST</span>
                    </button>
                  ` : `<span style="color:#aaa;font-size:.8rem">${r.status === 'APPROVED' ? `Approved by ${esc(r.reviewedBy || '')}` : `Rejected — ${esc(r.rejectionReason || 'no reason')}`}</span>`}
                </td>
              </tr>`).join('')}
          </tbody>
        </table>`;
    } catch (e) {
      container.innerHTML = `<div class="empty-state" style="color:#e63946">${fmtError(e)}</div>`;
    }
  }

  async function approveOnboarding(id, btn) {
    if (!confirm('Approve this onboarding request? A user account will be created.')) return;
    setLoading(btn, true);
    try {
      await api('POST', `/onboarding-requests/${id}/approve`);
      showResponse('ob-response', 'Request approved. User account created with mustChangePassword=true.', false);
      loadOnboardingRequests();
    } catch (e) {
      showResponse('ob-response', e.body || e, true);
      setLoading(btn, false);
    }
  }

  async function rejectOnboarding(id, btn) {
    const reason = prompt('Rejection reason (optional):');
    if (reason === null) return; // cancelled
    setLoading(btn, true);
    try {
      await api('POST', `/onboarding-requests/${id}/reject`, { reason: reason || null });
      showResponse('ob-response', 'Request rejected.', false);
      loadOnboardingRequests();
    } catch (e) {
      showResponse('ob-response', e.body || e, true);
      setLoading(btn, false);
    }
  }
