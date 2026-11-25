#pragma once

namespace webui {

// Stored in Flash (PROGMEM-equivalent const char[]) so RAM & streaming FPS overhead is 0%.
static constexpr char kIndexHtml[] = R"HTML(<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>dockycam</title>
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='16' fill='%230B0F0E'/%3E%3Ccircle cx='32' cy='32' r='20' fill='%23121A18' stroke='%232CC295' stroke-width='3'/%3E%3Ccircle cx='32' cy='32' r='11' fill='%2318382E' stroke='%236EE7B7' stroke-width='2'/%3E%3Ccircle cx='32' cy='32' r='5' fill='%232CC295'/%3E%3Ccircle cx='49' cy='15' r='4' fill='%23F87171'/%3E%3C/svg%3E">
<style>
:root{--bg:#0b0f0e;--sf:#131a18;--sf2:#1a2421;--bd:#24322e;--tx:#eef4f1;--mt:#8ba097;--ac:#2cc295;--ac2:#12382c;--rd:#f87171;--rd2:#3a191b;--am:#f59e0b;--cy:#38bdf8}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--tx);font-family:system-ui,-apple-system,sans-serif;line-height:1.45}
.wrap{max-width:960px;margin:0 auto;padding:18px 14px 48px}
.top{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:14px}
.brand{display:flex;align-items:center;gap:10px}
.logo{width:34px;height:34px;flex-shrink:0}
h1{margin:0;font-size:21px;letter-spacing:-.02em}
.pill{display:inline-flex;align-items:center;gap:7px;padding:6px 12px;border-radius:99px;background:var(--sf);border:1px solid var(--bd);font-size:12.5px;color:var(--mt)}
.dot{width:8px;height:8px;border-radius:50%;background:var(--ac)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:12px;margin-bottom:12px}
.card{background:var(--sf);border:1px solid var(--bd);border-radius:16px;padding:15px;margin-bottom:12px}
.card h2{margin:0 0 10px;font-size:14.5px;color:var(--tx);display:flex;align-items:center;justify-content:space-between;gap:8px}
.meta{font-size:12.5px;color:var(--mt);margin:4px 0}
.bar{height:8px;background:var(--bg);border-radius:99px;overflow:hidden;border:1px solid var(--bd);margin:8px 0}
.bar>div{height:100%;background:var(--ac);transition:width .2s}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
button,a.btn{cursor:pointer;border:1px solid var(--bd);background:var(--sf2);color:var(--tx);padding:8px 12px;border-radius:10px;font-size:13px;font-weight:600;text-decoration:none;display:inline-flex;align-items:center;gap:6px}
button:hover,a.btn:hover{border-color:var(--ac)}
button:disabled{opacity:.5;cursor:not-allowed}
button.pri{background:var(--ac);color:#062319;border-color:var(--ac)}
button.red{background:var(--rd2);color:var(--rd);border-color:#5b2427}
.tabs{display:flex;gap:6px;flex-wrap:wrap;margin:10px 0 12px}
.tabs button.on{background:var(--ac);color:#062319;border-color:var(--ac)}
.file{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 12px;border-radius:12px;background:var(--bg);border:1px solid var(--bd);margin-bottom:8px;flex-wrap:wrap}
.tag{font-size:11px;font-weight:700;padding:2px 8px;border-radius:99px;background:var(--ac2);color:var(--ac);margin-right:6px}
.tag.blackbox{background:var(--rd2);color:var(--rd)}
.tag.timelapse{background:#3a2b10;color:var(--am)}
.tag.shared{background:#122b3a;color:var(--cy)}
#liveBox{display:none;margin-top:12px;background:#050807;border-radius:12px;overflow:hidden;border:1px solid var(--bd);text-align:center}
#liveImg{max-width:100%;max-height:65vh;display:block;margin:0 auto}
.drop{border:2px dashed var(--bd);border-radius:12px;padding:16px;text-align:center;background:var(--bg);transition:border-color .15s,background .15s;cursor:pointer}
.drop.over{border-color:var(--ac);background:var(--ac2)}
.inp,textarea{width:100%;background:var(--bg);color:var(--tx);border:1px solid var(--bd);border-radius:10px;padding:9px 11px;font:inherit;font-size:13.5px;outline:none}
.inp:focus,textarea:focus{border-color:var(--ac)}
textarea{min-height:74px;resize:vertical}
.note{padding:11px 12px;border-radius:12px;background:var(--bg);border:1px solid var(--bd);margin-top:8px}
.note-h{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:6px;flex-wrap:wrap}
.note-b{white-space:pre-wrap;word-break:break-word;font-size:13.5px;color:#dbe7e2;margin:0}
.note-b a{color:var(--cy);text-decoration:underline}
</style>
</head>
<body>
<div class="wrap">
  <div class="top">
    <div class="brand">
      <svg class="logo" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg">
        <rect width="64" height="64" rx="16" fill="#131A18" stroke="#24322E" stroke-width="2"/>
        <circle cx="32" cy="32" r="19" fill="#0B0F0E" stroke="#2CC295" stroke-width="3"/>
        <circle cx="32" cy="32" r="10.5" fill="#12382C" stroke="#6EE7B7" stroke-width="2"/>
        <circle cx="32" cy="32" r="4.5" fill="#2CC295"/>
        <circle cx="28" cy="27" r="2.2" fill="#FFFFFF" fill-opacity="0.8"/>
        <circle cx="49" cy="15" r="4" fill="#F87171"/>
      </svg>
      <h1>dockycam</h1>
    </div>
    <div class="row">
      <span class="pill"><span class="dot" id="stDot"></span><span id="stTxt">연결 확인 중...</span></span>
      <button onclick="refreshAll()">새로고침</button>
    </div>
  </div>

  <div class="grid">
    <div class="card" style="margin-bottom:0">
      <h2>보드 · 네트워크</h2>
      <div class="meta" id="netMeta">불러오는 중...</div>
      <div class="meta" id="telMeta"></div>
      <div class="row" style="margin-top:10px">
        <button id="ledBtn" onclick="toggleLed()">LED 전환</button>
        <button onclick="toggleLive()" id="liveToggle">실시간 영상 열기</button>
      </div>
    </div>
    <div class="card" style="margin-bottom:0">
      <h2>SD카드 · 카메라 녹화</h2>
      <div class="meta" id="sdMeta">SD카드 확인 중...</div>
      <div class="bar"><div id="sdFill" style="width:0%"></div></div>
      <div class="row" style="margin-top:10px">
        <button class="pri" id="bbBtn" onclick="toggleRec('blackbox')">블랙박스 시작</button>
        <button id="tlBtn" onclick="toggleRec('timelapse')">타임랩스 시작</button>
        <button onclick="snap()">스냅샷</button>
      </div>
    </div>
  </div>

  <div class="card" id="liveCard" style="display:none;margin-top:12px">
    <h2>실시간 미리보기</h2>
    <div id="liveBox" style="display:block"><img id="liveImg" alt="live stream"></div>
  </div>

  <div class="grid" style="margin-top:12px">
    <div class="card" style="margin-bottom:0">
      <h2>간이 클라우드 업로드 <span class="meta" style="margin:0;font-weight:400">/dockycam/shared</span></h2>
      <div class="drop" id="dropZone" onclick="$('filePick').click()">
        <div style="font-weight:700;font-size:14px">파일을 여기에 끌어다 놓거나 클릭해서 선택</div>
        <div class="meta" style="margin-top:4px">전송 중에는 실시간 영상이 자동 정지되어 최대 속도로 전송됩니다.<br>공유함 파일은 블랙박스 순환 삭제에서 보호됩니다.</div>
      </div>
      <input id="filePick" type="file" multiple style="display:none" onchange="uploadFiles(this.files);this.value=''">
      <div id="upBox" style="display:none;margin-top:10px">
        <div class="meta" id="upTxt">전송 준비 중...</div>
        <div class="bar"><div id="upFill" style="width:0%"></div></div>
      </div>
    </div>

    <div class="card" style="margin-bottom:0">
      <h2>빠른 메모 · 링크 공유판 <span class="meta" id="noteCnt" style="margin:0;font-weight:400"></span></h2>
      <input class="inp" id="noteTitle" placeholder="제목 (선택, 예: 작업 링크 / 메모)" maxlength="80" style="margin-bottom:8px">
      <textarea id="noteText" placeholder="기기 간 주고받을 메모나 URL을 입력하세요 (Ctrl+Enter 저장)" maxlength="3800"></textarea>
      <div class="row" style="margin-top:8px;justify-content:flex-end">
        <button id="noteCancelBtn" style="display:none" onclick="cancelEditNote()">취소</button>
        <button class="pri" id="noteSaveBtn" onclick="saveNote()">메모 저장</button>
      </div>
      <div id="notesList"></div>
    </div>
  </div>

  <div class="card" style="margin-top:12px">
    <div class="row" style="justify-content:space-between">
      <h2 style="margin:0">SD카드 파일 관리</h2>
      <span class="meta" id="fileSum"></span>
    </div>
    <div class="tabs">
      <button class="on" onclick="setCat('all',this)">전체</button>
      <button onclick="setCat('shared',this)">공유함</button>
      <button onclick="setCat('blackbox',this)">블랙박스</button>
      <button onclick="setCat('timelapse',this)">타임랩스</button>
      <button onclick="setCat('snapshots',this)">스냅샷</button>
    </div>
    <div id="files">불러오는 중...</div>
  </div>
</div>
<script>
let curCat='all', sdState=null, wifiState=null, liveOn=false, uploading=false, editNoteId=null, notesCache=[];
const $=id=>document.getElementById(id);
const esc=s=>String(s||'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
const linkify=s=>esc(s).replace(/(https?:\/\/[^\s<]+)/g,'<a href="$1" target="_blank" rel="noopener">$1</a>');
const fmtSz=b=>b>=1073741824?(b/1073741824).toFixed(2)+' GB':b>=1048576?(b/1048576).toFixed(1)+' MB':b>=1024?Math.round(b/1024)+' KB':b+' B';
const fmtMb=m=>m>=1024?(m/1024).toFixed(1)+' GB':m+' MB';
const fmtTitle=n=>{const m=n.match(/^[A-Z]+_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})\./);return m?`${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}`:n};
const fmtTs=ts=>{if(!ts||ts<1700000000)return '';const d=new Date(ts*1000);const p=v=>String(v).padStart(2,'0');return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;};
const catKo=c=>c==='shared'?'공유함':c==='blackbox'?'블랙박스':c==='timelapse'?'타임랩스':c==='snapshots'?'스냅샷':c;

async function postJson(u,obj){
  const r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:obj==null?'':JSON.stringify(obj)});
  if(!r.ok) throw new Error(r.status);
  return r.status===204?null:r.json();
}
function stopLiveIfNeeded(){
  if(!liveOn) return;
  liveOn=false;
  $('liveCard').style.display='none';
  $('liveToggle').textContent='실시간 영상 열기';
  $('liveImg').src='';
  postJson('/stop',null).catch(()=>{});
}
function toggleLive(){
  if(uploading){alert('파일 전송이 끝난 뒤 실시간 영상을 열 수 있습니다.');return;}
  liveOn=!liveOn;
  $('liveCard').style.display=liveOn?'block':'none';
  $('liveToggle').textContent=liveOn?'실시간 영상 닫기':'실시간 영상 열기';
  if(liveOn){
    $('liveImg').src=`http://${location.hostname}:81/stream?t=${Date.now()}`;
  }else{
    $('liveImg').src='';
    postJson('/stop',null).catch(()=>{});
  }
}
async function toggleRec(mode){
  const next=(sdState && sdState.mode===mode)?'off':mode;
  await postJson('/sd/record',{mode:next});
  await loadStatus();
}
async function snap(){
  await postJson('/sd/snapshot',null);
  setTimeout(loadFiles,500);
}
async function toggleLed(){
  const next=!(wifiState && wifiState.led_enabled);
  await postJson('/led',{enabled:next});
  await loadStatus();
}
function setCat(c,btn){
  curCat=c;
  document.querySelectorAll('.tabs button').forEach(b=>b.classList.remove('on'));
  btn.classList.add('on');
  loadFiles();
}
async function delFile(cat,name){
  if(!confirm(name+' 파일을 삭제할까요?')) return;
  await postJson('/sd/delete',{cat,name});
  loadFiles();loadStatus();
}
function uploadOne(file,idx,total){
  return new Promise((resolve,reject)=>{
    const xhr=new XMLHttpRequest();
    const t0=Date.now();
    xhr.open('POST','/sd/upload?name='+encodeURIComponent(file.name));
    xhr.upload.onprogress=e=>{
      if(!e.lengthComputable) return;
      const pct=Math.min(100,Math.round(e.loaded*100/e.total));
      const sec=Math.max(0.2,(Date.now()-t0)/1000);
      const spd=fmtSz(e.loaded/sec)+'/s';
      $('upFill').style.width=pct+'%';
      $('upTxt').textContent=`[${idx}/${total}] ${file.name} · ${pct}% (${fmtSz(e.loaded)} / ${fmtSz(e.total)} · ${spd})`;
    };
    xhr.onload=()=>{
      if(xhr.status>=200 && xhr.status<300) resolve();
      else reject(new Error(xhr.responseText || ('HTTP '+xhr.status)));
    };
    xhr.onerror=()=>reject(new Error('네트워크 오류'));
    xhr.send(file);
  });
}
async function uploadFiles(fileList){
  const list=Array.from(fileList||[]);
  if(!list.length || uploading) return;
  stopLiveIfNeeded();
  uploading=true;
  $('upBox').style.display='block';
  let ok=0;
  try{
    for(let i=0;i<list.length;i++){
      $('upFill').style.width='0%';
      $('upTxt').textContent=`[${i+1}/${list.length}] ${list[i].name} 전송 시작...`;
      await uploadOne(list[i],i+1,list.length);
      ok++;
    }
    $('upFill').style.width='100%';
    $('upTxt').textContent=`완료! ${ok}개 파일을 공유함(/dockycam/shared)에 저장했습니다.`;
  }catch(e){
    $('upTxt').textContent='업로드 실패: '+(e.message||e);
  }finally{
    uploading=false;
    loadFiles();loadStatus();
  }
}
const dz=$('dropZone');
['dragenter','dragover'].forEach(ev=>dz.addEventListener(ev,e=>{e.preventDefault();dz.classList.add('over');}));
['dragleave','drop'].forEach(ev=>dz.addEventListener(ev,e=>{e.preventDefault();dz.classList.remove('over');}));
dz.addEventListener('drop',e=>{if(e.dataTransfer&&e.dataTransfer.files)uploadFiles(e.dataTransfer.files);});

function copyText(txt,btn){
  const done=()=>{if(btn){const o=btn.textContent;btn.textContent='복사됨';setTimeout(()=>btn.textContent=o,1200);}};
  if(navigator.clipboard && window.isSecureContext){
    navigator.clipboard.writeText(txt).then(done).catch(()=>fallbackCopy(txt,done));
  }else{
    fallbackCopy(txt,done);
  }
}
function fallbackCopy(txt,cb){
  const ta=document.createElement('textarea');
  ta.value=txt;ta.style.position='fixed';ta.style.opacity='0';
  document.body.appendChild(ta);ta.select();
  try{document.execCommand('copy');}catch(e){}
  document.body.removeChild(ta);cb();
}
function renderNotes(list){
  notesCache=list||[];
  $('noteCnt').textContent=notesCache.length?notesCache.length+'개':'';
  if(!notesCache.length){
    $('notesList').innerHTML='<div class="meta" style="margin-top:10px">저장된 메모가 없습니다.</div>';
    return;
  }
  $('notesList').innerHTML=notesCache.map((n,i)=>`<div class="note"><div class="note-h"><div><strong>${esc(n.title||'메모')}</strong> <span class="meta">${esc(fmtTs(n.ts))}</span></div><div class="row"><button onclick="copyText(notesCache[${i}].text||notesCache[${i}].title,this)">복사</button><button onclick="startEditNote(${i})">수정</button><button class="red" onclick="deleteNote(${n.id})">삭제</button></div></div><div class="note-b">${linkify(n.text||'')}</div></div>`).join('');
}
function startEditNote(idx){
  const n=notesCache[idx]; if(!n) return;
  editNoteId=n.id;
  $('noteTitle').value=n.title||'';
  $('noteText').value=n.text||'';
  $('noteSaveBtn').textContent='수정 저장';
  $('noteCancelBtn').style.display='inline-flex';
  $('noteText').focus();
}
function cancelEditNote(){
  editNoteId=null;
  $('noteTitle').value='';$('noteText').value='';
  $('noteSaveBtn').textContent='메모 저장';
  $('noteCancelBtn').style.display='none';
}
async function loadNotes(){
  try{
    const d=await fetch('/sd/notes').then(r=>r.json());
    renderNotes(d.notes||[]);
  }catch(e){}
}
async function saveNote(){
  const title=$('noteTitle').value.trim(), text=$('noteText').value.trim();
  if(!title && !text) return;
  const payload=editNoteId!=null?{action:'update',id:editNoteId,title,text}:{action:'add',title,text};
  const d=await postJson('/sd/notes',payload);
  cancelEditNote();
  renderNotes(d.notes||[]);
}
async function deleteNote(id){
  if(!confirm('이 메모를 삭제할까요?')) return;
  const d=await postJson('/sd/notes',{action:'delete',id});
  renderNotes(d.notes||[]);
}
$('noteText').addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='Enter')saveNote();});

async function loadStatus(){
  if(uploading) return;
  try{
    const [w,s,t]=await Promise.all([
      fetch('/wifi/status').then(r=>r.json()),
      fetch('/sd/status').then(r=>r.json()),
      fetch('/telemetry').then(r=>r.json())
    ]);
    wifiState=w; sdState=s;
    $('netMeta').textContent=`${w.mode==='sta'?'공유기(STA)':'직결 AP'} · ${w.ssid} · ${w.ip} (ch ${w.channel})`;
    $('telMeta').textContent=`칩 온도 ${t.chip_temp_c!=null?t.chip_temp_c.toFixed(1)+' °C':'-'} · 신호 ${w.mode==='sta'?w.rssi+' dBm':'AP'} · LED ${w.led_enabled?'켜짐':'꺼짐(스텔스)'}`;
    $('ledBtn').textContent=w.led_enabled?'LED 끄기 (스텔스)':'LED 켜기';
    if(!s.mounted){
      $('sdMeta').textContent='SD카드 미인식';
    }else{
      const pct=s.total_mb?Math.min(100,Math.round(s.used_mb*100/s.total_mb)):0;
      $('sdFill').style.width=pct+'%';
      $('sdMeta').textContent=`여유 ${fmtMb(s.free_mb)} / 전체 ${fmtMb(s.total_mb)}`+(s.active?` · ${catKo(s.mode)} 중 (${s.frames}프레임)`:'');
    }
    $('bbBtn').textContent=s.mode==='blackbox'?'블랙박스 중지':'블랙박스 시작';
    $('bbBtn').className=s.mode==='blackbox'?'red':'pri';
    $('tlBtn').textContent=s.mode==='timelapse'?'타임랩스 중지':'타임랩스 시작';
    $('stTxt').textContent=s.active?catKo(s.mode)+' 중':(w.mode==='sta'?'공유기 연결됨':'AP 대기');
    $('stDot').style.background=s.active?'var(--red)':'var(--ac)';
  }catch(e){}
}
async function loadFiles(){
  try{
    const d=await fetch('/sd/files?cat='+encodeURIComponent(curCat)).then(r=>r.json());
    const list=d.files||[];
    const tot=list.reduce((a,f)=>a+(f.size||0),0);
    $('fileSum').textContent=`${list.length}개 · ${fmtSz(tot)}`;
    if(!list.length){$('files').innerHTML='<div class="meta">저장된 파일이 없습니다.</div>';return;}
    $('files').innerHTML=list.map((f,i)=>{
      const q=`cat=${encodeURIComponent(f.cat)}&name=${encodeURIComponent(f.name)}`;
      return `<div class="file"><div><div><span class="tag ${esc(f.cat)}">${esc(catKo(f.cat))}</span><strong>${esc(fmtTitle(f.name))}</strong></div><div class="meta">${esc(f.name)} · ${fmtSz(f.size)}</div></div><div class="row"><a class="btn" href="/sd/file?${q}" download="${esc(f.name)}" onclick="stopLiveIfNeeded()">다운로드</a><button class="red" onclick="delFile('${esc(f.cat)}',decodeURIComponent('${encodeURIComponent(f.name)}'))">삭제</button></div></div>`;
    }).join('');
  }catch(e){$('files').textContent='목록을 불러올 수 없습니다.';}
}
function refreshAll(){loadStatus();loadFiles();loadNotes();}
refreshAll();
setInterval(()=>{if(!liveOn && !uploading)loadStatus();},5000);
</script>
</body>
</html>
)HTML";

} // namespace webui
