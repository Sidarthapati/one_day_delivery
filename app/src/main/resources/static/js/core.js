// core.js — shared state, generic API/UI helpers, tab + dark-mode switching.
// Loaded first; every other module depends on these globals.

  // ── State ──
  let token = null;
  let currentUser = null;
  let currentUserId = null;
  let rolesCache = [];   // [{id, name, cityScoped}, ...]
  let lookedUpUserId = null;

  const CITY_SCOPED_ROLES = new Set([
    'STATION_MANAGER','SUPERVISOR','HUB_OPERATOR',
    'DELIVERY_ASSOCIATE','VAN_DRIVER','CRON_DRIVER','CALL_CENTER_AGENT'
  ]);

  // ── API helper ──
  function api(method, path, body) {
    const opts = { method, headers: { 'Content-Type': 'application/json' } };
    if (token) opts.headers['Authorization'] = 'Bearer ' + token;
    if (body)  opts.body = JSON.stringify(body);
    return fetch(path, opts).then(async r => {
      const text = await r.text();
      let data;
      try { data = JSON.parse(text); } catch { data = text; }
      if (!r.ok) throw { status: r.status, body: data };
      return data;
    });
  }

  function fmtError(e) {
    const b = e && e.body;
    if (!b) return 'Something went wrong.';
    const title  = b.title  || '';
    const detail = b.detail || '';
    if (title && detail) return `${title} — ${detail}`;
    return detail || title || 'Something went wrong.';
  }

  function showResponse(id, data, isError) {
    const el = document.getElementById(id);
    el.className = 'response visible' + (isError ? ' error' : '');
    if (isError) {
      el.textContent = fmtError(data && data.body !== undefined ? data : { body: data });
    } else {
      el.textContent = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
    }
  }

  function setLoading(btn, loading) {
    if (loading) {
      btn.dataset.orig = btn.innerHTML;
      btn.innerHTML = '<span class="spinner"></span>';
      btn.disabled = true;
    } else {
      btn.innerHTML = btn.dataset.orig;
      btn.disabled = false;
    }
  }

  // ── Auth toggle ──
  function switchAuth(mode) {
    document.querySelectorAll('.auth-toggle button').forEach((b, i) => {
      b.classList.toggle('active', (mode === 'login') === (i === 0));
    });
    document.getElementById('login-fields').classList.toggle('visible', mode === 'login');
    document.getElementById('register-fields').classList.toggle('visible', mode === 'register');
    document.getElementById('register-hint').style.display = mode === 'register' ? '' : 'none';
    document.getElementById('login-error').className = 'response error';
  }

  // ── Tab switch ──
  function switchTab(name) {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
    document.querySelector(`[onclick="switchTab('${name}')"]`).classList.add('active');
    document.getElementById('tab-' + name).classList.add('active');
  }

  // ── Helpers ──
  function esc(s) {
    return String(s ?? '').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  // ── Dark mode ──
  function toggleDark() {
    const dark = document.body.classList.toggle('dark');
    document.getElementById('dark-toggle').textContent = dark ? '☀️' : '🌙';
  }

  // ── Order/format utils (shared by orders + admin) ──
  function uuid() {
    return (crypto.randomUUID ? crypto.randomUUID()
      : 'id-' + Date.now() + '-' + Math.random().toString(16).slice(2));
  }
  function inr(p) {
    return p == null ? '—' : '₹' + (p / 100).toLocaleString('en-IN', { minimumFractionDigits: 2 });
  }
  function val(id) { return document.getElementById(id).value.trim(); }

  function fmtTs(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    return isNaN(d) ? '—' : d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
  }

  // Identity comes from the authenticated principal server-side (JWT in prod; the demo
  // principal under !prod) — no X-User-Id header. We still send the bearer token.
  function orderApi(method, path, body, extraHeaders) {
    const headers = Object.assign({ 'Content-Type': 'application/json' }, extraHeaders || {});
    if (token) headers['Authorization'] = 'Bearer ' + token;
    headers['Idempotency-Key'] = uuid();
    const opts = { method, headers };
    if (body) opts.body = JSON.stringify(body);
    return fetch(path, opts).then(async r => {
      const text = await r.text();
      let data; try { data = JSON.parse(text); } catch { data = text; }
      return { status: r.status, data };
    });
  }
