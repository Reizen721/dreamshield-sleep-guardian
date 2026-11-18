(function (root) {
  'use strict';
  const ROWS = 62, COLS = 80;
  const PALETTE = [[22,45,84],[38,100,124],[85,182,170],[242,220,136],[237,110,84]];

  function validateMatrix(value) {
    return Array.isArray(value) && value.length === ROWS && value.every(row =>
      Array.isArray(row) && row.length === COLS && row.every(pixel =>
        pixel === null || (typeof pixel === 'number' && Number.isFinite(pixel))));
  }
  function temperatureRange(matrix) {
    if (!validateMatrix(matrix)) return null;
    let min = Infinity, max = -Infinity;
    for (const row of matrix) for (const pixel of row) if (pixel !== null) {
      min = Math.min(min, pixel); max = Math.max(max, pixel);
    }
    return Number.isFinite(min) ? {min, max} : null;
  }
  function colorAt(value, min, max) {
    if (value === null || !Number.isFinite(value)) return [7,16,24];
    const fraction = max > min ? Math.max(0, Math.min(1, (value-min)/(max-min))) : 0.5;
    const position = fraction*(PALETTE.length-1), index = Math.min(PALETTE.length-2, Math.floor(position));
    const blend = position-index;
    return PALETTE[index].map((channel, channelIndex) =>
      Math.round(channel+(PALETTE[index+1][channelIndex]-channel)*blend));
  }
  function safeMetadataUrl(value) {
    if (typeof value !== 'string' || !/^\/thermal-captures\/[A-Za-z0-9_./-]+\.json$/.test(value)) return null;
    let decoded;
    try { decoded = decodeURIComponent(value); } catch (_) { return null; }
    if (decoded.split('/').some(part => part === '..' || part === '.')) return null;
    return value;
  }
  function normalizedUrl(value) { return String(value || '').trim().replace(/\/+$/, ''); }
  function localTime(value) {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN', {hour12:false});
  }
  function isSimulated(value) {
    return Boolean(value && (value.simulated === true || value.validation_status === 'SIMULATED_ONLY'));
  }
  function connectionState(config, controlUrl) {
    const configured = Boolean(config && normalizedUrl(config.base_url));
    const pairMismatch = Boolean(config && normalizedUrl(config.paired_control_base_url) &&
      normalizedUrl(controlUrl) !== normalizedUrl(config.paired_control_base_url));
    return {configured, pairMismatch, ready:configured && !pairMismatch};
  }
  const helpers = {validateMatrix, temperatureRange, colorAt, safeMetadataUrl, normalizedUrl, isSimulated, connectionState};
  root.DreamShieldThermalSetup = helpers;
  if (typeof module !== 'undefined' && module.exports) module.exports = helpers;
  if (!root.document) return;

  const doc = root.document, byId = id => doc.getElementById(id);
  let savedConfig = null, currentControlUrl = '', busy = false, stopped = false;
  let fieldsLoaded = false, frame = null, timer = null, lastPreviewKey = '';
  let previewEnabled = false;
  const buttonIds = ['saveThermal','startPreview','stopPreview','captureThermal','startThermal','stopThermal','refreshHistory'];

  async function request(path, body) {
    const options = {cache:'no-store', credentials:'same-origin'};
    if (body !== undefined) Object.assign(options, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)
    });
    const controller = new AbortController(), timeout = root.setTimeout(() => controller.abort(), 15000);
    options.signal = controller.signal;
    try {
      const response = await root.fetch(path, options);
      const payload = await response.json();
      if (!response.ok || payload.ok === false) throw Error(payload.error || `服务器返回HTTP ${response.status}`);
      return payload;
    } catch (error) {
      if (error.name === 'AbortError') throw Error('请求超时。检查电脑服务与热像板网络后再试。');
      throw error;
    } finally { root.clearTimeout(timeout); }
  }
  function feedback(message, error) {
    const node = byId('actionFeedback'); node.textContent = message; node.classList.toggle('error', Boolean(error));
  }
  function serviceError(message) {
    const node = byId('serviceAlert'); node.textContent = message || ''; node.hidden = !message;
  }
  function fieldsMatch() {
    return savedConfig && normalizedUrl(byId('thermalUrl').value) === normalizedUrl(savedConfig.base_url) &&
      Number(byId('captureInterval').value) === savedConfig.interval_seconds &&
      normalizedUrl(currentControlUrl) === normalizedUrl(savedConfig.paired_control_base_url);
  }
  function updateButtons() {
    const ready = connectionState(savedConfig, currentControlUrl).ready;
    const enabled = Boolean(savedConfig && savedConfig.enabled);
    byId('saveThermal').disabled = busy;
    byId('startPreview').disabled = busy || !ready || previewEnabled;
    byId('stopPreview').disabled = busy || !previewEnabled;
    byId('captureThermal').disabled = busy || !ready;
    byId('startThermal').disabled = busy || !ready || enabled;
    byId('stopThermal').disabled = busy || !enabled;
    byId('refreshHistory').disabled = busy;
    buttonIds.forEach(id => byId(id).setAttribute('aria-busy', busy ? 'true' : 'false'));
  }
  function showStatus(thermal) {
    if (!thermal || typeof thermal !== 'object') throw Error('热像状态格式不正确，请更新服务器后重试。');
    savedConfig = thermal.config || {};
    previewEnabled = thermal.preview_enabled === true;
    const initialStatus = !fieldsLoaded;
    if (!fieldsLoaded) {
      byId('thermalUrl').value = savedConfig.base_url || '';
      byId('captureInterval').value = savedConfig.interval_seconds || 2;
      fieldsLoaded = true;
    }
    const enabled = savedConfig.enabled === true, badge = byId('captureState');
    const pairMismatch = connectionState(savedConfig, currentControlUrl).pairMismatch;
    badge.classList.toggle('active', (enabled || previewEnabled) && !pairMismatch && !thermal.last_error);
    badge.textContent = pairMismatch ? '配对需更新' : thermal.last_error ? '采集遇到问题' :
      enabled && previewEnabled ? '预览与定时保存中' : enabled ? '定时保存中' :
      previewEnabled ? '实时预览中 · 未自动保存' : savedConfig.base_url ? '热像任务已停止' : '等待配置';
    const counters = thermal.counters || {};
    byId('savedCount').textContent = String(counters.saved ?? counters.captured ?? counters.frames_saved ?? 0);
    const lastError = thermal.last_error || thermal.error;
    if (pairMismatch) serviceError('日常控制台的设备地址已改变。请核对当前控制板，再保存配对。');
    else if (lastError) serviceError(`热像读取：${typeof lastError === 'string' ? lastError : lastError.message || lastError.code || '读取失败'}。请检查地址和供电；原厂 TCP 固件还需先断开 SenXor 软件。`);
    else serviceError('');
    if (!lastError && !pairMismatch && thermal.latest && thermal.latest.freshness === 'fresh' &&
        byId('actionFeedback').classList.contains('error')) {
      feedback(previewEnabled ? '连接已恢复，实时热像正在更新。' : '热像读取已恢复。');
    }
    if (initialStatus) feedback(enabled ? '定时保存正在运行，关闭网页后仍会继续。' :
      previewEnabled ? '实时预览正在运行，未开启自动保存。' : '尚未开始预览或定时保存。');
    updateButtons();
  }
  function renderFrame(value) {
    frame = value;
    const range = value && temperatureRange(value.temperatures_c);
    const canvas = byId('thermalCanvas'), empty = byId('emptyPreview');
    const usable = Boolean(range);
    canvas.hidden = !usable; empty.hidden = usable; byId('thermalScale').hidden = !usable;
    byId('simulationFlag').hidden = !usable || !isSimulated(value);
    if (!usable) {
      byId('frameTemperature').textContent = '—'; byId('frameNumber').textContent = '—';
      byId('frameAge').textContent = '—'; byId('staleFlag').hidden = true;
      byId('thermalView').classList.remove('is-stale'); lastPreviewKey = '';
      if (value) { empty.querySelector('strong').textContent = '温度矩阵暂不可显示'; }
      return;
    }
    byId('frameNumber').textContent = String(value.frame_number ?? '—');
    byId('frameTemperature').textContent = `${range.min.toFixed(1)} / ${range.max.toFixed(1)} °C`;
    byId('scaleMin').textContent = `${range.min.toFixed(1)} °C`;
    byId('scaleMax').textContent = `${range.max.toFixed(1)} °C`;
    const key = `${value.received_at || ''}:${value.frame_number ?? ''}:${value.raw_file || ''}`;
    if (key !== lastPreviewKey || !key.replace(/:/g,'')) {
      const context = canvas.getContext('2d'), pixels = context.createImageData(COLS, ROWS);
      for (let y=0;y<ROWS;y++) for (let x=0;x<COLS;x++) {
        const pixel = 4*(y*COLS+x), color = colorAt(value.temperatures_c[y][x], range.min, range.max);
        pixels.data[pixel] = color[0]; pixels.data[pixel+1] = color[1]; pixels.data[pixel+2] = color[2]; pixels.data[pixel+3] = 255;
      }
      context.putImageData(pixels,0,0); lastPreviewKey = key;
    }
    updateFrameAge();
  }
  function updateFrameAge() {
    if (!frame || !validateMatrix(frame.temperatures_c)) return;
    const received = Date.parse(frame.received_at || ''), supplied = Number(frame.age_seconds);
    const age = Number.isFinite(received) ? Math.max(0,(Date.now()-received)/1000) : Number.isFinite(supplied) ? Math.max(0,supplied) : null;
    byId('frameAge').textContent = age === null ? '未知' : `${Math.floor(age)} 秒`;
    const staleAfter = Math.max(10, 3*Number(savedConfig && savedConfig.interval_seconds || 2));
    const inactive = !previewEnabled && !(savedConfig && savedConfig.enabled);
    const stale = inactive || frame.freshness === 'stale' || frame.freshness === 'unavailable' || age === null || age > staleAfter;
    byId('staleFlag').textContent = inactive ? '缓存画面 · 热像任务已停止' : '缓存画面 · 等待新帧';
    byId('staleFlag').hidden = !stale; byId('thermalView').classList.toggle('is-stale',stale);
  }
  function showHistory(items) {
    const tbody = byId('historyBody'); tbody.replaceChildren();
    const rows = Array.isArray(items) ? items.slice(0,20) : [];
    byId('historyEmpty').hidden = rows.length > 0; byId('historyTable').hidden = rows.length === 0;
    for (const item of rows) {
      const row = doc.createElement('tr');
      const cells = [localTime(item.received_at), String(item.frame_number ?? '—'), isSimulated(item) ? '模拟数据' : '设备采集'];
      cells.forEach((value,index) => {const cell=doc.createElement('td');cell.textContent=value;if(index===2&&isSimulated(item))cell.className='simulated-label';row.append(cell);});
      const cell = doc.createElement('td'), url = safeMetadataUrl(item.metadata_url || item.json_url);
      if (url) { const link = doc.createElement('a');link.href=url;link.textContent='查看JSON';link.target='_blank';link.rel='noopener';cell.append(link); }
      else cell.textContent = '已保存在服务器';
      row.append(cell); tbody.append(row);
    }
  }
  async function refreshHistory() { const payload = await request('/api/thermal/captures?limit=20'); showHistory(payload.items); }
  async function readConsoleStatus() {
    try {
      const data = await request('/api/app/status');
      root.dispatchEvent(new CustomEvent('dreamshield:status', {detail:{available:true, data}}));
      return data;
    } catch (error) {
      root.dispatchEvent(new CustomEvent('dreamshield:status', {detail:{available:false}}));
      throw error;
    }
  }
  async function refresh() {
    const [status, app] = await Promise.all([request('/api/thermal/status'), readConsoleStatus()]);
    currentControlUrl = app.config && app.config.device_base_url || '';
    byId('controlUrl').value = currentControlUrl || '未配置 · 可独立预览热像';
    showStatus(status.thermal);
    const payload = await request('/api/thermal/frame'); renderFrame(payload.frame);
  }
  async function operate(action) {
    if (busy) return;
    busy = true; updateButtons();
    try { await action(); await refresh(); await refreshHistory(); }
    catch (error) { feedback(error.message || '操作失败，请检查服务器后重试。',true); }
    finally {busy=false;updateButtons();}
  }
  function requireSavedFields() {
    if (!fieldsMatch()) throw Error('地址或间隔有未保存的修改，请先点击“保存连接设置”。');
  }
  byId('thermalForm').addEventListener('submit', event => {
    event.preventDefault();
    if (!byId('thermalForm').reportValidity()) return;
    operate(async () => {
      const interval = Number(byId('captureInterval').value);
      if (!Number.isInteger(interval) || interval<1 || interval>60) throw Error('采集间隔请填写1～60的整数。');
      const payload = await request('/api/thermal/config',{
        base_url:byId('thermalUrl').value.trim(), paired_control_base_url:currentControlUrl,
        interval_seconds:interval, enabled:false
      });
      if (payload.thermal) showStatus(payload.thermal);
      feedback('连接设置已保存。点击“开始实时预览”查看画面，或单次保存一帧。');
    });
  });
  byId('startPreview').addEventListener('click', () => operate(async () => {
    requireSavedFields();
    await request('/api/thermal/preview',{enabled:true});
    feedback('实时预览已开启，画面不会自动保存。原厂 TCP 固件请保持 SenXor 软件断开。');
  }));
  byId('stopPreview').addEventListener('click', () => operate(async () => {
    await request('/api/thermal/preview',{enabled:false});
    feedback('实时预览已停止。若还开启着定时保存，请另行点击“停止定时保存”。');
  }));
  byId('captureThermal').addEventListener('click', () => operate(async () => {
    requireSavedFields(); feedback('正在读取并保存一帧…');
    const payload = await request('/api/thermal/capture',{});
    const result = payload.result || payload;
    const messages = {captured:'新温度帧已保存。',no_new_frame:'设备尚无新帧，此次没有重复保存。',
      not_ready:'设备还没有可用温度帧，请稍后重试。',duplicate:'帧号未更新，此次没有重复保存。'};
    feedback(messages[result.status] || '读取完成，请查看热图与保存记录。');
  }));
  byId('startThermal').addEventListener('click', () => operate(async () => {
    requireSavedFields(); await request('/api/thermal/config',{enabled:true});
    feedback('定时保存已开启。每帧约10KB原始数据，另有温度矩阵JSON；关闭网页后仍会继续。');
  }));
  byId('stopThermal').addEventListener('click', () => operate(async () => {
    await request('/api/thermal/config',{enabled:false}); feedback('定时保存已停止，已有文件保留。预览状态以上方状态为准。');
  }));
  byId('refreshHistory').addEventListener('click', () => operate(async () => { feedback('已刷新保存记录。'); }));
  byId('thermalUrl').addEventListener('input',updateButtons);
  byId('captureInterval').addEventListener('input',updateButtons);
  root.addEventListener('pagehide',()=>{stopped=true;root.clearTimeout(timer);});
  async function poll() {
    if (stopped) return;
    updateFrameAge();
    try { if (!busy) await refresh(); }
    catch (error) { serviceError(`无法读取服务器热像状态：${error.message}。确认服务仍在运行。`); }
    finally {if(!stopped)timer=root.setTimeout(poll,2000);}
  }
  async function init() {
    try {
      await refresh(); await refreshHistory();
    } catch (error) {serviceError(`初始化失败：${error.message}。确认服务器已更新并运行。`);}
    finally {updateButtons();if(!stopped)timer=root.setTimeout(poll,2000);}
  }
  init();
})(globalThis);
