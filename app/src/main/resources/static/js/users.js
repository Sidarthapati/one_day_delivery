// users.js — user management, roles, permissions.

  // ── Roles into dropdowns ──
  async function loadRolesIntoDropdowns() {
    try {
      rolesCache = await api('GET', '/roles');
      populateRoleSelect('cu-role', true);
      populateRoleSelect('cr-role-select', false);
    } catch (_) {}
  }

  function populateRoleSelect(selectId, includeEmpty) {
    const sel = document.getElementById(selectId);
    sel.innerHTML = includeEmpty ? '<option value="">— select role —</option>' : '<option value="">— select role —</option>';
    rolesCache.forEach(r => {
      // Auth API serialises in snake_case (jackson SNAKE_CASE); accept camelCase too.
      const displayName = r.display_name ?? r.displayName ?? r.name;
      const cityScoped  = r.city_scoped ?? r.cityScoped ?? false;
      const opt = document.createElement('option');
      opt.value = r.id;
      opt.textContent = displayName + (cityScoped ? ' (city)' : '');
      opt.dataset.cityScoped = cityScoped;
      opt.dataset.name = r.name;
      sel.appendChild(opt);
    });
  }

  function onCreateRoleChange() {
    const sel = document.getElementById('cu-role');
    const opt = sel.selectedOptions[0];
    const cityScoped = opt && opt.dataset.cityScoped === 'true';
    document.getElementById('cu-city-field').classList.toggle('show', cityScoped);
  }

  // ── User Lookup ──
  async function getUser() {
    const btn = document.querySelector('[onclick="getUser()"]');
    const email = document.getElementById('ul-email').value.trim();
    if (!email) { alert('Enter an email address'); return; }
    setLoading(btn, true);
    try {
      const data = await api('GET', `/users?email=${encodeURIComponent(email)}`);
      lookedUpUserId = data.id;
      showResponse('ul-response', data, false);
      document.getElementById('ul-actions').style.display = 'block';
    } catch (e) {
      showResponse('ul-response', e.body || e, true);
      document.getElementById('ul-actions').style.display = 'none';
    } finally {
      setLoading(btn, false);
    }
  }

  async function changeRole() {
    const btn = document.querySelector('[onclick="changeRole()"]');
    const roleId = document.getElementById('cr-role-select').value;
    const reason = document.getElementById('cr-reason').value.trim();
    if (!roleId) { alert('Select a role'); return; }
    if (!lookedUpUserId) { alert('Fetch a user first'); return; }
    setLoading(btn, true);
    try {
      await api('PUT', `/users/${lookedUpUserId}/role`, { newRoleId: roleId, reason: reason || null });
      showResponse('cr-response', 'Role changed successfully.', false);
    } catch (e) {
      showResponse('cr-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  async function resetPassword() {
    const btn = document.querySelector('[onclick="resetPassword()"]');
    const newPassword = document.getElementById('rp-password').value;
    if (!newPassword) { alert('Enter a password'); return; }
    if (!lookedUpUserId) { alert('Fetch a user first'); return; }
    setLoading(btn, true);
    try {
      await api('POST', `/users/${lookedUpUserId}/reset-password`, { newPassword });
      showResponse('rp-response', 'Password reset. User must change on next login.', false);
      document.getElementById('rp-password').value = '';
    } catch (e) {
      showResponse('rp-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  async function deactivateUser() {
    const btn = document.querySelector('[onclick="deactivateUser()"]');
    if (!lookedUpUserId) { alert('Fetch a user first'); return; }
    if (!confirm('Deactivate this user?')) return;
    setLoading(btn, true);
    try {
      await api('DELETE', `/users/${lookedUpUserId}`);
      showResponse('status-response', 'User deactivated.', false);
    } catch (e) {
      showResponse('status-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  async function reactivateUser() {
    const btn = document.querySelector('[onclick="reactivateUser()"]');
    if (!lookedUpUserId) { alert('Fetch a user first'); return; }
    setLoading(btn, true);
    try {
      await api('PUT', `/users/${lookedUpUserId}/reactivate`);
      showResponse('status-response', 'User reactivated.', false);
    } catch (e) {
      showResponse('status-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  async function getAuditLog() {
    const btn = document.querySelector('[onclick="getAuditLog()"]');
    if (!lookedUpUserId) { alert('Fetch a user first'); return; }
    setLoading(btn, true);
    try {
      const data = await api('GET', `/users/${lookedUpUserId}/audit-log`);
      showResponse('al-response', data, false);
    } catch (e) {
      showResponse('al-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  // ── My Account ──
  async function updateProfile() {
    const btn = document.querySelector('[onclick="updateProfile()"]');
    const name = document.getElementById('up-name').value.trim();
    if (!name) { alert('Enter a name'); return; }
    setLoading(btn, true);
    try {
      await api('PUT', '/users/me', { name });
      showResponse('up-response', 'Profile updated.', false);
    } catch (e) {
      showResponse('up-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  async function changePassword() {
    const btn = document.querySelector('[onclick="changePassword()"]');
    const currentPassword = document.getElementById('cp-current').value;
    const newPassword = document.getElementById('cp-new').value;
    if (!currentPassword || !newPassword) { alert('Both fields required'); return; }
    setLoading(btn, true);
    try {
      await api('PUT', '/users/me/password', { currentPassword, newPassword });
      showResponse('cp-response', 'Password changed.', false);
      document.getElementById('cp-current').value = '';
      document.getElementById('cp-new').value = '';
      document.getElementById('info-mustchange').textContent = 'No';
    } catch (e) {
      showResponse('cp-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  // ── Create User ──
  async function createUser() {
    const btn = document.querySelector('[onclick="createUser()"]');
    const sel = document.getElementById('cu-role');
    const opt = sel.selectedOptions[0];
    const cityScoped = opt && opt.dataset.cityScoped === 'true';
    const body = {
      name:     document.getElementById('cu-name').value.trim(),
      email:    document.getElementById('cu-email').value.trim(),
      password: document.getElementById('cu-password').value,
      role:     opt ? opt.dataset.name : '',
      // Auth API binds request bodies in snake_case (jackson SNAKE_CASE).
      city_id:  cityScoped ? document.getElementById('cu-city').value : null,
    };
    setLoading(btn, true);
    try {
      const data = await api('POST', '/users', body);
      showResponse('cu-response', data, false);
    } catch (e) {
      showResponse('cu-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  // ── Roles ──
  async function loadRoles() {
    const container = document.getElementById('roles-list-container');
    container.innerHTML = '<div class="empty-state"><span class="spinner"></span></div>';
    document.getElementById('role-deactivate-response').className = 'response';
    try {
      const roles = await api('GET', '/roles');
      rolesCache = roles;
      populateRoleSelect('cu-role', true);
      populateRoleSelect('cr-role-select', false);
      container.innerHTML = `
        <table class="data-table">
          <thead><tr>
            <th>Name</th><th>Display Name</th><th>City Scoped</th><th>Type</th><th>Status</th><th></th>
          </tr></thead>
          <tbody>
            ${roles.map(r => `
              <tr>
                <td><code style="font-size:.8rem">${esc(r.name)}</code></td>
                <td>${esc(r.display_name ?? r.displayName ?? r.name)}</td>
                <td>${(r.city_scoped ?? r.cityScoped) ? '<span class="badge badge-blue">Yes</span>' : '—'}</td>
                <td>${r.builtin ? '<span class="badge badge-gray">Built-in</span>' : '<span class="badge badge-green">Custom</span>'}</td>
                <td><span class="badge ${r.active ? 'badge-green' : 'badge-red'}">${r.active ? 'Active' : 'Inactive'}</span></td>
                <td>${(!r.builtin && r.active && currentUser.role === 'ADMIN')
                  ? `<button class="btn btn-sm btn-danger" onclick="deactivateRole('${r.id}', this)">Deactivate</button>`
                  : '—'
                }</td>
              </tr>`).join('')}
          </tbody>
        </table>`;
    } catch (e) {
      container.innerHTML = `<div class="empty-state" style="color:#e63946">${fmtError(e)}</div>`;
    }
  }

  async function deactivateRole(roleId, btn) {
    if (!confirm('Deactivate this role? Users currently assigned it will be affected.')) return;
    setLoading(btn, true);
    try {
      await api('DELETE', `/roles/${roleId}`);
      showResponse('role-deactivate-response', 'Role deactivated.', false);
      loadRoles();
    } catch (e) {
      showResponse('role-deactivate-response', e.body || e, true);
      setLoading(btn, false);
    }
  }

  function togglePerm(el) { el.classList.toggle('selected'); }

  function toSnakeCase(str) {
    return str
      .replace(/([A-Z])/g, '_$1')
      .replace(/[\s\-]+/g, '_')
      .replace(/[^a-z0-9_]/gi, '')
      .replace(/_+/g, '_')
      .replace(/^_|_$/g, '')
      .toLowerCase();
  }

  function validateRoleName(input) {
    const hint = document.getElementById('nr-name-hint');
    const val = input.value;
    if (!val) { hint.style.display = 'none'; return; }
    const isValid = /^[a-z][a-z0-9_]*$/.test(val);
    if (isValid) {
      hint.style.display = 'none';
    } else {
      const suggested = toSnakeCase(val);
      hint.style.display = 'block';
      hint.innerHTML = `<span style="color:#ef4444">Must be snake_case (lowercase, underscores only).</span>`
        + (suggested ? ` <a href="#" style="color:#2563eb;text-decoration:none" onclick="event.preventDefault();document.getElementById('nr-name').value='${suggested}';validateRoleName(document.getElementById('nr-name'))">Use <strong>${suggested}</strong></a>` : '');
    }
  }

  async function createRole() {
    const btn = document.querySelector('[onclick="createRole()"]');
    const name = document.getElementById('nr-name').value.trim();
    const displayName = document.getElementById('nr-display').value.trim();
    const cityScoped = document.getElementById('nr-cityscoped').value === 'true';
    const permissions = [...document.querySelectorAll('#nr-perm-chips .perm-chip.selected')].map(el => el.dataset.perm);
    if (!/^[a-z][a-z0-9_]*$/.test(name)) { validateRoleName(document.getElementById('nr-name')); alert('Role name must be snake_case (e.g. warehouse_manager)'); return; }
    if (!name || !displayName || !permissions.length) { alert('Name, display name, and at least one permission required'); return; }
    setLoading(btn, true);
    try {
      // Auth API binds request bodies in snake_case (jackson SNAKE_CASE).
      const data = await api('POST', '/roles', { name, display_name: displayName, city_scoped: cityScoped, permissions });
      showResponse('nr-response', data, false);
      document.getElementById('nr-name').value = '';
      document.getElementById('nr-display').value = '';
      document.getElementById('nr-name-hint').style.display = 'none';
      document.querySelectorAll('#nr-perm-chips .perm-chip.selected').forEach(el => el.classList.remove('selected'));
      loadRoles();
    } catch (e) {
      showResponse('nr-response', e.body || e, true);
    } finally {
      setLoading(btn, false);
    }
  }

  // ── Permission Check ──
  async function checkPermission() {
    const btn = document.querySelector('[onclick="checkPermission()"]');
    const email  = document.getElementById('pc-email').value.trim();
    const action = document.getElementById('pc-action').value;
    const city   = document.getElementById('pc-city').value.trim();
    if (!email) { alert('Enter an email address'); return; }
    setLoading(btn, true);
    try {
      let url = `/permissions/check?email=${encodeURIComponent(email)}&action=${action}`;
      if (city) url += `&cityId=${city}`;
      const data = await api('GET', url);
      document.getElementById('pc-result').innerHTML =
        `<div class="perm-result ${data.allowed ? 'allowed' : 'denied'}">
          ${data.allowed ? '✅' : '❌'} ${data.allowed ? 'Allowed' : 'Denied'} — ${esc(data.reason)}
        </div>`;
    } catch (e) {
      document.getElementById('pc-result').innerHTML =
        `<div class="perm-result denied">❌ ${esc(fmtError(e))}</div>`;
    } finally {
      setLoading(btn, false);
    }
  }
