// Page presentation only: navigation must never start a device, upload, or command.
(function (root) {
  'use strict';
  const PAGES = Object.freeze({
    home: Object.freeze({path: '/', title: '房间总览', description: '房间画面、环境与后台任务，一处掌握。',
      camera: true, galleryLimit: 3, environmentHistory: false, automaticAi: true, statusPollMs: 2500}),
    connections: Object.freeze({path: '/connections', title: '连接与设备', description: '连接手机，管理授权，以及房间里的 Freenove 主板。',
      camera: false, galleryLimit: 0, environmentHistory: false, automaticAi: false, statusPollMs: 5000}),
    settings: Object.freeze({path: '/settings', title: '运行设置', description: '调整观察节奏、温湿度记录和本地服务参数。',
      camera: false, galleryLimit: 0, environmentHistory: false, automaticAi: false, statusPollMs: 5000}),
    history: Object.freeze({path: '/history', title: '观察记录', description: '回看已保存的抓拍、环境记录和 AI 观察。',
      camera: false, galleryLimit: 18, environmentHistory: true, automaticAi: true, statusPollMs: 15000})
  });
  function pageForPath(pathname) {
    const path = typeof pathname === 'string' ? pathname.replace(/\/+$/, '') || '/' : '/';
    return path === '/mobile-setup' ? 'connections' : Object.keys(PAGES).find(page => PAGES[page].path === path) || 'home';
  }
  function apply(doc, pathname) {
    const page = pageForPath(pathname), features = PAGES[page];
    if (page === 'history') {
      const galleryTarget = doc.getElementById('historyGallery');
      const resultTarget = doc.getElementById('historyAiResults');
      if (galleryTarget) galleryTarget.appendChild(doc.getElementById('gallerySection'));
      if (resultTarget) resultTarget.appendChild(doc.getElementById('automaticAiResultSection'));
    }
    for (const node of doc.querySelectorAll('[data-console-pages]')) {
      node.hidden = !node.getAttribute('data-console-pages').split(/\s+/).includes(page);
    }
    for (const link of doc.querySelectorAll('[data-console-nav]')) {
      if (link.getAttribute('data-console-nav') === page) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    }
    doc.body.setAttribute('data-console-page', page);
    doc.title = `${features.title} · DreamShield`;
    doc.getElementById('consolePageTitle').textContent = features.title;
    doc.getElementById('consolePageDescription').textContent = features.description;
    return {page, features};
  }
  const navigation = {PAGES, pageForPath, apply};
  root.DreamShieldConsoleNavigation = navigation;
  if (typeof module !== 'undefined' && module.exports) module.exports = navigation;
})(globalThis);
