// Presentation only. Real document navigation, focus, forms and background tasks
// remain owned by the browser and the existing controllers.
(function (root) {
  'use strict';
  const KEY = 'dreamshield.console.navigation';
  const ROUTES = Object.freeze(['/', '/history', '/connections', '/settings', '/device-setup', '/thermal-setup']);
  function route(value, base) {
    try {
      const url = new URL(value, base), origin = new URL(base).origin;
      if (url.origin !== origin) return null;
      const path = url.pathname.replace(/\/+$/, '') || '/';
      return ROUTES.includes(path) ? path : path === '/mobile-setup' ? '/connections' : null;
    } catch (_) { return null; }
  }
  function direction(from, to) {
    return ROUTES.indexOf(from) > ROUTES.indexOf(to) ? 'backward' : 'forward';
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = {KEY, ROUTES, route, direction};
  const doc = root.document, location = root.location || root.window?.location;
  if (!doc || !location?.href) return;
  const current = route(location.href, location.href);
  if (!current) return;
  const html = doc.documentElement;
  let cleanupTimer;
  function enter(from) {
    root.clearTimeout(cleanupTimer);
    html.removeAttribute('data-console-motion');
    try { root.sessionStorage.setItem(KEY, current); } catch (_) {}
    if (!from || from === current) return;
    html.setAttribute('data-console-direction', direction(from, current));
    html.setAttribute('data-console-motion', 'enter');
    // End after the document can paint, even when its first load was slow.
    const finish = () => { cleanupTimer = root.setTimeout(() => html.removeAttribute('data-console-motion'), 240); };
    if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', finish, {once: true});
    else finish();
  }
  function previous() {
    const activation = root.navigation?.activation;
    if (activation?.navigationType === 'reload') return current;
    if (activation?.from?.url) return route(activation.from.url, location.href);
    try {
      const stored = root.sessionStorage.getItem(KEY);
      if (stored) return route(stored, location.href);
    } catch (_) {}
    return doc.referrer ? route(doc.referrer, location.href) : null;
  }
  // Apply before the first paint. The new document is immediately interactive;
  // no outgoing bitmap, overlay, navigation interception or animation frame loop.
  enter(previous());
  root.addEventListener('pageshow', event => {
    if (event.persisted) enter(previous());
  });
})(globalThis);
