// init.js — global listeners. Loaded last so all handlers are defined.

  // ── Keyboard ──
  document.addEventListener('keydown', e => {
    if (e.key === 'Enter') {
      if (document.getElementById('login-fields').classList.contains('visible')) login();
      else if (document.getElementById('register-fields').classList.contains('visible')) register();
    }
  });
