// core.js — shared session state + generic API/UI helpers. Loaded on every page before the
// page-specific script. Adapted from the old 1DD demo's core.js: same api()/fmtError()/esc()/fmtTs()
// helpers, but token lives in sessionStorage (not a plain JS variable) since this demo is several
// separate HTML pages, not one single-page tabbed app — a page navigation would otherwise lose it.

function getToken() { return sessionStorage.getItem('m9demo_token'); }
function getUser() {
  const raw = sessionStorage.getItem('m9demo_user');
  return raw ? JSON.parse(raw) : null;
}
function setSession(token, user) {
  sessionStorage.setItem('m9demo_token', token);
  sessionStorage.setItem('m9demo_user', JSON.stringify(user));
}
function clearSession() {
  sessionStorage.removeItem('m9demo_token');
  sessionStorage.removeItem('m9demo_user');
}

/** Redirect to login if there's no session; call at the top of every page except index.html. */
function requireSession() {
  if (!getToken()) {
    window.location.href = 'index.html';
    return false;
  }
  return true;
}

/** Paints the shared nav bar's user badge + wires the logout button. Call after requireSession(). */
function paintNav() {
  const user = getUser();
  const badge = document.getElementById('user-badge');
  if (badge && user) {
    badge.textContent = `${user.email} · ${user.role}`;
  }
  const logoutBtn = document.getElementById('logout-btn');
  if (logoutBtn) {
    logoutBtn.addEventListener('click', () => {
      clearSession();
      window.location.href = 'index.html';
    });
  }
  // Highlight the current page's nav link.
  const here = window.location.pathname.split('/').pop();
  document.querySelectorAll('nav .links a').forEach(a => {
    a.classList.toggle('active', a.getAttribute('href') === here);
  });
}

/** Shared fetch helper: attaches the bearer token, JSON-encodes the body, parses JSON responses. */
function api(method, path, body, extraHeaders) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, extraHeaders || {});
  const token = getToken();
  if (token) headers['Authorization'] = 'Bearer ' + token;
  const opts = { method, headers };
  if (body) opts.body = JSON.stringify(body);
  return fetch(path, opts).then(async r => {
    const text = await r.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (!r.ok) throw { status: r.status, body: data };
    return data;
  });
}

function fmtError(e) {
  const b = e && e.body;
  if (!b) return (e && e.message) || 'Something went wrong.';
  if (typeof b === 'string') return b;
  const title = b.title || b.error || '';
  const detail = b.detail || b.message || '';
  if (title && detail) return `${title} — ${detail}`;
  return detail || title || JSON.stringify(b);
}

function showResponse(id, data, isError) {
  const el = document.getElementById(id);
  if (!el) return;
  el.className = 'response visible' + (isError ? ' error' : '');
  el.textContent = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
}

function setLoading(btn, loading) {
  if (!btn) return;
  if (loading) {
    btn.dataset.orig = btn.innerHTML;
    btn.innerHTML = '<span class="spinner"></span>';
    btn.disabled = true;
  } else {
    btn.innerHTML = btn.dataset.orig ?? btn.innerHTML;
    btn.disabled = false;
  }
}

function esc(s) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function uuid() {
  return (crypto.randomUUID ? crypto.randomUUID()
    : 'id-' + Date.now() + '-' + Math.random().toString(16).slice(2));
}

function inr(paise) {
  return paise == null ? '—' : '₹' + (paise / 100).toLocaleString('en-IN', { minimumFractionDigits: 2 });
}

function fmtTs(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d) ? '—' : d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

function val(id) {
  const el = document.getElementById(id);
  return el ? el.value.trim() : '';
}

/** ISO yyyy-MM-dd for a Date, in local time (matches @DateTimeFormat(iso=DATE) on the backend). */
function isoDate(d) {
  const pad = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
