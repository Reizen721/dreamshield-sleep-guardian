(function (root) {
  'use strict';
  function plain(value, fallback = '—') {
    return typeof value === 'string' && value ? value.slice(0, 4000) : fallback;
  }
  function timeText(value) {
    if (typeof value !== 'string' || !value) return '—';
    const time = new Date(value);
    return Number.isFinite(time.getTime()) ? time.toLocaleString() : '—';
  }
  function sensorFallbackView(resolution, baseUrl, elapsedMs = 0) {
    const observation = resolution?.effective_observation;
    const valid = resolution?.source === 'last_valid' && observation && observation.device_base_url === baseUrl
      && Number.isFinite(observation.ambient_temperature_c) && observation.ambient_temperature_c >= -40 && observation.ambient_temperature_c <= 125
      && Number.isFinite(observation.relative_humidity_percent) && observation.relative_humidity_percent >= 0 && observation.relative_humidity_percent <= 100
      && Number.isFinite(resolution.age_seconds) && resolution.age_seconds >= 0
      && Number.isFinite(elapsedMs) && elapsedMs >= 0;
    if (!valid) return {visible: false, temperature: '—', humidity: '—', detail: ''};
    const age = resolution.age_seconds + elapsedMs / 1000;
    const expired = age > 300 || resolution.control_usable !== true;
    return {visible: true, temperature: `${observation.ambient_temperature_c.toFixed(2)} °C`,
      humidity: `${observation.relative_humidity_percent.toFixed(2)} %RH`,
      detail: `最后有效缓存 · 不是当前实测 · 原采样 ${timeText(resolution.original_measured_at || observation.measured_at_estimated)} · 年龄 ${Math.ceil(age)} 秒 · 故障 ${plain(resolution.fault_reason)}；${expired ? '超过可用时限，仅显示、不供新控制' : '最多沿用 300 秒'}，不形成趋势。`};
  }
  root.DreamShieldSensorFallback = {view: sensorFallbackView};
})(globalThis);
