(function (root) {
  'use strict';

  const freshMs = 45000;
  const maxDisplayAgeMs = 300000;
  const cleanUrl = value => typeof value === 'string' ? value.replace(/\/+$/, '') : '';
  const finiteBetween = (value, min, max) => Number.isFinite(value) && value >= min && value <= max;
  const hasReadings = row => row && finiteBetween(row.ambient_temperature_c, -40, 125)
    && finiteBetween(row.relative_humidity_percent, 0, 100);

  function sharedEnvironmentState(room, now) {
    const asOf = Date.parse(room.as_of), measuredAt = Date.parse(room.measured_at);
    const displayUntil = asOf + room.display_for_ms;
    const freshUntil = asOf + room.fresh_for_ms;
    const available = (room.state === 'fresh' || room.state === 'cached') && now < displayUntil
      && Number.isFinite(measuredAt) && finiteBetween(room.temperature_c, -40, 125)
      && finiteBetween(room.humidity_percent, 0, 100);
    const fresh = available && room.state === 'fresh' && now < freshUntil;
    if (available) {
      const last = room.reason === 'device_unreachable' ? '上次读数 · 主板无响应' : '上次读数';
      return {shared:true, label:fresh ? '近期实测' : last, tone:fresh ? 'ok' : 'warn',
        temperature:room.temperature_c.toFixed(1), humidity:room.humidity_percent.toFixed(1),
        detailTemperature:room.temperature_c.toFixed(2) + ' °C', detailHumidity:room.humidity_percent.toFixed(2) + ' %RH',
        measuredAt, nextChange:fresh ? freshUntil : displayUntil};
    }
    const reason = room.state === 'unavailable' ? room.reason : 'stale';
    const labels = {waiting:'等待采样', recording_off:'采集已关闭', stale:'读数已过期', device_unreachable:'上次主板无响应'};
    return {shared:true, label:labels[reason] || '读数不可用', tone:['waiting','recording_off'].includes(reason) ? 'neutral' : 'warn',
      temperature:'—', humidity:'—', detailTemperature:'—', detailHumidity:'—', measuredAt:null, nextChange:0};
  }

  // The server may report a current sample, an explicitly identified cached sample,
  // or just a failed contact attempt. Re-reading a snapshot never renews its age.
  function environmentState(data, direct, now) {
    if (data?.room_environment) return sharedEnvironmentState(data.room_environment, now);
    const target = cleanUrl(data?.config?.device_base_url);
    const matches = row => target && cleanUrl(row?.device_base_url) === target;
    const latest = matches(data?.last_environment) ? data.last_environment : null;
    const resolved = data?.environment_effective;
    const effective = ['fresh', 'last_valid'].includes(resolved?.source) && matches(resolved?.effective_observation)
      ? resolved.effective_observation : null;
    const receivedAt = Date.parse(latest?.received_at);
    const useDirect = direct && target && direct.deviceUrl === target && direct.receivedAt <= now
      && now - direct.receivedAt <= freshMs && (!Number.isFinite(receivedAt) || direct.receivedAt >= receivedAt);
    const currentValid = latest?.valid === true && latest.status === 'fresh';
    let row = currentValid ? latest : effective;
    let measuredAt = Date.parse(row?.measured_at_estimated);
    let previous = !currentValid && (resolved?.source === 'last_valid' || latest?.valid === false);
    let issue = latest?.error || resolved?.fault_reason;
    if (useDirect) {
      if (direct.valid) {
        row = direct.environment;
        measuredAt = direct.measuredAt;
        previous = false;
        issue = null;
      } else {
        previous = true;
        issue = direct.issue;
      }
    }
    const age = now - measuredAt;
    const margin = row?.request_duration_ms ?? 0;
    const fresh = !previous && hasReadings(row) && age >= 0 && finiteBetween(age + margin, 0, freshMs);
    const cached = hasReadings(row) && finiteBetween(age, 0, maxDisplayAgeMs);
    const result = {label:'等待采样', tone:'neutral', temperature:'—', humidity:'—', measuredAt:null, nextChange:0};
    if (fresh || cached) {
      result.label = fresh ? '近期实测' : '上次读数';
      result.tone = fresh ? 'ok' : 'warn';
      result.temperature = row.ambient_temperature_c.toFixed(1);
      result.humidity = row.relative_humidity_percent.toFixed(1);
      result.detailTemperature = row.ambient_temperature_c.toFixed(2) + ' °C';
      result.detailHumidity = row.relative_humidity_percent.toFixed(2) + ' %RH';
      result.measuredAt = measuredAt;
      result.nextChange = measuredAt + (fresh ? freshMs - margin : maxDisplayAgeMs) + 1;
      if (!fresh && issue === 'device_unreachable') result.label = '上次读数 · 主板无响应';
    } else if (issue === 'device_unreachable') {
      result.label = '上次主板无响应';
      result.tone = 'warn';
    } else if ((Number.isFinite(age) && age > maxDisplayAgeMs) || latest?.status === 'stale' || issue === 'stale_sample') {
      result.label = '读数已过期';
      result.tone = 'warn';
    } else if (latest || (useDirect && !direct.valid)) {
      result.label = '读数不可用';
      result.tone = 'warn';
    } else if (data?.config?.environment_recording_enabled === false) {
      result.label = '采集已关闭';
    }
    if (useDirect && !direct.valid) {
      const expires = direct.receivedAt + freshMs + 1;
      result.nextChange = result.nextChange ? Math.min(result.nextChange, expires) : expires;
    }
    return result;
  }

  root.DreamShieldRoomStatus = {environmentState};
  if (typeof module !== 'undefined' && module.exports) module.exports = root.DreamShieldRoomStatus;
  const doc = root.document;
  if (!doc) return;
  const bar = doc.querySelector('.room-statusbar');
  if (!bar) return;
  const service = doc.getElementById('consoleServerStatus');
  const source = doc.getElementById('roomSensorState'), time = doc.getElementById('roomMeasuredTime');
  const temperature = doc.getElementById('roomTemperature'), humidity = doc.getElementById('roomHumidity');
  const serviceWrap = service.parentElement;
  const formatter = new Intl.DateTimeFormat('zh-CN', {hour:'2-digit', minute:'2-digit', hour12:false});
  let data, direct, availability, ageTimer, pollTimer, inFlight, displayed;
  root.DreamShieldRoomStatus.readingFor = deviceUrl =>
    availability === true && cleanUrl(deviceUrl) === cleanUrl(data?.config?.device_base_url) ? displayed : null;
  const setText = (node, value) => { if (node.textContent !== value) node.textContent = value; };
  const setTone = (node, value) => { if (node.getAttribute('data-tone') !== value) node.setAttribute('data-tone', value); };

  function paint() {
    if (ageTimer) clearTimeout(ageTimer);
    if (doc.hidden) return;
    const now = Date.now();
    const view = environmentState(data, direct, now);
    if (availability === false) {
      Object.assign(view, {temperature:'—', humidity:'—', label:'读数不可用', tone:'neutral', measuredAt:null, nextChange:0});
    }
    displayed = view;
    setText(temperature, view.temperature);
    setText(humidity, view.humidity);
    setText(source, view.label);
    setTone(source.parentElement, view.tone);
    const sampledTime = view.measuredAt === null ? '' : formatter.format(view.measuredAt);
    view.detail = 'SHT31 · ' + view.label + (sampledTime ? ' · 采样 ' + sampledTime : '');
    setText(time, sampledTime);
    const iso = view.measuredAt === null ? '' : new Date(view.measuredAt).toISOString();
    if (time.dateTime !== iso) {
      time.dateTime = iso;
      time.title = iso ? '采样时间 ' + new Date(view.measuredAt).toLocaleString('zh-CN', {hour12:false}) : '';
    }
    const serviceLabel = availability === undefined ? '正在读取服务' : availability ? '电脑服务运行中' : '电脑服务暂不可用';
    setText(service, serviceLabel);
    setTone(serviceWrap, availability === undefined ? 'neutral' : availability ? 'ok' : 'error');
    if (view.nextChange > now) ageTimer = setTimeout(paint, view.nextChange - now);
  }

  function update(ok, snapshot) {
    availability = ok;
    if (ok) data = snapshot;
    else { data = null; direct = null; }
    paint();
  }
  root.addEventListener('dreamshield:status', e => update(e.detail?.available === true, e.detail?.data));
  root.addEventListener('dreamshield:device-reading', e => {
    const {status, deviceUrl, requestStart} = e.detail || {};
    const env = status?.environment;
    const elapsed = performance.now() - requestStart;
    const directAge = env?.sample_age_ms + elapsed;
    const valid = env?.sensor_model === 'sht31' && env.input_origin === 'sensor' && env.status === 'fresh'
      && env.valid === true && hasReadings(env) && finiteBetween(elapsed, 0, freshMs)
      && finiteBetween(env.sample_age_ms, 0, freshMs) && finiteBetween(directAge, 0, freshMs);
    const receivedAt = Date.now();
    direct = {valid, environment:env, deviceUrl:cleanUrl(deviceUrl), receivedAt, measuredAt:receivedAt - directAge,
      issue:status ? 'sensor_unavailable' : 'device_unreachable'};
    paint();
  });

  // Core console and thermal setup already request this snapshot. Only the other
  // standalone tools need their own low-frequency, visible-window read.
  const standalone = bar.getAttribute('data-feed') === 'standalone';
  async function poll() {
    clearTimeout(pollTimer);
    if (!standalone || doc.hidden || inFlight) return;
    const controller = new AbortController();
    inFlight = controller;
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch('/api/app/status', {signal:controller.signal, cache:'no-store', credentials:'same-origin'});
      if (!response.ok) throw Error('Status unavailable');
      const snapshot = await response.json();
      if (inFlight === controller) update(snapshot.ok === true, snapshot);
    } catch {
      if (inFlight === controller) update(false);
    } finally {
      clearTimeout(timeout);
      if (inFlight === controller) {
        inFlight = null;
        if (!doc.hidden) pollTimer = setTimeout(poll, 15000);
      }
    }
  }
  function pause() {
    clearTimeout(ageTimer); clearTimeout(pollTimer);
    const pending = inFlight; inFlight = null; pending?.abort();
  }
  function resume() { paint(); if (standalone) void poll(); }
  doc.addEventListener('visibilitychange', () => doc.hidden ? pause() : resume());
  root.addEventListener('pagehide', pause);
  root.addEventListener('pageshow', event => { if (event.persisted) resume(); });
  resume();
})(globalThis);
