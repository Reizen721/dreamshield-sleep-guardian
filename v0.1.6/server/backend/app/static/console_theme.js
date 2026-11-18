// Resolve appearance before the first body paint; this module has no network or
// device side effects. Browser tabs and the native Windows host use the same UI.
(function (root) {
  'use strict';
  const KEY = 'dreamshield.console.theme';
  const normalize = value => value === 'dark' || value === 'light' ? value : null;
  const resolve = (preference, systemDark) => normalize(preference) || (systemDark ? 'dark' : 'light');
  if (typeof module !== 'undefined' && module.exports) module.exports = {KEY, normalize, resolve};
  const doc = root.document;
  if (!doc) return;
  const media = typeof root.matchMedia === 'function' ? root.matchMedia('(prefers-color-scheme: dark)') : null;
  let preference = null;
  try { preference = normalize(root.localStorage.getItem(KEY)); } catch (_) {}
  let theme;
  function renderButton() {
    const button = doc.getElementById('consoleThemeToggle');
    if (!button) return;
    button.setAttribute('aria-checked', String(theme === 'dark'));
    button.title = theme === 'dark' ? '切换到浅色模式' : '切换到暗色模式';
  }
  function apply() {
    theme = resolve(preference, Boolean(media?.matches));
    doc.documentElement.setAttribute('data-theme', theme);
    doc.documentElement.style.colorScheme = theme;
    doc.documentElement.style.backgroundColor = theme === 'dark' ? '#0a121b' : '#f4f7f9';
    renderButton();
    // The desktop host accepts only this bounded appearance message.
    try { root.chrome?.webview?.postMessage({type: 'theme', value: theme}); } catch (_) {}
    return theme;
  }
  function setTheme(value) {
    const next = normalize(value);
    if (!next) return theme;
    preference = next;
    try { root.localStorage.setItem(KEY, next); } catch (_) {}
    return apply();
  }
  function useSystem() {
    preference = null;
    try { root.localStorage.removeItem(KEY); } catch (_) {}
    return apply();
  }
  let bound = false;
  function bind() {
    if (bound) return;
    bound = true;
    const button = doc.getElementById('consoleThemeToggle');
    if (button) button.addEventListener('click', () => setTheme(theme === 'dark' ? 'light' : 'dark'));
    renderButton();
    // Re-send once the host and document have both completed initial setup.
    try { root.chrome?.webview?.postMessage({type: 'theme', value: theme}); } catch (_) {}
  }
  root.DreamShieldTheme = Object.freeze({getTheme: () => theme, getPreference: () => preference, setTheme, useSystem});
  apply();
  if (media?.addEventListener) media.addEventListener('change', () => { if (!preference) apply(); });
  else if (media?.addListener) media.addListener(() => { if (!preference) apply(); });
  root.addEventListener('storage', event => {
    if (event.key !== KEY && event.key !== null) return;
    preference = normalize(event.newValue);
    apply();
  });
  if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', bind, {once: true});
  else bind();
})(globalThis);
