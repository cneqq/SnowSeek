/* Optional visual layer. No field values, event interception, network or storage access. */
(function () {
  'use strict';
  if (window.__snowseekSignInTheme) return;
  var enabled = false, sheet = null, marked = new Set(), timer;
  var protectedArea = 'iframe,[role="dialog"],[aria-modal="true"],[class*="captcha" i],[id*="captcha" i],[class*="challenge" i],[id*="challenge" i]';
  function mark(node, name) { node.setAttribute(name, ''); marked.add(node); }
  function unmark() {
    marked.forEach(function (node) {
      ['data-snowseek-login','data-snowseek-field','data-snowseek-input'].forEach(function (name) { node.removeAttribute(name); });
    });
    marked.clear();
  }
  function clear() {
    unmark();
    if (sheet) { sheet.remove(); sheet = null; }
  }
  function refresh() {
    if (!enabled || window !== window.top || window.location.origin !== 'https://chat.deepseek.com' ||
        !/^\/sign_in\/?$/.test(window.location.pathname) || !document.body || !document.head) { clear(); return; }
    var inputs = Array.from(document.querySelectorAll('input[type="password"],input[type="email"],input[type="tel"]'))
      .filter(function (input) { return !input.closest(protectedArea) && (input.closest('form') || input.closest('.ds-input')); });
    // Unknown markup keeps its original interface; do not guess generated class names.
    if (!inputs.length) { clear(); return; }
    unmark();
    mark(document.documentElement, 'data-snowseek-login');
    inputs.forEach(function (input) {
      mark(input, 'data-snowseek-field');
      var wrapper = input.closest('.ds-input');
      if (wrapper && !wrapper.closest(protectedArea)) mark(wrapper, 'data-snowseek-input');
    });
    if (sheet) return;
    sheet = document.createElement('style');
    sheet.textContent = `
      html[data-snowseek-login], html[data-snowseek-login] body {
        background-color: #10131b !important; color: #e5e9f5; color-scheme: dark;
        --dsw-alias-bg-base: #10131b;
        --dsw-alias-brand-primary: #4163eb;
        --dsw-alias-brand-text: #a9b9ff;
        --dsw-alias-label-primary: #e5e9f5;
        --dsw-alias-label-secondary: #a8b0c6;
        --dsw-alias-label-tertiary: #a8b0c6;
        --dsw-alias-label-caption: #a8b0c6;
        --dsw-alias-label-primary-inverted: #ffffff;
        --dsw-alias-border-l3: #424b63;
        --dsw-specific-login-input: #242a39;
        --ds-auth-primary-button-radius: 16px;
      }
      html[data-snowseek-login] [data-snowseek-input] {
        background-color: #242a39 !important; border-color: #424b63 !important; border-radius: 16px !important;
      }
      html[data-snowseek-login] [data-snowseek-field] {
        background-color: #242a39 !important; color: #e5e9f5 !important;
        caret-color: #a9b9ff; border-radius: 16px; font-size: 16px;
      }
      html[data-snowseek-login] [data-snowseek-field]::placeholder { color: #a8b0c6; }
      html[data-snowseek-login] [data-snowseek-field]:focus-visible { outline: 2px solid #a9b9ff; outline-offset: 2px; }
    `;
    document.head.appendChild(sheet);
  }
  window.__snowseekSignInTheme = function (apply) { enabled = apply === true; refresh(); };
  // The official SPA may mount late or replace its form. Observe structure only, never values.
  new MutationObserver(function () {
    clearTimeout(timer); timer = setTimeout(refresh, 100);
  }).observe(document.documentElement, {childList: true, subtree: true});
})();
