/* PC-local BLE provisioning UI. Never uses Web Bluetooth, credential URLs or browser storage. */
(function (root) {
  'use strict';
  const STATES = {
    idle: ['准备就绪后，开始扫描', '进入此页只读取状态；扫描、连接和发凭据都需要你点击。'],
    scanning: ['正在扫描蓝牙设备', '由电脑 Python 服务使用 Windows 蓝牙扫描。请保持开发板配网窗口开启。'],
    devices_found: ['蓝牙扫描已结束', '选中你的 DreamShield 设备，输入物理 USB 串口给出的独立配网码。'],
    connecting: ['正在建立安全会话', '连接所选设备并校验配网码；尚未发送家庭 Wi-Fi 密码。'],
    connected: ['安全会话已建立', '可让 ESP32 扫描 2.4 GHz Wi-Fi，或手动填写隐藏 SSID。'],
    scanning_wifi: ['正在让设备扫描 Wi-Fi', '此列表来自 ESP32，不是电脑的无线网卡。'],
    wifi_ready: ['设备 Wi-Fi 扫描已结束', '选择家庭网络并填入密码；隐藏 SSID 可手动输入。'],
    configuring: ['正在发送 Wi-Fi 配置', '提交已发出，但还不能判定成功；正在等待设备实际连接结果。'],
    waiting_wifi: ['等待设备连接 Wi-Fi', '“凭据已发送”不是成功。需要设备确认连网并取得有效地址。'],
    provisioned: ['设备已报告 Wi-Fi 地址', '尚未采用为监控地址。请点击下方按钮，让电脑验证设备身份和接口。'],
    disconnected: ['蓝牙已断开', '蓝牙断开不等于 Wi-Fi 断开，也不会停止电脑原有监控任务。'],
    cancelled: ['当前配网任务已取消', '设备已收到的配置不能撤回；请重新核对设备状态后操作。'],
    error: ['配网任务未完成', '请检查蓝牙、配网窗口和网络设置，再按提示重试。'],
    unavailable: ['电脑 BLE 功能尚不可用', '请按中文教程安装 Python BLE 依赖并重新启动服务器。']
  };
  const ERRORS = {
    dependencies_missing: '缺少 Python BLE 依赖，请按中文教程安装后重启服务器。',
    dependency_missing: '缺少 Python BLE 依赖，请按中文教程安装后重启服务器。',
    missing_dependencies: '缺少 Python BLE 依赖，请按中文教程安装后重启服务器。',
    bleak_missing: '缺少 bleak 蓝牙依赖，请安装后重启服务器。',
    bluetooth_unavailable: 'Windows 蓝牙不可用：检查蓝牙开关、硬件和驱动。',
    adapter_unavailable: '未发现可用蓝牙适配器，请检查硬件和驱动。',
    bluetooth_off: '请在 Windows“设置 → 蓝牙和设备”中打开蓝牙。',
    adapter_off: '请打开 Windows 蓝牙开关后重新扫描。',
    device_not_found: '设备未找到：在实体 USB 串口重新发送 p，开启配网窗口后重试。',
    device_disconnected: '设备蓝牙已断开，请重新发送 p 并连接。',
    connection_failed: '蓝牙连接失败：保持开发板在电脑附近，确认 5 分钟配网窗口仍有效。',
    pop_mismatch: '配网码校验失败。请使用这个设备物理 USB 串口显示的码，注意大小写。',
    invalid_pop: '配网码格式无效，请完整复制物理 USB 串口显示的 32 位码。',
    security_handshake_failed: '安全会话校验失败：请核对设备、配网码和固件版本，不发送 Wi-Fi 凭据。',
    security_mismatch: '固件安全版本不匹配，本页只支持带配网码的 Security 1。',
    timeout: '操作超时：检查配网窗口、设备距离及 Windows 蓝牙；不会把超时当成功。',
    wifi_auth_failed: 'Wi-Fi 认证失败：检查名称、密码和路由器加密设置。',
    wifi_connect_failed: '设备未成功连接 Wi-Fi，请核对 2.4 GHz 网络、密码和信号。',
    wifi_not_found: '设备未发现该 Wi-Fi，请检查 SSID、2.4 GHz 网络与信号。',
    invalid_credentials: 'Wi-Fi 名称或密码格式不符合要求，请重新填写。',
    cancelled: '任务已取消；设备已经收到的配置不能撤回。',
    busy: '后台已有配网任务，请等待结束或显式取消。',
    identity_mismatch: '网络设备身份与 BLE 配网设备不一致，不能设为监控地址。',
    verification_failed: '电脑无法验证设备身份与接口，监控地址没有更改。',
    device_unreachable: '电脑无法访问设备地址：检查同一局域网、防火墙或路由器设备隔离。',
    invalid_result: '设备尚未报告有效 Wi-Fi 地址，不能接入监控。',
    bluetooth_error: 'Windows 蓝牙操作失败，请检查蓝牙开关、硬件驱动和设备配网窗口。',
    operation_timeout: '操作超时：在实体 USB 串口重新发送 p，确认配网窗口开启后重试。',
    authentication_failed: '设备安全认证失败，请核对这台设备物理 USB 串口显示的独立配网码。',
    protocol_error: '设备响应或配网协议不兼容，请检查支持 BLE 配网的固件版本。',
    wifi_network_not_found: '设备未发现该 Wi-Fi，请核对 2.4 GHz 网络、SSID 和信号。',
    wifi_failed: '设备连接 Wi-Fi 失败；可重新扫描并填写凭据后再提交。',
    session_expired: '蓝牙会话或扫描结果已过期，请重新开启设备配网窗口并扫描连接。',
    local_owner_required: '此工具仅供电脑本机操作。请使用 http://127.0.0.1:8000/device-setup 打开，不要从局域网 IP 或安卓访问。',
    busy_or_changed: '后台任务忙、配网结果变化或身份验证未通过。请刷新当前状态后再操作；不能据此认定接入成功。'
  };
  function plain(value, fallback = '—', max = 180) {
    return typeof value === 'string' && value ? value.slice(0, max) : fallback;
  }
  function safeCode(value) { return typeof value === 'string' && /^[a-z][a-z0-9_]{0,79}$/.test(value) ? value : ''; }
  function errorText(code, fallback = '操作未完成，请检查电脑蓝牙、设备配网窗口与网络设置。') {
    const normalized = safeCode(code);
    return ERRORS[normalized] || (normalized ? fallback + '（' + normalized + '）' : fallback);
  }
  function utf8Bytes(value) { return new TextEncoder().encode(value).length; }
  function privateIPv4(value) {
    if (typeof value !== 'string' || !/^\d{1,3}(?:\.\d{1,3}){3}$/.test(value)) return false;
    const parts = value.split('.').map(Number);
    if (parts.some(number => number < 0 || number > 255)) return false;
    return parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) || (parts[0] === 192 && parts[1] === 168);
  }
  function validMonitorUrl(value) {
    try { const url = new URL(value); return url.protocol === 'http:' && privateIPv4(url.hostname) && !url.username && !url.password && !url.search && !url.hash && (url.pathname === '/' || url.pathname === '') && (!url.port || url.port === '80'); }
    catch (_) { return false; }
  }
  function connectPayload(deviceId, pop) {
    if (typeof deviceId !== 'string' || !deviceId || deviceId.length > 200 || /[\u0000-\u001f\u007f]/.test(deviceId)) throw new Error('请先选择刚刚扫描发现的目标设备。');
    if (typeof pop !== 'string' || !/^[a-fA-F0-9]{32}$/.test(pop.trim())) throw new Error('请填写物理 USB 串口显示的 32 位十六进制独立配网码。');
    return {device_id: deviceId, pop: pop.trim()};
  }
  function wifiPayload(ssid, password) {
    if (typeof ssid !== 'string' || utf8Bytes(ssid) < 1 || utf8Bytes(ssid) > 32 || /[\u0000-\u001f\u007f]/.test(ssid)) throw new Error('SSID 需为 1–32 个 UTF-8 字节，不含控制字符；名称中的空格不会被自动删去。');
    if (typeof password !== 'string' || utf8Bytes(password) < 8 || utf8Bytes(password) > 63 || /[\u0000-\u001f\u007f]/.test(password)) throw new Error('Wi-Fi 密码需为 8–63 个 UTF-8 字节，不含控制字符；本版不支持 64 位十六进制原始密钥或开放网络。');
    return {ssid, password};
  }
  function networkView(network) {
    const n = network && typeof network === 'object' ? network : {};
    const open = n.auth === 0 || ['Open', 'OPEN', 'open', 'none', 'WIFI_AUTH_OPEN'].includes(n.auth);
    const enterprise = n.auth === 5 || ['enterprise', 'WPA2_ENTERPRISE', 'WIFI_AUTH_WPA2_ENTERPRISE'].includes(n.auth);
    const wep = n.auth === 1 || ['WEP', 'wep', 'WIFI_AUTH_WEP'].includes(n.auth);
    const validName = typeof n.ssid === 'string' && utf8Bytes(n.ssid) > 0 && utf8Bytes(n.ssid) <= 32 && !/[\u0000-\u001f\u007f]/.test(n.ssid);
    const channel = Number.isInteger(n.channel) && n.channel >= 1 && n.channel <= 14 ? n.channel : null;
    const rssi = Number.isFinite(n.rssi) && n.rssi >= -127 && n.rssi <= 0 ? n.rssi : null;
    return {ssid: plain(n.ssid, '隐藏 SSID'), supported: validName && n.supported !== false && !open && !enterprise && !wep,
      security: open ? '开放网络 · 本版不支持' : enterprise ? '企业认证 · 本版不支持' : wep ? 'WEP · 本版不支持' : n.supported === false ? '此认证模式暂不支持' : '加密网络',
      detail: (rssi === null ? '信号未知' : rssi + ' dBm') + (channel === null ? '' : ' · 信道 ' + channel)};
  }
  function snapshotView(snapshot, selectedId = '', adopted = null) {
    const s = snapshot && typeof snapshot === 'object' ? snapshot : {};
    const state = safeCode(s.state) || 'idle', operation = plain(s.operation, '', 40), busy = s.busy === true;
    const devices = Array.isArray(s.devices) ? s.devices.filter(device => typeof device?.id === 'string' && device.id.length <= 200).slice(0, 30) : [];
    const connected = s.connected_device && typeof s.connected_device === 'object' ? s.connected_device : null;
    const result = s.result && typeof s.result === 'object' ? s.result : {};
    const hasIp = privateIPv4(result.ip_address);
    const errorCode = safeCode(s.error_code);
    const available = s.available === true, safeSession = s.security_version === 1;
    const phase = {scanning: 0, devices_found: 0, connecting: 1, connected: 1, scanning_wifi: 1, wifi_ready: 1, configuring: 2, waiting_wifi: 3, provisioned: 3}[state];
    const failedPhase = operation === 'scan' ? 0 : operation === 'connect' || ['wifi-scan', 'scan_wifi'].includes(operation) ? 1 : ['configure', 'provision'].includes(operation) || errorCode.startsWith('wifi_') ? 3 : ['authentication_failed', 'session_expired', 'protocol_error', 'device_disconnected'].includes(errorCode) ? 1 : null;
    const defaults = STATES[state] || ['配网状态已更新', busy ? '电脑正在执行用户请求的配网操作，请等待或显式取消。' : '请根据设备状态继续；页面不会自动发送任何凭据。'];
    const adoptionMatches = adopted?.verified === true && adopted.ip_address === result.ip_address && adopted.device_id === result.device_id && state === 'provisioned';
    return {state, operation, busy, available, safeSession, devices, connected, result, errorCode,
      title: adoptionMatches ? (adopted.camera_ready ? '身份验证通过，已采用监控地址' : '身份验证通过，摄像头尚待检查') : defaults[0],
      message: errorCode ? errorText(errorCode) : adoptionMatches ? '定时抓拍、温湿度记录和 AI 已关闭。请回监控页检查设备，再按需重新开启。' : defaults[1],
      canScan: available && !busy,
      canConnect: available && safeSession && !busy && !connected && devices.some(device => device.id === selectedId),
      canWifi: available && safeSession && !busy && Boolean(connected) && (['connected', 'wifi_ready'].includes(state) || state === 'error' && errorCode.startsWith('wifi_')),
      canDisconnect: available && !busy && Boolean(connected),
      canAdopt: available && !busy && !errorCode && hasIp && state === 'provisioned' && !adoptionMatches,
      canCancel: busy,
      adoptionMatches, phase: adoptionMatches ? 4 : phase, failedPhase: state === 'error' ? failedPhase : null,
      wifiSuccess: hasIp && state === 'provisioned' && !errorCode,
      dependency: available ? '已安装 · 未等于硬件已测试' : '缺失 / 尚不可用'};
  }
  function createController(doc, options) {
    const node = id => doc.getElementById(id), timers = options.timers || root;
    let snapshot = null, selectedId = '', adopted = null, pending = false, polling = false, disposed = false;
    let timer = null, epoch = 0, deviceKey = '', wifiKey = '';
    function text(id, value) { node(id).textContent = value; }
    function feedback(id, message, failed = false) { text(id, message); node(id).classList.toggle('error', failed); }
    function secretsClear() { node('pairingCode').value = ''; node('wifiPassword').value = ''; node('wifiSsid').value = ''; }
    function updateButtons() {
      const view = snapshotView(snapshot, selectedId, adopted);
      for (const [id, enabled] of [['scanDevicesButton', view.canScan], ['connectDeviceButton', view.canConnect], ['disconnectButton', view.canDisconnect], ['scanWifiButton', view.canWifi], ['configureWifiButton', view.canWifi], ['adoptDeviceButton', view.canAdopt], ['cancelOperationButton', view.canCancel]]) node(id).disabled = pending || !enabled;
      text('scanDevicesButton', view.connected ? '重新扫描（会断开蓝牙）' : '扫描附近梦盾设备');
      for (const id of ['wifiSsid', 'wifiPassword']) node(id).disabled = pending || !view.canWifi;
      node('pairingCode').disabled = pending || view.busy || Boolean(view.connected) || !view.available || !view.safeSession || !selectedId;
      doc.querySelectorAll('.device-option,.wifi-option').forEach(button => { button.disabled = pending || view.busy || button.dataset.unsupported === 'true' || (button.classList.contains('device-option') && Boolean(view.connected)); });
    }
    function make(tag, className, value) { const n = doc.createElement(tag); if (className) n.className = className; if (value != null) n.textContent = value; return n; }
    function renderDevices(view) {
      const key = JSON.stringify(view.devices);
      if (key === deviceKey) return;
      deviceKey = key;
      const container = node('deviceList'); container.replaceChildren();
      if (!view.devices.some(device => device.id === selectedId)) { selectedId = ''; node('pairingCode').value = ''; }
      if (!view.devices.length) {
        const empty = make('div', 'empty-state'); empty.append(make('strong', '', view.state === 'scanning' ? '正在寻找设备…' : view.state === 'devices_found' ? '本次没有发现可配网设备' : '尚未发现设备'), make('p', '', '在 USB 串口 115200 发送 p，保持设备在附近，再点击扫描。'));
        container.append(empty); return;
      }
      container.setAttribute('role', 'radiogroup');
      for (const device of view.devices) {
        const button = make('button', 'device-option'); button.type = 'button'; button.setAttribute('role', 'radio'); button.setAttribute('aria-checked', String(device.id === selectedId));
        const heading = make('span'); heading.append(make('strong', '', plain(device.name, '未命名配网设备')), make('small', '', plain(device.id)));
        const rssi = Number.isFinite(device.rssi) && device.rssi >= -127 && device.rssi <= 0 ? device.rssi + ' dBm' : '信号未知';
        button.append(heading, make('span', 'signal', rssi));
        button.addEventListener('click', () => {
          if (button.disabled) return;
          selectedId = device.id; node('pairingCode').value = '';
          container.querySelectorAll('button').forEach(candidate => candidate.setAttribute('aria-checked', String(candidate === button)));
          feedback('connectionFeedback', '已选择 ' + plain(device.name, '目标设备') + '。请输入它在物理 USB 串口显示的独立配网码。'); updateButtons();
        }); container.append(button);
      }
    }
    function renderWifi(view) {
      const networks = Array.isArray(snapshot?.wifi_networks) ? snapshot.wifi_networks.slice(0, 50) : [];
      const key = JSON.stringify(networks);
      if (key === wifiKey) return;
      wifiKey = key;
      const container = node('wifiList'); container.replaceChildren();
      if (!networks.length) { container.append(make('div', 'compact-empty', view.state === 'scanning_wifi' ? '设备正在扫描…' : '尚无 Wi-Fi 列表；连接后可让设备扫描，隐藏 SSID 可手动填写。')); return; }
      for (const network of networks) {
        const n = networkView(network), button = make('button', 'wifi-option' + (!n.supported ? ' unsupported' : '')); button.type = 'button'; button.dataset.unsupported = String(!n.supported);
        const heading = make('span'); heading.append(make('strong', '', n.ssid), make('small', '', n.security)); button.append(heading, make('span', 'signal', n.detail));
        button.addEventListener('click', () => { if (button.disabled || !n.supported) return; node('wifiSsid').value = network.ssid; node('wifiPassword').value = ''; feedback('wifiFeedback', '已选择网络，请填写密码；选择本身不会发给设备。'); }); container.append(button);
      }
    }
    function renderPhases(view) {
      const ids = ['phaseScan', 'phaseConnect', 'phaseConfigure', 'phaseVerify', 'phaseAdopt'];
      ids.forEach((id, index) => {
        const current = view.phase === index && (view.busy || index === 4 && !view.adoptionMatches);
        const done = Number.isInteger(view.phase) && (index < view.phase || index === view.phase && !view.busy && view.state !== 'error' && view.state !== 'idle') && !(index === 0 && view.state === 'devices_found' && !view.devices.length);
        node(id).classList.toggle('current', current); node(id).classList.toggle('done', done); node(id).classList.toggle('failed', view.failedPhase === index);
        if (current) node(id).setAttribute('aria-current', 'step'); else node(id).removeAttribute('aria-current');
      });
    }
    function render(next) {
      if (!next || typeof next !== 'object') throw new Error('invalid_snapshot');
      snapshot = next;
      if (adopted && (adopted.ip_address !== snapshot.result?.ip_address || adopted.device_id !== snapshot.result?.device_id)) adopted = null;
      const view = snapshotView(snapshot, selectedId, adopted);
      text('dependencyStatus', view.dependency);
      text('bluetoothStatus', ['scanning', 'devices_found'].includes(view.state) ? (view.busy ? '正在实际扫描' : '本次扫描已完成') : view.connected ? '本次已连接设备' : '尚未确认当前硬件状态');
      text('bleBadge', view.connected ? '安全会话已连接' : view.state === 'connecting' ? '校验中' : '未连接');
      node('bleBadge').classList.toggle('success', Boolean(view.connected));
      text('wifiBadge', view.wifiSuccess ? '已报告 Wi-Fi IP' : view.busy && ['configuring', 'waiting_wifi'].includes(view.state) ? '等待实际连网' : view.connected ? '可以填写网络' : '等待蓝牙连接');
      node('wifiBadge').classList.toggle('success', view.wifiSuccess);
      text('operationBadge', view.busy ? '任务进行中' : view.errorCode || view.state === 'error' ? '未完成' : '等待操作');
      node('operationBadge').classList.toggle('warning', Boolean(view.errorCode) || view.state === 'error');
      text('operationTitle', view.title); text('operationMessage', view.message);
      if (view.state === 'error') feedback(view.errorCode.startsWith('wifi_') ? 'wifiFeedback' : 'connectionFeedback', view.message, true);
      else if (view.state === 'provisioned') feedback('wifiFeedback', '设备已报告实际 Wi-Fi 地址。等待约 15–20 秒，再点击身份验证；这仍不等于已接入监控。');
      else if (view.state === 'connected') feedback('connectionFeedback', '独立配网码认证通过，可扫描或手动填写家庭 Wi-Fi。');
      text('resultSsid', plain(view.result.ssid)); text('resultIp', privateIPv4(view.result.ip_address) ? view.result.ip_address : '—');
      text('resultDevice', plain(view.result.device_name || view.connected?.name));
      text('adoptBadge', view.adoptionMatches ? (adopted.camera_ready ? '身份已验证' : '摄像头待检查') : '未验证');
      node('adoptBadge').classList.toggle('success', view.adoptionMatches && adopted.camera_ready === true);
      node('adoptBadge').classList.toggle('warning', view.adoptionMatches && adopted.camera_ready !== true);
      node('openMonitorLink').hidden = !view.adoptionMatches;
      text('resultExplanation', view.adoptionMatches ? (adopted.camera_ready ? '设备身份与接口已验证，电脑已采用该地址。后台任务已关闭，请回监控页检查后按需重新开启。' : '设备身份已验证，地址已采用，但摄像头尚未就绪。请回控制台检查摄像头连接 / 日志，不能把身份验证当成图像测试通过。') : view.wifiSuccess ? '设备已报告 Wi-Fi 地址，但电脑尚未验证、尚未更改监控地址。请显式点击“验证设备并设为监控地址”。' : '取得 Wi-Fi IP 不等于已接入监控；先等待设备的实际连接结果。');
      const alert = !view.available ? errorText(view.errorCode, 'Python BLE 依赖尚不可用。请按中文教程安装后重新启动服务器；不要只刷新网页。') : !view.safeSession ? '服务安全版本不匹配：本页仅支持 Security 1 + 独立配网码，暂不发送 Wi-Fi 凭据。' : '';
      text('serviceAlert', alert); node('serviceAlert').hidden = !alert;
      renderDevices(view); renderWifi(view); renderPhases(view); updateButtons();
    }
    function schedulePoll() {
      timers.clearTimeout(timer);
      if (!disposed && doc.hidden !== true) timer = timers.setTimeout(() => { void refresh(); }, snapshot?.busy === true ? 1000 : 5000);
    }
    async function refresh() {
      if (disposed || polling || pending || doc.hidden === true) return;
      polling = true; const requestEpoch = epoch;
      try {
        const response = await options.api('/api/provisioning/status', {method: 'GET'});
        if (disposed || requestEpoch !== epoch) return;
        render(response?.provisioning);
      } catch (_) {
        if (disposed || requestEpoch !== epoch) return;
        snapshot = null; updateButtons(); text('serviceAlert', '无法读取本机配网服务状态。请确认电脑服务器已启动、页面来自电脑本机，或重新启动支持 BLE 的服务器。'); node('serviceAlert').hidden = false;
        text('operationTitle', '服务状态未知'); text('operationMessage', '旧任务结果不能代表当前状态；不会自动重发凭据。');
      } finally { polling = false; schedulePoll(); }
    }
    async function action(name, payload = {}, feedbackId) {
      if (pending || disposed || snapshot?.busy === true && name !== 'cancel') return;
      pending = true; epoch++; timers.clearTimeout(timer); updateButtons();
      if (['scan', 'connect', 'configure'].includes(name)) adopted = null;
      if (feedbackId) feedback(feedbackId, '请求已提交，等待服务状态…');
      try {
        const response = await options.api('/api/provisioning/' + name, {method: 'POST', body: payload});
        if (disposed) return;
        render(response?.provisioning);
        if (feedbackId) feedback(feedbackId, name === 'configure' ? '配置任务已提交。请看右侧进度，等待设备实际报告连网结果；此时还不能算成功。' : '请求已处理，请查看当前进度。');
      } catch (error) {
        if (feedbackId) feedback(feedbackId, errorText(error?.code), true);
        else { text('serviceAlert', errorText(error?.code)); node('serviceAlert').hidden = false; }
      } finally {
        if (name === 'connect' || name === 'configure') secretsClear();
        if (payload && typeof payload === 'object') { delete payload.pop; delete payload.password; }
        pending = false; updateButtons(); schedulePoll();
      }
    }
    async function connect(event) {
      event?.preventDefault();
      if (node('connectDeviceButton').disabled) return;
      try { const payload = connectPayload(selectedId, node('pairingCode').value); node('pairingCode').value = ''; await action('connect', payload, 'connectionFeedback'); }
      catch (error) { secretsClear(); feedback('connectionFeedback', plain(error.message), true); }
    }
    async function configure(event) {
      event?.preventDefault();
      if (node('configureWifiButton').disabled) return;
      try { const payload = wifiPayload(node('wifiSsid').value, node('wifiPassword').value); secretsClear(); await action('configure', payload, 'wifiFeedback'); }
      catch (error) { secretsClear(); feedback('wifiFeedback', plain(error.message), true); }
    }
    async function adopt() {
      if (node('adoptDeviceButton').disabled || pending) return;
      const ip = snapshot?.result?.ip_address;
      pending = true; epoch++; timers.clearTimeout(timer); updateButtons(); feedback('adoptFeedback', '电脑正在检查网络设备身份与接口，暂不更改监控地址…');
      try {
        const response = await options.api('/api/provisioning/adopt', {method: 'POST', body: {}});
        if (disposed) return;
        if (response?.verified !== true || !validMonitorUrl(response.config?.device_base_url) || new URL(response.config.device_base_url).hostname !== ip || response.device_id !== snapshot.result?.device_id) throw Object.assign(new Error('unverified'), {code: 'verification_failed'});
        adopted = {verified: true, ip_address: ip, device_id: response.device_id, camera_ready: response.camera_ready === true};
        render(snapshot);
        feedback('adoptFeedback', response.camera_ready === true ? '身份与接口验证通过，监控地址已采用。抓拍、温湿度记录和 AI 已关闭；请回控制台检查后重新开启。' : '设备身份验证通过，地址已采用；但摄像头尚未就绪，需要进一步检查。所有后台任务保持关闭。', response.camera_ready !== true);
      } catch (error) { adopted = null; feedback('adoptFeedback', errorText(error?.code, '身份或接口验证未通过，监控地址没有更改。若设备刚取得 IP，请等待 15–20 秒后手动重试。'), true); }
      finally { pending = false; updateButtons(); schedulePoll(); }
    }
    node('scanDevicesButton').addEventListener('click', () => { if (!node('scanDevicesButton').disabled) { selectedId = ''; deviceKey = ''; secretsClear(); void action('scan'); } });
    node('disconnectButton').addEventListener('click', () => { if (!node('disconnectButton').disabled) { secretsClear(); void action('disconnect', {}, 'connectionFeedback'); } });
    node('scanWifiButton').addEventListener('click', () => { if (!node('scanWifiButton').disabled) void action('wifi-scan', {}, 'wifiFeedback'); });
    node('cancelOperationButton').addEventListener('click', () => { if (!node('cancelOperationButton').disabled) { secretsClear(); void action('cancel'); } });
    node('connectForm').addEventListener('submit', event => { void connect(event); }); node('wifiForm').addEventListener('submit', event => { void configure(event); });
    node('adoptDeviceButton').addEventListener('click', () => { void adopt(); });
    doc.addEventListener?.('visibilitychange', () => { timers.clearTimeout(timer); if (doc.hidden !== true) void refresh(); });
    updateButtons();
    return {refresh, connect, configure, adopt, render, action,
      dispose() { disposed = true; epoch++; timers.clearTimeout(timer); secretsClear(); }, getSnapshot: () => snapshot};
  }
  async function browserApi(path, options) {
    if (!/^\/api\/provisioning\/(?:status|scan|connect|wifi-scan|configure|disconnect|cancel|adopt)$/.test(path)) throw Object.assign(new Error('route'), {code: 'invalid_request'});
    const response = await root.fetch(path, {method: options.method, headers: options.method === 'POST' ? {'Content-Type': 'application/json'} : {},
      ...(options.method === 'POST' ? {body: JSON.stringify(options.body || {})} : {}), cache: 'no-store', credentials: 'same-origin', signal: AbortSignal.timeout(20000)});
    const data = await response.json();
    if (!response.ok || data.ok !== true) throw Object.assign(new Error('provisioning_request_failed'), {code: safeCode(data.error_code || data.provisioning?.error_code) || ({403: 'local_owner_required', 409: 'busy_or_changed', 502: 'device_unreachable'}[response.status])});
    return data;
  }
  root.DreamShieldProvisioning = {privateIPv4, validMonitorUrl, utf8Bytes, connectPayload, wifiPayload, networkView, snapshotView, errorText, createController};
  if (typeof module !== 'undefined' && module.exports) module.exports = root.DreamShieldProvisioning;
  if (root.document) {
    const boot = () => { const controller = createController(root.document, {api: browserApi}); void controller.refresh(); root.addEventListener?.('pagehide', () => controller.dispose()); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', boot); else boot();
  }
})(globalThis);
