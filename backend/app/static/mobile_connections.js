// Owner console presentation. Loading this module never pairs/revokes a phone,
// starts monitoring, sends infrared, or uploads an image.
(function(root){
  'use strict';
  function activityText(session) {
    if (session.recently_active) return '近期有访问';
    return session.last_seen_at ? '最近访问 '+new Date(session.last_seen_at).toLocaleString('zh-CN',{hour12:false}) : '尚无本次服务访问记录';
  }
  function invitationSeconds(info,now) {
    if(!info?.enabled||!info?.pairing_available||!info.pairing_expires_at)return 0;
    const deadline=Date.parse(info.pairing_expires_at);
    return Number.isFinite(deadline)?Math.max(0,Math.ceil((deadline-now)/1000)):0;
  }
  if(typeof module!=='undefined'&&module.exports)module.exports={activityText,invitationSeconds};
  const doc=root.document;if(!doc)return;
  const $=id=>doc.getElementById(id);
  function text(id,value) {
    const node=$(id);
    if(node.textContent!==value)node.textContent=value;
  }
  root.addEventListener('dreamshield:status',event=>{
    const ok=event.detail?.available===true;
    text('consoleServerStatus',ok?'电脑服务运行中':'电脑服务暂不可用');
    $('consoleServerStatus').classList.toggle('ready',ok);
    text('sidebarServiceStatus',ok?'电脑服务运行中':'请检查本地服务');
    doc.querySelector('.service-indicator')?.classList.toggle('ready',ok);
  });
  for(const link of doc.querySelectorAll('[data-console-nav]'))link.title=link.textContent.trim();
  // Reflow DOM as well as pixels so narrow-screen keyboard/readout order
  // follows camera, environment, tasks and results rather than an entire column.
  if(root.matchMedia&&doc.body.getAttribute('data-console-page')==='home'){
    const media=root.matchMedia('(max-width:760px)');
    let compact;
    const reflow=()=>{
      if(compact===media.matches)return;compact=media.matches;
      const primary=doc.querySelector('.home-primary'),secondary=doc.querySelector('.home-secondary');
      const environment=doc.querySelector('.environment-overview');
      if(!primary||!secondary||!environment)return;
      const focus=doc.activeElement;
      const items=compact?['cameraSection',environment,'scheduleSection','automaticAiResultSection','automaticAiSection','acControlSection','gallerySection']:['cameraSection','automaticAiResultSection','gallerySection'];
      for(const item of items)primary.appendChild(typeof item==='string'?$(item):item);
      if(!compact)for(const item of [environment,$('scheduleSection'),$('automaticAiSection'),$('acControlSection')])secondary.appendChild(item);
      if(focus&&focus!==doc.body&&focus.isConnected)focus.focus({preventScroll:true});
    };
    reflow();media.addEventListener('change',reflow);
  }
  if(doc.body.getAttribute('data-console-page')!=='connections')return;
  let current=null,busy=false,armed='',lastSessions='';
  const feedback=text=>{$('mobileConnectionFeedback').textContent=text;};
  async function request(path,body){
    const response=await fetch(path,{cache:'no-store',...(body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})});
    const data=await response.json();
    if(!response.ok)throw Error(data.error||'操作没有完成，请稍后重试。');
    return data;
  }
  function lock(value){
    busy=value;
    $('generateMobileCode').disabled=value||!current?.enabled;
    $('refreshMobileConnections').disabled=value;
    $('mobileAddress').disabled=value||!(current?.urls||[]).length;
    $('generateMobileCode').textContent=value?'正在处理…':'生成连接码';
    for(const button of $('mobileSessionList').querySelectorAll('button'))button.disabled=value;
    $('copyMobileCode').disabled=value||!$('mobileConnectionCode').value;
  }
  function invitation(){
    const seconds=invitationSeconds(current,Date.now());
    const option=seconds?(current.connection_options||[]).find(o=>o.base_url===$('mobileAddress').value):null;
    const code=option?.connection_code||'';
    if($('mobileConnectionCode').value!==code)$('mobileConnectionCode').value=code;
    $('copyMobileCode').disabled=busy||!code;
    const qr=option?.qr_svg_data_url||'';
    if(qr.startsWith('data:image/svg+xml;base64,')){
      if($('mobileConnectionQr').getAttribute('src')!==qr)$('mobileConnectionQr').src=qr;
      $('mobileConnectionQr').hidden=false;$('mobileQrEmpty').hidden=true;
    }else{
      $('mobileConnectionQr').hidden=true;$('mobileConnectionQr').removeAttribute('src');$('mobileQrEmpty').hidden=false;
    }
    text('mobileInvitationState',seconds?`连接码 ${Math.floor(seconds/60)} 分 ${seconds%60} 秒后失效，仅用于新增配对。`:'连接码仅用于新增配对，5 分钟内有效。已连接手机保持连接。');
  }
  function element(tag,className,text){const el=doc.createElement(tag);if(className)el.className=className;if(text!==undefined)el.textContent=text;return el;}
  function sessions(items,force=false){
    const signature=JSON.stringify(items);
    if(!force&&(armed||signature===lastSessions))return;
    lastSessions=signature;
    const list=$('mobileSessionList');list.replaceChildren();$('mobileSessionCount').textContent=String(items.length);
    if(!items.length){const empty=element('div','phone-empty');empty.append(element('strong','','还没有已授权的手机'),element('p','','生成连接码，在手机 APP 中配对。连接成功后会显示在这里。'));list.append(empty);return;}
    for(const item of items){
      const row=element('article','phone-entry');const head=element('div','phone-entry-header');
      const mark=element('span','phone-entry-icon');
      mark.innerHTML='<svg viewBox="0 0 24 28" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true"><rect x="5" y="2" width="14" height="24" rx="3"/><path d="M10 5h4M11 23h2"/></svg>';
      const name=element('div');name.append(element('p','phone-entry-name',item.device_name||'Android 手机'),element('p','phone-entry-status',activityText(item)));
      const actions=element('div','phone-entry-actions');const disconnect=element('button','button','断开');disconnect.type='button';disconnect.setAttribute('aria-label','断开 '+(item.device_name||'Android 手机'));
      disconnect.disabled=busy||!/^[0-9a-f]{24}$/.test(item.session_id||'');
      disconnect.addEventListener('click',()=>{armed=item.session_id;sessions(current.sessions||[],true);});actions.append(disconnect);
      head.append(mark,name,actions);row.append(head);
      const created=item.created_at?new Date(item.created_at).toLocaleString('zh-CN',{hour12:false}):'—';
      row.append(element('p','phone-entry-meta','连接于 '+created+' · 手动断开前持续有效'));
      if(armed===item.session_id){
        const confirmation=element('div','phone-entry-confirm');confirmation.append(element('p','','断开后，这台手机需要重新配对才能访问电脑。'));
        const confirm=element('button','button danger','确认断开');confirm.type='button';confirm.disabled=busy;
        const cancel=element('button','button','取消');cancel.type='button';cancel.disabled=busy;
        confirm.addEventListener('click',()=>mutate('/api/mobile/sessions/revoke',{session_id:item.session_id},'已断开该手机，其他手机和电脑任务保持运行。'));
        cancel.addEventListener('click',()=>{armed='';sessions(current.sessions||[],true);});
        const buttons=element('div','button-row');buttons.append(confirm,cancel);confirmation.append(buttons);row.append(confirmation);
      }
      list.append(row);
    }
  }
  function render(info){
    current=info;const selected=$('mobileAddress').value;
    const urls=info.urls||[];
    const options=[...$('mobileAddress').options].map(o=>o.value);
    if(JSON.stringify(options)!==JSON.stringify(urls)){
      $('mobileAddress').replaceChildren();
      for(const url of urls){const option=element('option','',url);option.value=url;$('mobileAddress').append(option);}
      if(urls.includes(selected))$('mobileAddress').value=selected;
    }
    $('mobileServiceBadge').textContent=info.enabled?'服务已就绪':'手机服务未启动';
    sessions(info.sessions||[]);invitation();lock(busy);
    if(!info.enabled)feedback('手机服务未启动，请使用电脑端手机连接启动脚本后重试。');
    else if(!urls.length)feedback('未发现局域网地址，请检查电脑的 Wi-Fi 或网线连接。');
  }
  async function refresh(){if(busy)return;try{render(await request('/api/mobile/setup'));}catch(error){feedback('无法读取连接状态：'+error.message);}}
  async function mutate(path,body,message){
    if(busy)return;lock(true);feedback('正在处理…');
    try{const data=await request(path,body);armed='';lastSessions='';render(data);feedback(message);}
    catch(error){feedback(error.message);}finally{lock(false);}
  }
  $('generateMobileCode').addEventListener('click',()=>mutate('/api/mobile/pairing/renew',{},'新的连接码已生成，已连接的手机不受影响。'));
  $('refreshMobileConnections').addEventListener('click',()=>{armed='';lastSessions='';void refresh();});
  $('mobileAddress').addEventListener('change',invitation);
  $('copyMobileCode').addEventListener('click',async()=>{
    if(!$('mobileConnectionCode').value)return;
    try{await root.navigator.clipboard.writeText($('mobileConnectionCode').value);feedback('连接码已复制，请在手机 APP 的“连接电脑”页面粘贴。');}
    catch(_){$('mobileConnectionCode').focus();$('mobileConnectionCode').select();feedback('连接码已选中，请按 Ctrl+C 复制。');}
  });
  void refresh();root.setInterval(()=>{if(!doc.hidden)void refresh();},10000);
  root.setInterval(()=>{if(!doc.hidden && current?.pairing_available)invitation();},1000);
})(globalThis);
