// auth.js — register + login for index.html. Adapted from the old demo's auth.js: same
// api()/fmtError() calling convention, trimmed down to just the two actions this demo needs
// (no onboarding, no API keys, no admin dashboard).

document.addEventListener('DOMContentLoaded', () => {
  // Already logged in from an earlier visit this session — skip straight to booking.
  if (getToken()) {
    window.location.href = 'booking.html';
    return;
  }

  document.getElementById('register-btn').addEventListener('click', register);
  document.getElementById('login-btn').addEventListener('click', login);
});

async function register() {
  const btn = document.getElementById('register-btn');
  const name = val('reg-name');
  const phone = val('reg-phone');
  const email = val('reg-email');
  const password = val('reg-password');

  if (!name || !email || !password) {
    showResponse('register-response', 'Name, email, and password are required.', true);
    return;
  }
  if (!/^\+91[0-9]{10}$/.test(phone)) {
    showResponse('register-response', 'Phone must be in +91XXXXXXXXXX format.', true);
    return;
  }

  setLoading(btn, true);
  try {
    // Self-registration always creates a C2C_CUSTOMER — no approval queue, token comes back
    // immediately (confirmed by reading auth/AuthController.register + RegisterRequest).
    const data = await api('POST', '/auth/register', { name, phone, email, password });
    onLoggedIn(data, email);
  } catch (e) {
    showResponse('register-response', fmtError(e), true);
  } finally {
    setLoading(btn, false);
  }
}

async function login() {
  const btn = document.getElementById('login-btn');
  const email = val('login-email');
  const password = val('login-password');
  if (!email || !password) {
    showResponse('login-response', 'Email and password are required.', true);
    return;
  }
  setLoading(btn, true);
  try {
    const data = await api('POST', '/auth/login', { email, password });
    onLoggedIn(data, email);
  } catch (e) {
    showResponse('login-response', e.status === 401 ? 'Invalid email or password.' : fmtError(e), true);
  } finally {
    setLoading(btn, false);
  }
}

function onLoggedIn(data, email) {
  // Backend Jackson config is SNAKE_CASE; accept both forms defensively.
  const role = data.role;
  setSession(data.token, { email, role });
  window.location.href = 'booking.html';
}
