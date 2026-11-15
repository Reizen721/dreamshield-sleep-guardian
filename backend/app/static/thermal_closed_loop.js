/* Read-only polling; only an explicit form submission authorizes the loop. */
(() => {
  'use strict';
  const el = id => document.getElementById(id);
  const states = {stopped:'未开启', waiting_pair:'等待前后帧', reviewing:'VLM 核对中', monitoring:'持续监测',
    waiting_evidence:'等待有效证据', budget_exhausted:'调用预算已用完', source_changed:'机位连接已变化',
    control_paused:'控制已暂停', policy_changed:'策略已更改，请重新开启', error:'运行暂停', server_stopped:'服务已停止'};
  const labels = {covered:'正常覆盖', empty:'无人', uncertain:'证据不足', partially_uncovered:'部分未覆盖',
    uncovered:'大面积未覆盖', uncovered_candidate:'未覆盖待确认', confirmed_uncovered:'持续未覆盖'};
  const reasons = {automatic_disabled:'未启用空调会话或自动控制', environment_unavailable_or_expired:'温湿度不可用或已过期',
    no_reliable_occupied_coverage:'等待可靠的有人覆盖状态', within_band_no_change:'室温在区间内，保持不调节',
    automatic_command_cooldown:'正在冷却期，避免连续调节', coverage_event_already_handled:'本次未覆盖事件已处理，不重复升温',
    uncovered_but_not_cool_no_automatic_warming:'未覆盖，但室温不偏低，无需升温',
    collecting_environment_persistence:'等待室温条件持续成立', visual_expired_or_missing:'等待新的视觉核对',
    automatic_setpoint_bound_reached:'已达到设定温度边界', coverage_event_recovery_pending:'等待盖被恢复确认',
    outside_experimental_sensor_range:'温湿度超出当前实验范围',
    emission_outcome_unknown_rearm_required:'发送结果未知；不重试，请核对设备后重新开启'};
  let data = null, pending = false, online = false;
  function controls() {
    const running = Boolean(data?.enabled);
    for (const id of ['loopMode','loopPose','loopCloud','loopControl','loopInterval','loopHour','loopSession']) el(id).disabled = pending || running;
    el('loopControlRow').hidden = el('loopMode').value !== 'automatic';
    el('loopStart').disabled = pending || !online || running || Boolean(data?.busy) || !el('loopPose').checked || !el('loopCloud').checked || (el('loopMode').value === 'automatic' && !el('loopControl').checked);
    el('loopStop').disabled = pending || !running;
  }
  function message(text, error=false) { el('loopMessage').textContent=text; el('loopMessage').classList.toggle('error',error); }
  function render(value) {
    data=value; online=true;
    if(value.enabled) { el('loopMode').value=value.mode; el('loopInterval').value=value.review_interval_seconds; el('loopHour').value=value.max_calls_per_hour; el('loopSession').value=value.max_calls_per_session; }
    controls();
    el('loopState').textContent=states[value.state] || value.state;
    const r=value.last_review, a=value.last_action, f=value.feedback, p=value.policy;
    const current=Boolean(value.enabled && value.state==='monitoring' && r && (Date.now()/1000-r.epoch)<=90);
    el('loopVerdict').textContent=current ? labels[r.state] || r.state : value.enabled ? '等待新的联合判断' : '闭环已停止';
    el('loopReason').textContent=value.error || (r ? (current ? '' : '上次记录：')+r.reason : '等待前后热图；单帧本地结果不会直接触发调温。');
    el('loopVlm').textContent=r ? `${new Date(r.captured_at).toLocaleTimeString()} · ${r.review.evidence} · ${r.review.limitations}` : '尚无结果';
    el('loopAction').textContent=a ? a.status==='preview_only' ? `仅建议：设定温度 ${a.state.setpoint_c}℃，未发送` : a.status==='emitted_local_only' ? `已发射 ${a.state.setpoint_c}℃ 指令，空调响应未确认` : reasons[a.reason] || `保持不调节（${a.reason || a.status}）` : '尚未生成建议；需要已配置的空调会话和温湿度';
    if(!current && a) el('loopAction').textContent='上次判断：'+el('loopAction').textContent;
    el('loopFeedback').textContent=f ? `最近发射后 ${f.elapsed_seconds ?? 0} 秒；${f.temperature_change_c == null ? '等待新的传感器样本' : `室温变化 ${f.temperature_change_c > 0 ? '+' : ''}${f.temperature_change_c}℃`}。仅作环境反馈，不是空调回执。` : '尚无发送记录';
    el('loopPolicy').textContent=`室温 ${p.temperature_lower_c}～${p.temperature_upper_c}℃ · 持续 ${p.environment_persistence_seconds} 秒 · 单次 ${p.temperature_step_c}℃ · 冷却 ${p.command_cooldown_seconds} 秒`;
    if(!pending) message(value.error || `本次已调用 ${value.session_calls}/${value.max_calls_per_session} 次。${value.enabled ? '关闭页面仍继续运行；停止按钮可撤销后续调节。' : '默认关闭，不会自动恢复上传或调温。'}`,Boolean(value.error));
  }
  async function request(body) {
    const controller = new AbortController(), timer=setTimeout(()=>controller.abort(),12000);
    try {
      const r=await fetch('/api/thermal-loop/'+(body ? 'config':'status'),{method:body?'POST':'GET',cache:'no-store',signal:controller.signal,
        headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined});
      const result=await r.json(); if(!r.ok || !result.ok) throw new Error(result.error || '请求未完成');
      return result.thermal_loop;
    } finally {clearTimeout(timer);}
  }
  async function change(body) {
    pending=true;controls();message(body.enabled?'正在开启联合闭环…':'正在停止闭环…');
    try {render(await request(body));message(body.enabled?'已开启；等待热像帧和 VLM 核对。':'已停止；正在处理的 VLM 回复不会触发指令。');}
    catch(e) {message(e.name==='AbortError'?'请求超时，请检查服务状态；正在重新读取。':e.message,true);}
    finally {pending=false;controls();}
  }
  el('loopForm').addEventListener('submit',event=>{event.preventDefault();if(el('loopStart').disabled)return;
    change({enabled:true,mode:el('loopMode').value,calibration_confirmed:el('loopPose').checked,
      cloud_upload_consent:el('loopCloud').checked,control_consent:el('loopControl').checked,
      review_interval_seconds:Number(el('loopInterval').value),max_calls_per_hour:Number(el('loopHour').value),max_calls_per_session:Number(el('loopSession').value)});
  });
  el('loopStop').addEventListener('click',()=>change({enabled:false}));
  for(const id of ['loopMode','loopPose','loopCloud','loopControl'])el(id).addEventListener('change',controls);
  async function poll(){
    try {const v=await request();if(!pending)render(v);}
    catch(e){online=false;controls();el('loopState').textContent='服务连接中断';el('loopVerdict').textContent='当前判断不可用';el('loopReason').textContent='等待电脑服务恢复；网络断开不代表后台任务已停止。';el('loopAction').textContent='当前调节状态不可用';el('loopFeedback').textContent='当前环境反馈不可用';message('无法读取闭环状态，正在重连。',true);}
    finally{setTimeout(poll,document.hidden?5000:2000);}
  }
  controls();poll();
})();
