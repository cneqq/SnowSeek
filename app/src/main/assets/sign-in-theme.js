/* Official form presentation only. No field values, event interception, network or storage access. */
(function () {
  'use strict';
  if (window.__snowseekSignInTheme) return;
  var enabled = false, sheet = null, marked = new Set(), timer;
  var palette = {}, intro = null, introAnchor = null, presented = new Map();
  var protectedArea = 'iframe,[role="dialog"],[aria-modal="true"],[role="alert"],[role="status"],[class*="captcha" i],[id*="captcha" i],[class*="challenge" i],[id*="challenge" i],[class*="turnstile" i],[id*="turnstile" i]';
  var attributes = ['data-snowseek-login','data-snowseek-field','data-snowseek-input','data-snowseek-hidden','data-snowseek-form','data-snowseek-control','data-snowseek-link','data-snowseek-google'];
  function mark(node, name) { node.setAttribute(name, ''); marked.add(node); }
  function unmark() {
    marked.forEach(function (node) {
      attributes.forEach(function (name) { node.removeAttribute(name); });
    });
    marked.clear();
  }
  function clear() {
    unmark();
    if (intro) { intro.remove(); intro = null; }
    introAnchor = null;
    Array.from(presented.keys()).forEach(restorePresentation);
    if (sheet) { sheet.remove(); sheet = null; }
  }
  function restorePresentation(node) {
    var changes = presented.get(node);
    if (!changes) return;
    changes.forEach(function (change, name) {
      if (node.getAttribute(name) !== change.applied) return;
      if (change.original === null) node.removeAttribute(name);
      else node.setAttribute(name, change.original);
    });
    presented.delete(node);
  }
  function presentAttribute(node, name, text) {
    var changes = presented.get(node);
    if (!changes) { changes = new Map(); presented.set(node, changes); }
    var previous = changes.get(name), current = node.getAttribute(name);
    var original = previous && current === previous.applied ? previous.original : current;
    changes.set(name, {original: original, applied: text});
    if (current !== text) node.setAttribute(name, text);
  }
  function hideOptional(node) {
    // Never hide a verification surface, its ancestors, or required form controls.
    if (!node || node.closest(protectedArea) || node.querySelector(protectedArea) ||
        node.matches('form,input,select,textarea,[role="checkbox"]') ||
        node.querySelector('form,input,select,textarea,[role="checkbox"]')) return;
    mark(node, 'data-snowseek-hidden');
  }
  function markControl(node, name) {
    if (node && !node.closest(protectedArea) && !node.querySelector(protectedArea)) mark(node, name);
  }
  function color(key, fallback) {
    return typeof palette[key] === 'string' && /^#[0-9a-f]{6}$/i.test(palette[key]) ? palette[key] : fallback;
  }
  function refresh() {
    if (!enabled || window !== window.top || window.location.origin !== 'https://chat.deepseek.com' ||
        !/^\/sign_in\/?$/.test(window.location.pathname) || !document.body || !document.head) { clear(); return; }
    var inputs = Array.from(document.querySelectorAll('input[type="password"],input[type="email"],input[type="tel"],.ds-sign-in-form-wrapper input[type="text"],.ds-sign-in-form-wrapper input:not([type])'))
      .filter(function (input) { return !input.closest(protectedArea) && (input.closest('form') || input.closest('.ds-input')); });
    // Unknown markup keeps its original interface; do not guess its structure.
    if (!inputs.length) { clear(); return; }
    Array.from(presented.keys()).filter(function (node) { return inputs.indexOf(node) < 0; }).forEach(restorePresentation);
    unmark();
    mark(document.documentElement, 'data-snowseek-login');
    inputs.forEach(function (input) {
      mark(input, 'data-snowseek-field');
      var wrapper = input.closest('.ds-input');
      if (wrapper && !wrapper.closest(protectedArea) && !wrapper.querySelector(protectedArea)) mark(wrapper, 'data-snowseek-input');
    });
    var form = document.querySelectorAll('.ds-sign-in-form-wrapper')[0];
    if (form && inputs.every(function (input) { return form.contains(input); }) && !form.closest(protectedArea)) {
      mark(form, 'data-snowseek-form');
      var russian = palette.language !== 'en';
      var icon = form.querySelector('.ds-sign-in-form__icon');
      if (icon && !icon.closest(protectedArea) && !icon.querySelector(protectedArea)) {
        if (!intro || !intro.isConnected || introAnchor !== icon) {
          if (intro) intro.remove();
          intro = document.createElement('p');
          introAnchor = icon;
          intro.setAttribute('data-snowseek-intro', '');
          icon.insertAdjacentElement('afterend', intro);
        }
        var explanation = russian ? 'Войдите в аккаунт для использования DeepSeek' : 'Sign in to use DeepSeek';
        if (intro.textContent !== explanation) intro.textContent = explanation;
      }
      inputs.forEach(function (input) {
        var type = (input.getAttribute('type') || 'text').toLowerCase();
        var placeholder = input.getAttribute('placeholder') || '';
        if (type === 'email' || (type === 'text' && /email|e-mail|почт|邮箱|邮件/i.test(placeholder))) {
          var emailLabel = russian ? 'Адрес электронной почты' : 'Email address';
          presentAttribute(input, 'placeholder', emailLabel);
          presentAttribute(input, 'aria-label', emailLabel);
        } else restorePresentation(input);
      });
      form.querySelectorAll('.ds-sign-up-form__agreement-text').forEach(hideOptional);
      // Exact official banner fingerprint. Presentation only: never click consent or write preferences.
      document.querySelectorAll('.cookie_banner-wrap').forEach(function (banner) {
        if (banner.querySelector('.cookie_banner-title') &&
            banner.querySelector('.cookie_banner-accept-essential-button') &&
            banner.querySelector('.cookie_banner-accept-all-button')) hideOptional(banner);
      });
      // The official primary component's inspected theme uses this exact height token.
      // Semantic submit controls are also unambiguous; unrelated buttons stay untouched.
      form.querySelectorAll('[style*="--ds-auth-primary-button-height"],button[type="submit"]').forEach(function (node) {
        markControl(node, 'data-snowseek-control');
      });
      form.querySelectorAll('.ds-sign-in-form__form-footer').forEach(function (footer) {
        footer.querySelectorAll('button,a,[role="button"]').forEach(function (node) { markControl(node, 'data-snowseek-link'); });
      });
      document.querySelectorAll('.ds-auth-footer,.ds-sign-in-form__main-wechat').forEach(hideOptional);
      // Fingerprint from the inspected official bundle: auth page > banner + content.
      // A future unknown bundle falls back to the official layout.
      var page = form.closest('.c994dda2');
      if (page && page.querySelector('._99ad066') && page.querySelector('._5178cc4')) {
        Array.from(page.children).filter(function (node) { return node.matches('._47c279e'); }).forEach(hideOptional);
        hideOptional(page.querySelector('._5178cc4'));
      }
      var social = Array.from(form.querySelectorAll('.ds-sign-in-form__social-link'));
      var google = social.filter(function (node) { return /google/i.test(node.textContent || ''); });
      if (google.length === 1) {
        markControl(google[0], 'data-snowseek-control');
        markControl(google[0], 'data-snowseek-google');
        social.filter(function (node) { return node !== google[0]; }).forEach(hideOptional);
        form.querySelectorAll('.ds-sign-in-form__social-link-separator,.ds-sign-in-form__social-links-measurer').forEach(hideOptional);
      }
    } else {
      if (intro) { intro.remove(); intro = null; }
      introAnchor = null;
      Array.from(presented.keys()).forEach(restorePresentation);
    }
    var scale = typeof palette.scale === 'number' && Number.isFinite(palette.scale) ? Math.min(1.5, Math.max(0.7, palette.scale)) : 1;
    var bg = color('background', '#10131b'), fg = color('foreground', '#e5e9f5');
    var surface = color('surface', '#242a39'), secondary = color('secondary', '#a8b0c6');
    var primary = color('primary', '#a9b9ff'), onPrimary = color('onPrimary', '#ffffff');
    var outline = color('outline', '#424b63'), scheme = palette.scheme === 'light' ? 'light' : 'dark';
    var css = `
      html[data-snowseek-login], html[data-snowseek-login] body {
        background-color: ${bg} !important; color: ${fg}; color-scheme: ${scheme};
        --dsw-alias-bg-base: ${bg};
        --dsw-alias-brand-primary: ${primary}; --dsw-alias-brand-text: ${primary};
        --dsw-alias-label-primary: ${fg}; --dsw-alias-label-secondary: ${secondary};
        --dsw-alias-label-tertiary: ${secondary}; --dsw-alias-label-caption: ${secondary};
        --dsw-alias-label-primary-inverted: ${onPrimary};
        --dsw-alias-border-l3: ${outline}; --dsw-specific-login-input: ${surface};
      }
      html[data-snowseek-login] [data-snowseek-hidden] { display: none !important; }
      html[data-snowseek-login] [data-snowseek-form] { max-width: 420px; width: 100%; margin-inline: auto; box-sizing: border-box; }
      html[data-snowseek-login] [data-snowseek-intro] {
        margin: 12px 0 28px; padding-inline: 8px; text-align: center;
        color: ${secondary}; font-size: calc(15px * ${scale}); line-height: 1.5;
      }
      html[data-snowseek-login] [data-snowseek-input] {
        background-color: ${surface} !important; border-color: ${outline} !important; border-radius: calc(16px * ${scale}) !important;
        height: calc(52px * ${scale}) !important; box-sizing: border-box;
      }
      html[data-snowseek-login] [data-snowseek-field] {
        background-color: ${surface} !important; color: ${fg} !important;
        caret-color: ${primary}; border-radius: calc(16px * ${scale}); font-size: calc(16px * ${scale}) !important;
      }
      html[data-snowseek-login] [data-snowseek-control] {
        --ds-auth-primary-button-height: calc(44px * ${scale});
        --ds-auth-primary-button-radius: calc(16px * ${scale});
        height: calc(44px * ${scale}) !important; min-height: calc(44px * ${scale});
        font-size: calc(16px * ${scale}) !important; border-radius: calc(16px * ${scale}) !important;
        box-sizing: border-box; max-width: 100%;
      }
      html[data-snowseek-login] [data-snowseek-link] { font-size: calc(14px * ${scale}) !important; }
      html[data-snowseek-login] [data-snowseek-google] {
        display: flex; align-items: center; justify-content: center; width: 100%;
        padding-inline: 16px; border: 1px solid ${outline} !important;
        background-color: ${surface} !important; color: ${fg} !important;
        text-decoration: none !important; font-weight: 600; cursor: pointer;
      }
      html[data-snowseek-login] [data-snowseek-google]:hover { border-color: ${primary} !important; }
      html[data-snowseek-login] [data-snowseek-google]:active { background-color: ${outline} !important; }
      html[data-snowseek-login] [data-snowseek-google]:focus-visible { outline: 2px solid ${primary}; outline-offset: 3px; }
      html[data-snowseek-login] [data-snowseek-google]:disabled,
      html[data-snowseek-login] [data-snowseek-google][aria-disabled="true"] { cursor: not-allowed; border-style: dashed !important; }
      html[data-snowseek-login] [data-snowseek-field]::placeholder { color: ${secondary}; }
      html[data-snowseek-login] [data-snowseek-field]:focus-visible { outline: 2px solid ${primary}; outline-offset: 2px; }
    `;
    if (!sheet) { sheet = document.createElement('style'); document.head.appendChild(sheet); }
    if (sheet.textContent !== css) sheet.textContent = css;
  }
  // Boolean API remains supported for existing callers; native UI supplies a validated palette.
  window.__snowseekSignInTheme = function (options) {
    enabled = options === true || !!(options && typeof options === 'object' && options.enabled === true);
    palette = options && typeof options === 'object' ? options : {};
    refresh();
  };
  // The official SPA may mount late or replace its form. Observe structure only, never values.
  new MutationObserver(function () {
    clearTimeout(timer); timer = setTimeout(refresh, 100);
  }).observe(document.documentElement, {childList: true, subtree: true});
})();
