#pragma once

namespace webui {

// Stored in Flash (PROGMEM-equivalent const char[]) so RAM & streaming FPS overhead is 0%.
static constexpr char kIndexHtml[] = R"HTML(<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#0b0f0e">
<meta name="application-name" content="dockycam">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="dockycam">
<title>dockycam</title>
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='16' fill='%230B0F0E'/%3E%3Ccircle cx='32' cy='32' r='20' fill='%23121A18' stroke='%232CC295' stroke-width='3'/%3E%3Ccircle cx='32' cy='32' r='11' fill='%2318382E' stroke='%236EE7B7' stroke-width='2'/%3E%3Ccircle cx='32' cy='32' r='5' fill='%232CC295'/%3E%3Ccircle cx='49' cy='15' r='4' fill='%23F87171'/%3E%3C/svg%3E">
<link rel="apple-touch-icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='16' fill='%230B0F0E'/%3E%3Ccircle cx='32' cy='32' r='20' fill='%23121A18' stroke='%232CC295' stroke-width='3'/%3E%3Ccircle cx='32' cy='32' r='11' fill='%2318382E' stroke='%236EE7B7' stroke-width='2'/%3E%3Ccircle cx='32' cy='32' r='5' fill='%232CC295'/%3E%3Ccircle cx='49' cy='15' r='4' fill='%23F87171'/%3E%3C/svg%3E">
<style>
:root{--bg:#0b0f0e;--sf:#131a18;--sf2:#1a2421;--bd:#24322e;--tx:#eef4f1;--mt:#8ba097;--ac:#2cc295;--ac2:#12382c;--rd:#f87171;--rd2:#3a191b;--am:#f59e0b;--cy:#38bdf8}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--tx);font-family:system-ui,-apple-system,sans-serif;line-height:1.45}
.wrap{max-width:1000px;margin:0 auto;padding:16px 14px 56px}
.top{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap;margin-bottom:14px}
.brand{display:flex;align-items:center;gap:10px}
.logo{width:34px;height:34px;flex-shrink:0}
h1{margin:0;font-size:21px;letter-spacing:-.02em}
.pill{display:inline-flex;align-items:center;gap:7px;padding:5px 11px;border-radius:99px;background:var(--sf);border:1px solid var(--bd);font-size:12px;color:var(--mt)}
.dot{width:8px;height:8px;border-radius:50%;background:var(--ac)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(285px,1fr));gap:12px;margin-bottom:12px}
.card{background:var(--sf);border:1px solid var(--bd);border-radius:16px;padding:15px;margin-bottom:12px}
.card h2{margin:0 0 10px;font-size:14.5px;color:var(--tx);display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
.meta{font-size:12.5px;color:var(--mt);margin:4px 0}
.bar{height:8px;background:var(--bg);border-radius:99px;overflow:hidden;border:1px solid var(--bd);margin:8px 0}
.bar>div{height:100%;background:var(--ac);transition:width .2s}
.row{display:flex;gap:7px;flex-wrap:wrap;align-items:center}
button,a.btn,select{cursor:pointer;border:1px solid var(--bd);background:var(--sf2);color:var(--tx);padding:7px 11px;border-radius:10px;font-size:12.5px;font-weight:600;text-decoration:none;display:inline-flex;align-items:center;gap:5px}
button:hover,a.btn:hover,select:hover{border-color:var(--ac)}
button:disabled{opacity:.45;cursor:not-allowed}
button.pri{background:var(--ac);color:#062319;border-color:var(--ac)}
button.red{background:var(--rd2);color:var(--rd);border-color:#5b2427}
button.sm,a.btn.sm{padding:5px 9px;font-size:12px;border-radius:8px}
.tabs{display:flex;gap:6px;flex-wrap:wrap;margin:10px 0}
.tabs button.on{background:var(--ac);color:#062319;border-color:var(--ac)}
.file{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 12px;border-radius:12px;background:var(--bg);border:1px solid var(--bd);margin-bottom:7px;flex-wrap:wrap}
.file.dir{cursor:pointer;border-color:#1e3a4a}
.file.dir:hover{border-color:var(--cy)}
.tag{font-size:11px;font-weight:700;padding:2px 7px;border-radius:99px;background:var(--ac2);color:var(--ac);margin-right:6px}
.tag.blackbox{background:var(--rd2);color:var(--rd)}
.tag.timelapse{background:#3a2b10;color:var(--am)}
.tag.shared{background:#122b3a;color:var(--cy)}
.tag.folder{background:#163042;color:#7dd3fc}
#liveBox{background:#050807;border-radius:12px;overflow:hidden;border:1px solid var(--bd);text-align:center}
#liveImg{max-width:100%;max-height:68vh;display:block;margin:0 auto}
.drop{border:2px dashed var(--bd);border-radius:12px;padding:15px;text-align:center;background:var(--bg);transition:border-color .15s,background .15s;cursor:pointer}
.drop.over{border-color:var(--ac);background:var(--ac2)}
.inp,textarea{width:100%;background:var(--bg);color:var(--tx);border:1px solid var(--bd);border-radius:10px;padding:8px 11px;font:inherit;font-size:13px;outline:none}
.inp:focus,textarea:focus{border-color:var(--ac)}
textarea{min-height:70px;resize:vertical}
.note{padding:10px 12px;border-radius:12px;background:var(--bg);border:1px solid var(--bd);margin-top:8px}
.note-h{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:5px;flex-wrap:wrap}
.note-b{white-space:pre-wrap;word-break:break-word;font-size:13px;color:#dbe7e2;margin:0}
.note-b a{color:var(--cy);text-decoration:underline}
.chk{display:inline-flex;align-items:center;gap:5px;font-size:12.5px;color:var(--mt);cursor:pointer;user-select:none}
.chk input{accent-color:var(--ac);width:15px;height:15px;cursor:pointer}
.crumb{display:flex;align-items:center;gap:6px;flex-wrap:wrap;padding:7px 10px;border-radius:10px;background:var(--bg);border:1px solid var(--bd);margin:8px 0;font-size:12.5px}
.crumb a{color:var(--cy);text-decoration:none;font-weight:600;cursor:pointer}
.modal{position:fixed;inset:0;background:rgba(5,9,8,.88);display:none;align-items:center;justify-content:center;padding:16px;z-index:50}
.modal.on{display:flex}
.mcard{background:var(--sf);border:1px solid var(--bd);border-radius:16px;max-width:920px;width:100%;max-height:92vh;display:flex;flex-direction:column;overflow:hidden}
.mhead{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 15px;border-bottom:1px solid var(--bd)}
.mbody{padding:14px;overflow:auto;text-align:center}
.mbody img,.mbody canvas{max-width:100%;max-height:68vh;border-radius:10px;background:#050807}
.mbody pre{text-align:left;white-space:pre-wrap;word-break:break-word;background:var(--bg);border:1px solid var(--bd);border-radius:10px;padding:12px;font-size:12.5px;color:#dbe7e2;margin:0;max-height:66vh;overflow:auto}
details summary{cursor:pointer;font-weight:700;font-size:14px;list-style:none;display:flex;align-items:center;justify-content:space-between}
details summary::-webkit-details-marker{display:none}
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
      <div>
        <h1>dockycam</h1>
        <div class="meta" id="sysBar" style="margin:0;font-size:11.5px">보드 정보 확인 중...</div>
      </div>
    </div>
    <div class="row">
      <span class="pill"><span class="dot" id="stDot"></span><span id="stTxt">연결 확인 중...</span></span>
      <button onclick="refreshAll()">새로고침</button>
    </div>
  </div>

  <div class="grid">
    <div class="card" style="margin-bottom:0">
      <h2>보드 · 카메라 설정</h2>
      <div class="meta" id="netMeta">불러오는 중...</div>
      <div class="meta" id="telMeta"></div>
      <div class="row" style="margin-top:9px">
        <select id="optRes" title="해상도">
          <option value="qvga">QVGA (320×240)</option>
          <option value="vga">VGA (640×480)</option>
          <option value="svga">SVGA (800×600)</option>
          <option value="hd">HD (1280×720)</option>
        </select>
        <label class="chk"><input type="checkbox" id="optRot">180° 회전</label>
        <label class="chk"><input type="checkbox" id="optLow">저대역폭</label>
        <button class="sm pri" onclick="saveCamOpts()">적용</button>
      </div>
      <div class="row" style="margin-top:9px">
        <button onclick="toggleLive()" id="liveToggle" class="pri">실시간 영상 열기</button>
        <button id="nightBtn" onclick="toggleNight()">야간 흑백</button>
        <button id="torchBtn" onclick="toggleTorch()">화이트 조명</button>
        <button id="ledBtn" onclick="toggleLed()">LED 전환</button>
        <button class="red" onclick="rebootBoard()">재부팅</button>
      </div>
    </div>

    <div class="card" style="margin-bottom:0">
      <h2>SD카드 · 카메라 녹화</h2>
      <div class="meta" id="sdMeta">SD카드 확인 중...</div>
      <div class="bar"><div id="sdFill" style="width:0%"></div></div>
      <div class="row" style="margin-top:10px">
        <button class="pri" id="bbBtn" onclick="toggleRec('blackbox')">블랙박스 시작</button>
        <button id="tlBtn" onclick="toggleRec('timelapse')">타임랩스 시작</button>
        <button onclick="snap()">SD 스냅샷</button>
      </div>
    </div>
  </div>

  <div class="card" id="liveCard" style="display:none;margin-top:12px">
    <h2>
      <span>실시간 미리보기</span>
      <span class="row">
        <button class="sm" id="liveNightBtn" onclick="toggleNight()">야간 흑백</button>
        <button class="sm" id="liveTorchBtn" onclick="toggleTorch()">화이트 조명</button>
        <button class="sm" onclick="captureLivePc()">PC에 사진 저장</button>
        <button class="sm" onclick="fullLive()">전체화면</button>
        <button class="sm red" onclick="toggleLive()">닫기</button>
      </span>
    </h2>
    <div id="liveBox"><img id="liveImg" crossorigin="anonymous" alt="live stream"></div>
  </div>

  <div class="grid" style="margin-top:12px">
    <div class="card" style="margin-bottom:0">
      <h2>간이 클라우드 업로드 <span class="meta" id="upPathTag" style="margin:0;font-weight:400">/dockycam/shared</span></h2>
      <div class="drop" id="dropZone" onclick="$('filePick').click()">
        <div style="font-weight:700;font-size:13.5px">파일 끌어다 놓기 · 클릭 선택 · 스크린샷 Ctrl+V</div>
        <div class="meta" style="margin-top:4px">PC 캡처 이미지를 Ctrl+V로 붙여넣으면 즉시 공유함에 저장됩니다.<br>전송 중 영상이 자동 정지되어 최대 속도로 저장되며, 공유함은 순환 삭제에서 보호됩니다.</div>
      </div>
      <input id="filePick" type="file" multiple style="display:none" onchange="uploadFiles(this.files);this.value=''">
      <div id="upBox" style="display:none;margin-top:10px">
        <div class="meta" id="upTxt">전송 준비 중...</div>
        <div class="bar"><div id="upFill" style="width:0%"></div></div>
      </div>
    </div>

    <div class="card" style="margin-bottom:0">
      <h2>빠른 메모 · 링크 공유판 <span class="meta" id="noteCnt" style="margin:0;font-weight:400"></span></h2>
      <input class="inp" id="noteTitle" placeholder="제목 (선택, 예: 작업 링크 / 메모)" maxlength="80" style="margin-bottom:7px">
      <textarea id="noteText" placeholder="기기 간 주고받을 메모나 URL을 입력하세요 (Ctrl+Enter 저장)" maxlength="3800"></textarea>
      <div class="row" style="margin-top:7px;justify-content:flex-end">
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

    <div id="sharedBar" style="display:none">
      <div class="row" style="justify-content:space-between">
        <div class="crumb" id="crumbBar" style="flex:1;margin:0"></div>
        <button class="sm pri" onclick="createFolder()">+ 새 폴더</button>
      </div>
    </div>

    <div class="row" style="margin:10px 0">
      <input class="inp" id="searchBox" placeholder="파일명 검색..." style="flex:1;min-width:150px" oninput="renderFileList()">
      <select id="sortSel" onchange="renderFileList()">
        <option value="newest">최신순</option>
        <option value="name">이름순</option>
        <option value="size_desc">용량 큰순</option>
        <option value="size_asc">용량 작은순</option>
      </select>
      <label class="chk"><input type="checkbox" id="selAllChk" onchange="toggleSelAll(this.checked)">전체선택</label>
      <button class="sm red" id="bulkDelBtn" style="display:none" onclick="bulkDelete()">선택 삭제</button>
    </div>

    <div id="files">불러오는 중...</div>
  </div>

  <div class="card">
    <details>
      <summary>
        <span>무선 펌웨어 업데이트 (OTA)</span>
        <span class="meta" style="margin:0;font-weight:400">USB 연결 없이 firmware.bin 업로드</span>
      </summary>
      <div style="margin-top:12px">
        <div class="meta">PlatformIO로 빌드된 <code>firmware.bin</code> 파일을 선택하면 무선으로 보드를 업데이트하고 자동 재부팅합니다. (실패 시 기존 펌웨어 유지)</div>
        <div class="row" style="margin-top:10px">
          <input id="otaPick" type="file" accept=".bin" style="display:none" onchange="startOta(this.files[0]);this.value=''">
          <button onclick="$('otaPick').click()" id="otaBtn">firmware.bin 선택 및 업데이트</button>
          <span class="meta" id="otaTxt" style="margin:0"></span>
        </div>
        <div class="bar" id="otaBarWrap" style="display:none"><div id="otaFill" style="width:0%"></div></div>
      </div>
    </details>
  </div>
</div>

<div class="modal" id="prevModal" onclick="if(event.target===this)closePreview()">
  <div class="mcard">
    <div class="mhead">
      <strong id="prevTitle" style="font-size:14px;word-break:break-all">미리보기</strong>
      <div class="row">
        <a class="btn sm" id="prevDl" href="#" download>다운로드</a>
        <button class="sm red" onclick="closePreview()">닫기</button>
      </div>
    </div>
    <div class="mbody" id="prevBody"></div>
  </div>
</div>

<script>
let curCat='all', curDir='', sdState=null, wifiState=null, liveOn=false, uploading=false;
let editNoteId=null, notesCache=[], filesCache=[], dirsCache=[], selectedKeys=new Set();
let aviTimer=null, aviFrames=[], aviIdx=0, aviPlaying=false, aviFps=8;
const $=id=>document.getElementById(id);
const esc=s=>String(s||'').replace(/[&<>"']/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
const linkify=s=>esc(s).replace(/(https?:\/\/[^\s<]+)/g,'<a href="$1" target="_blank" rel="noopener">$1</a>');
const fmtSz=b=>b>=1073741824?(b/1073741824).toFixed(2)+' GB':b>=1048576?(b/1048576).toFixed(1)+' MB':b>=1024?Math.round(b/1024)+' KB':b+' B';
const fmtMb=m=>m>=1024?(m/1024).toFixed(1)+' GB':m+' MB';
const fmtUp=s=>{const h=Math.floor(s/3600),m=Math.floor((s%3600)/60);return h?`${h}h ${m}m`:`${m}m ${s%60}s`;};
const fmtTitle=n=>{const m=n.match(/^[A-Z]+_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})\./);return m?`${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}`:n};
const fmtTs=ts=>{if(!ts||ts<1700000000)return '';const d=new Date(ts*1000);const p=v=>String(v).padStart(2,'0');return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;};
const catKo=c=>c==='shared'?'공유함':c==='blackbox'?'블랙박스':c==='timelapse'?'타임랩스':c==='snapshots'?'스냅샷':c;
const fileUrl=(cat,name,dir='')=>`/sd/file?cat=${encodeURIComponent(cat)}&name=${encodeURIComponent(name)}`+(cat==='shared'&&dir?'&dir='+encodeURIComponent(dir):'');
const previewKind=name=>{
  const l=name.toLowerCase();
  if(/\.(jpg|jpeg|png|webp|gif)$/.test(l)) return 'img';
  if(/\.avi$/.test(l)) return 'avi';
  if(/\.(txt|md|json|csv|log|ini|yml|yaml|xml|html|js|css|py|c|cpp|h|kt)$/.test(l)) return 'txt';
  return null;
};

async function postJson(u,obj){
  const r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:obj==null?'':JSON.stringify(obj)});
  if(!r.ok) throw new Error(await r.text()||('HTTP '+r.status));
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
function fullLive(){
  const el=$('liveBox');
  if(document.fullscreenElement) document.exitFullscreen();
  else if(el.requestFullscreen) el.requestFullscreen();
}
function captureLivePc(){
  const img=$('liveImg');
  if(!img.naturalWidth){alert('영상이 표시된 뒤 캡처해 주세요.');return;}
  const c=document.createElement('canvas');
  c.width=img.naturalWidth; c.height=img.naturalHeight;
  try{
    c.getContext('2d').drawImage(img,0,0);
    const a=document.createElement('a');
    const d=new Date(), p=v=>String(v).padStart(2,'0');
    a.download=`DockyCam_${d.getFullYear()}${p(d.getMonth()+1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.jpg`;
    a.href=c.toDataURL('image/jpeg',0.93);
    a.click();
  }catch(e){alert('브라우저 캡처에 실패했습니다: '+e.message);}
}
async function loadCamOpts(){
  try{
    const o=await fetch('/settings').then(r=>r.json());
    $('optRes').value=o.resolution||'hd';
    $('optRot').checked=!!o.rotate_180;
    $('optLow').checked=!!o.low_bandwidth;
  }catch(e){}
}
async function saveCamOpts(){
  try{
    const wasLive=liveOn;
    if(wasLive) stopLiveIfNeeded();
    await postJson('/settings',{resolution:$('optRes').value,rotate_180:$('optRot').checked,low_bandwidth:$('optLow').checked});
    if(wasLive) setTimeout(toggleLive,400);
    alert('카메라 설정을 저장했습니다.');
  }catch(e){alert('설정 저장 실패: '+e.message);}
}
async function rebootBoard(){
  if(!confirm('보드를 지금 원격 재부팅할까요? (약 5초 후 자동 새로고침)')) return;
  stopLiveIfNeeded();
  try{await postJson('/reboot',null);}catch(e){}
  $('stTxt').textContent='재부팅 중...';
  setTimeout(()=>location.reload(),6000);
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
function syncNightTorchUi(){
  const night=!!(wifiState && wifiState.night_mode);
  const torch=!!(wifiState && wifiState.torch_enabled);
  ['nightBtn','liveNightBtn'].forEach(id=>{
    const b=$(id); if(!b) return;
    b.textContent=night?'야간 흑백 ON':'야간 흑백';
    b.className=(id==='liveNightBtn'?'sm ':'')+(night?'pri':'');
  });
  ['torchBtn','liveTorchBtn'].forEach(id=>{
    const b=$(id); if(!b) return;
    b.textContent=torch?'화이트 조명 ON':'화이트 조명';
    b.style.borderColor=torch?'var(--am)':'';
    b.style.color=torch?'var(--am)':'';
  });
}
async function toggleNight(){
  const next=!(wifiState && wifiState.night_mode);
  const r=await postJson('/night',{enabled:next});
  if(wifiState) wifiState.night_mode=!!r.night_mode;
  syncNightTorchUi();
}
async function toggleTorch(){
  const next=!(wifiState && wifiState.torch_enabled);
  const r=await postJson('/torch',{enabled:next});
  if(wifiState) wifiState.torch_enabled=!!r.torch_enabled;
  syncNightTorchUi();
}
function updateUpPath(){
  $('upPathTag').textContent='/dockycam/shared'+(curCat==='shared'&&curDir?'/'+curDir:'');
}
function setCat(c,btn){
  curCat=c;
  if(c!=='shared') curDir='';
  selectedKeys.clear();
  document.querySelectorAll('.tabs button').forEach(b=>b.classList.remove('on'));
  btn.classList.add('on');
  $('sharedBar').style.display=c==='shared'?'block':'none';
  updateUpPath();
  loadFiles();
}
function openDir(d){
  curDir=d;
  selectedKeys.clear();
  updateUpPath();
  loadFiles();
}
async function createFolder(){
  const name=prompt('새 폴더 이름을 입력하세요 (예: 문서, 사진)');
  if(!name || !name.trim()) return;
  try{
    await postJson('/sd/mkdir',{dir:curDir,name:name.trim()});
    loadFiles();
  }catch(e){alert('폴더 생성 실패: '+e.message);}
}
async function renameItem(cat,name){
  const next=prompt('새 이름을 입력하세요:',name);
  if(!next || !next.trim() || next.trim()===name) return;
  try{
    await postJson('/sd/rename',{cat,dir:cat==='shared'?curDir:'',from:name,to:next.trim()});
    loadFiles();
  }catch(e){alert('이름 변경 실패: '+e.message);}
}
async function delFile(cat,name,isDir=false){
  const msg=isDir?`'${name}' 폴더와 내부 파일을 모두 삭제할까요?`:`'${name}' 파일을 삭제할까요?`;
  if(!confirm(msg)) return;
  await postJson('/sd/delete',{cat,dir:cat==='shared'?curDir:'',name,is_dir:isDir});
  loadFiles();loadStatus();
}
function toggleSel(key,checked){
  if(checked) selectedKeys.add(key); else selectedKeys.delete(key);
  updateBulkBtn();
}
function toggleSelAll(checked){
  selectedKeys.clear();
  if(checked){
    getVisibleFiles().forEach(f=>selectedKeys.add(f.cat+'|'+f.name));
  }
  renderFileList();
}
function updateBulkBtn(){
  const n=selectedKeys.size;
  $('bulkDelBtn').style.display=n?'inline-flex':'none';
  $('bulkDelBtn').textContent=`선택 삭제 (${n})`;
}
async function bulkDelete(){
  const n=selectedKeys.size;
  if(!n || !confirm(`선택한 ${n}개 파일을 삭제할까요?`)) return;
  const items=[];
  selectedKeys.forEach(k=>{
    const idx=k.indexOf('|');
    const cat=k.slice(0,idx), name=k.slice(idx+1);
    items.push({cat,dir:cat==='shared'?curDir:'',name,is_dir:false});
  });
  await postJson('/sd/delete',{items});
  selectedKeys.clear();
  $('selAllChk').checked=false;
  loadFiles();loadStatus();
}

function uploadOne(file,idx,total){
  return new Promise((resolve,reject)=>{
    const xhr=new XMLHttpRequest();
    const t0=Date.now();
    const dirQ=(curCat==='shared'&&curDir)?'&dir='+encodeURIComponent(curDir):'';
    xhr.open('POST','/sd/upload?name='+encodeURIComponent(file.name)+dirQ);
    xhr.upload.onprogress=e=>{
      if(!e.lengthComputable) return;
      const pct=Math.min(100,Math.round(e.loaded*100/e.total));
      const sec=Math.max(0.2,(Date.now()-t0)/1000);
      const spd=fmtSz(e.loaded/sec)+'/s';
      $('upFill').style.width=pct+'%';
      $('upTxt').textContent=`[${idx}/${total}] ${file.name} · ${pct}% (${fmtSz(e.loaded)} / ${fmtSz(e.total)} · ${spd})`;
    };
    xhr.onload=()=>{if(xhr.status>=200&&xhr.status<300)resolve();else reject(new Error(xhr.responseText||('HTTP '+xhr.status)));};
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
    $('upTxt').textContent=`완료! ${ok}개 파일을 ${$('upPathTag').textContent}에 저장했습니다.`;
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
document.addEventListener('paste',e=>{
  const cd=e.clipboardData;
  if(!cd || !cd.items) return;
  const imgs=[];
  const now=new Date(), pad=n=>String(n).padStart(2,'0');
  const stamp=`${now.getFullYear()}${pad(now.getMonth()+1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  for(const it of cd.items){
    if(it.kind==='file' && it.type && it.type.startsWith('image/')){
      const blob=it.getAsFile();
      if(!blob) continue;
      const ext=it.type.includes('jpeg')?'jpg':it.type.includes('webp')?'webp':it.type.includes('gif')?'gif':'png';
      const suffix=imgs.length?`_${imgs.length+1}`:'';
      imgs.push(new File([blob],`clip_${stamp}${suffix}.${ext}`,{type:it.type}));
    }
  }
  if(!imgs.length) return;
  e.preventDefault();
  dz.classList.add('over');
  setTimeout(()=>dz.classList.remove('over'),350);
  uploadFiles(imgs);
});

function startOta(file){
  if(!file) return;
  if(!file.name.toLowerCase().endsWith('.bin')){alert('.bin 펌웨어 파일만 선택하세요.');return;}
  if(!confirm(`'${file.name}' (${fmtSz(file.size)})로 보드 펌웨어를 무선 업데이트할까요?\n완료 후 보드가 자동 재부팅됩니다.`)) return;
  stopLiveIfNeeded();
  uploading=true;
  $('otaBtn').disabled=true;
  $('otaBarWrap').style.display='block';
  $('otaFill').style.width='0%';
  $('otaTxt').textContent='펌웨어 전송 중...';
  const xhr=new XMLHttpRequest();
  xhr.open('POST','/ota/upload');
  xhr.upload.onprogress=e=>{
    if(!e.lengthComputable) return;
    const pct=Math.min(100,Math.round(e.loaded*100/e.total));
    $('otaFill').style.width=pct+'%';
    $('otaTxt').textContent=`플래싱 중... ${pct}% (${fmtSz(e.loaded)} / ${fmtSz(e.total)})`;
  };
  xhr.onload=()=>{
    uploading=false;
    if(xhr.status===200){
      $('otaFill').style.width='100%';
      $('otaTxt').textContent='업데이트 완료! 보드 재부팅 후 8초 뒤 자동 새로고침합니다...';
      setTimeout(()=>location.reload(),8000);
    }else{
      $('otaBtn').disabled=false;
      $('otaTxt').textContent='업데이트 실패: '+(xhr.responseText||xhr.status);
    }
  };
  xhr.onerror=()=>{uploading=false;$('otaBtn').disabled=false;$('otaTxt').textContent='네트워크 오류로 중단됨';};
  xhr.send(file);
}

function copyText(txt,btn){
  const done=()=>{if(btn){const o=btn.textContent;btn.textContent='복사됨';setTimeout(()=>btn.textContent=o,1200);}};
  if(navigator.clipboard && window.isSecureContext){
    navigator.clipboard.writeText(txt).then(done).catch(()=>fallbackCopy(txt,done));
  }else fallbackCopy(txt,done);
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
  if(!notesCache.length){$('notesList').innerHTML='<div class="meta" style="margin-top:8px">저장된 메모가 없습니다.</div>';return;}
  $('notesList').innerHTML=notesCache.map((n,i)=>`<div class="note"><div class="note-h"><div><strong>${esc(n.title||'메모')}</strong> <span class="meta">${esc(fmtTs(n.ts))}</span></div><div class="row"><button class="sm" onclick="copyText(notesCache[${i}].text||notesCache[${i}].title,this)">복사</button><button class="sm" onclick="startEditNote(${i})">수정</button><button class="sm red" onclick="deleteNote(${n.id})">삭제</button></div></div><div class="note-b">${linkify(n.text||'')}</div></div>`).join('');
}
function startEditNote(idx){
  const n=notesCache[idx]; if(!n) return;
  editNoteId=n.id; $('noteTitle').value=n.title||''; $('noteText').value=n.text||'';
  $('noteSaveBtn').textContent='수정 저장'; $('noteCancelBtn').style.display='inline-flex'; $('noteText').focus();
}
function cancelEditNote(){
  editNoteId=null; $('noteTitle').value=''; $('noteText').value='';
  $('noteSaveBtn').textContent='메모 저장'; $('noteCancelBtn').style.display='none';
}
async function loadNotes(){try{const d=await fetch('/sd/notes').then(r=>r.json());renderNotes(d.notes||[]);}catch(e){}}
async function saveNote(){
  const title=$('noteTitle').value.trim(), text=$('noteText').value.trim();
  if(!title && !text) return;
  const payload=editNoteId!=null?{action:'update',id:editNoteId,title,text}:{action:'add',title,text};
  const d=await postJson('/sd/notes',payload); cancelEditNote(); renderNotes(d.notes||[]);
}
async function deleteNote(id){
  if(!confirm('이 메모를 삭제할까요?')) return;
  const d=await postJson('/sd/notes',{action:'delete',id}); renderNotes(d.notes||[]);
}
$('noteText').addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='Enter')saveNote();});

function closePreview(){
  if(aviTimer){clearInterval(aviTimer);aviTimer=null;}
  aviPlaying=false; aviFrames=[];
  $('prevModal').classList.remove('on');
  $('prevBody').innerHTML='';
}
function parseAviMjpeg(buf){
  const v=new DataView(buf), u8=new Uint8Array(buf), len=u8.length;
  let fps=8, pos=12;
  const fourcc=p=>String.fromCharCode(u8[p],u8[p+1],u8[p+2],u8[p+3]);
  const frames=[];
  function scan(start,end){
    let p=start;
    while(p+8<=end){
      const id=fourcc(p), sz=v.getUint32(p+4,true), data=p+8;
      if(id==='LIST' && data+4<=end){
        const lt=fourcc(data);
        if(lt==='hdrl' || lt==='strl' || lt==='movi') scan(data+4,Math.min(end,data+sz));
      }else if(id==='avih' && data+4<=end){
        const us=v.getUint32(data,true);
        if(us>10000 && us<5000000) fps=Math.max(1,Math.min(30,Math.round(1000000/us)));
      }else if((id==='00dc'||id==='00db') && sz>16 && data+sz<=end){
        if(u8[data]===0xFF && u8[data+1]===0xD8) frames.push(u8.subarray(data,data+sz));
      }
      p=data+sz+(sz&1);
      if(sz>end) break;
    }
  }
  scan(pos,len);
  return {fps,frames};
}
function renderAviFrame(idx){
  if(!aviFrames.length) return;
  aviIdx=Math.max(0,Math.min(aviFrames.length-1,idx));
  const blob=new Blob([aviFrames[aviIdx]],{type:'image/jpeg'});
  const url=URL.createObjectURL(blob);
  const img=new Image();
  img.onload=()=>{
    const cv=$('aviCv');
    if(cv){
      if(cv.width!==img.width||cv.height!==img.height){cv.width=img.width;cv.height=img.height;}
      cv.getContext('2d').drawImage(img,0,0);
    }
    URL.revokeObjectURL(url);
  };
  img.src=url;
  const rng=$('aviRng'), lbl=$('aviLbl');
  if(rng) rng.value=aviIdx;
  if(lbl) lbl.textContent=`${aviIdx+1} / ${aviFrames.length} 프레임 (${aviFps} FPS)`;
}
function toggleAviPlay(){
  aviPlaying=!aviPlaying;
  const b=$('aviPlayBtn'); if(b) b.textContent=aviPlaying?'일시정지':'재생';
  if(aviTimer){clearInterval(aviTimer);aviTimer=null;}
  if(aviPlaying){
    aviTimer=setInterval(()=>{
      if(aviIdx+1>=aviFrames.length) renderAviFrame(0);
      else renderAviFrame(aviIdx+1);
    },Math.max(40,Math.round(1000/aviFps)));
  }
}
async function openPreview(cat,name){
  stopLiveIfNeeded();
  const kind=previewKind(name);
  const url=fileUrl(cat,name,curDir);
  $('prevTitle').textContent=name;
  $('prevDl').href=url; $('prevDl').download=name;
  $('prevBody').innerHTML='<div class="meta">불러오는 중...</div>';
  $('prevModal').classList.add('on');
  if(kind==='img'){
    $('prevBody').innerHTML=`<img src="${url}" alt="${esc(name)}">`;
  }else if(kind==='txt'){
    try{
      const txt=await fetch(url).then(r=>r.text());
      $('prevBody').innerHTML=`<div class="row" style="justify-content:flex-end;margin-bottom:8px"><button class="sm" id="prevCopyBtn">전체 복사</button></div><pre id="prevPre"></pre>`;
      $('prevPre').textContent=txt;
      $('prevCopyBtn').onclick=()=>copyText(txt,$('prevCopyBtn'));
    }catch(e){$('prevBody').innerHTML='<div class="meta">텍스트를 읽을 수 없습니다.</div>';}
  }else if(kind==='avi'){
    try{
      const buf=await fetch(url).then(r=>r.arrayBuffer());
      const parsed=parseAviMjpeg(buf);
      aviFrames=parsed.frames; aviFps=parsed.fps; aviIdx=0; aviPlaying=false;
      if(!aviFrames.length){$('prevBody').innerHTML='<div class="meta">AVI 프레임을 해석할 수 없습니다. 다운로드해서 재생해 주세요.</div>';return;}
      $('prevBody').innerHTML=`<canvas id="aviCv"></canvas><div class="row" style="margin-top:10px;justify-content:center"><button class="sm pri" id="aviPlayBtn" onclick="toggleAviPlay()">재생</button><input id="aviRng" type="range" min="0" max="${aviFrames.length-1}" value="0" style="flex:1;max-width:420px" oninput="renderAviFrame(+this.value)"><span class="meta" id="aviLbl" style="margin:0"></span></div>`;
      renderAviFrame(0);
      toggleAviPlay();
    }catch(e){$('prevBody').innerHTML='<div class="meta">영상 불러오기 실패: '+esc(e.message)+'</div>';}
  }
}

function renderBreadcrumb(){
  if(curCat!=='shared') return;
  const parts=curDir?curDir.split('/'):[];
  let html=`<a onclick="openDir('')">공유함</a>`;
  let acc='';
  parts.forEach(p=>{
    acc=acc?acc+'/'+p:p;
    const target=acc;
    html+=` <span>/</span> <a onclick="openDir(decodeURIComponent('${encodeURIComponent(target)}'))">${esc(p)}</a>`;
  });
  $('crumbBar').innerHTML=html;
}
function getVisibleFiles(){
  const q=($('searchBox').value||'').trim().toLowerCase();
  const sort=$('sortSel').value;
  let arr=filesCache.filter(f=>!q || f.name.toLowerCase().includes(q));
  if(sort==='name') arr.sort((a,b)=>a.name.localeCompare(b.name,'ko'));
  else if(sort==='size_desc') arr.sort((a,b)=>(b.size||0)-(a.size||0));
  else if(sort==='size_asc') arr.sort((a,b)=>(a.size||0)-(b.size||0));
  else arr.sort((a,b)=>((b.mtime||0)-(a.mtime||0))||b.name.localeCompare(a.name));
  return arr;
}
function renderFileList(){
  renderBreadcrumb();
  const q=($('searchBox').value||'').trim().toLowerCase();
  const dirs=(curCat==='shared'?dirsCache:[]).filter(d=>!q || d.toLowerCase().includes(q));
  const list=getVisibleFiles();
  const tot=list.reduce((a,f)=>a+(f.size||0),0);
  $('fileSum').textContent=`${dirs.length?dirs.length+'개 폴더 · ':''}${list.length}개 파일 · ${fmtSz(tot)}`;
  updateBulkBtn();
  if(!dirs.length && !list.length){
    $('files').innerHTML='<div class="meta">표시할 폴더나 파일이 없습니다.</div>';
    return;
  }
  let html='';
  if(curCat==='shared' && curDir){
    const parent=curDir.includes('/')?curDir.slice(0,curDir.lastIndexOf('/')):'';
    html+=`<div class="file dir" onclick="openDir(decodeURIComponent('${encodeURIComponent(parent)}'))"><div><span class="tag folder">상위</span><strong>.. (상위 폴더로 이동)</strong></div></div>`;
  }
  dirs.forEach(d=>{
    const next=curDir?curDir+'/'+d:d;
    html+=`<div class="file dir" onclick="openDir(decodeURIComponent('${encodeURIComponent(next)}'))"><div><span class="tag folder">폴더</span><strong>${esc(d)}</strong></div><div class="row" onclick="event.stopPropagation()"><button class="sm" onclick="renameItem('shared',decodeURIComponent('${encodeURIComponent(d)}'))">이름변경</button><button class="sm red" onclick="delFile('shared',decodeURIComponent('${encodeURIComponent(d)}'),true)">삭제</button></div></div>`;
  });
  list.forEach(f=>{
    const key=f.cat+'|'+f.name;
    const chk=selectedKeys.has(key)?'checked':'';
    const url=fileUrl(f.cat,f.name,curDir);
    const kind=previewKind(f.name);
    const encN=encodeURIComponent(f.name);
    html+=`<div class="file"><div class="row" style="flex:1;min-width:200px"><input type="checkbox" style="accent-color:var(--ac);width:16px;height:16px" ${chk} onchange="toggleSel(decodeURIComponent('${encodeURIComponent(key)}'),this.checked)"><div><div><span class="tag ${esc(f.cat)}">${esc(catKo(f.cat))}</span><strong>${esc(fmtTitle(f.name))}</strong></div><div class="meta">${esc(f.name)} · ${fmtSz(f.size)}${f.mtime>1700000000?' · '+fmtTs(f.mtime):''}</div></div></div><div class="row">${kind?`<button class="sm pri" onclick="openPreview('${esc(f.cat)}',decodeURIComponent('${encN}'))">미리보기</button>`:''}<button class="sm" onclick="renameItem('${esc(f.cat)}',decodeURIComponent('${encN}'))">이름변경</button><a class="btn sm" href="${url}" download="${esc(f.name)}" onclick="stopLiveIfNeeded()">다운로드</a><button class="sm red" onclick="delFile('${esc(f.cat)}',decodeURIComponent('${encN}'))">삭제</button></div></div>`;
  });
  $('files').innerHTML=html;
}

async function loadStatus(){
  if(uploading) return;
  try{
    const [w,s,t]=await Promise.all([
      fetch('/wifi/status').then(r=>r.json()),
      fetch('/sd/status').then(r=>r.json()),
      fetch('/telemetry').then(r=>r.json())
    ]);
    wifiState=w; sdState=s;
    const clockStr=w.time_synced?fmtTs(w.epoch):'시각 미동기화';
    $('sysBar').textContent=`v${w.fw_version||'0.3.2'} · ${clockStr} · 가동 ${fmtUp(w.uptime_s||0)}`;
    $('netMeta').textContent=`${w.mode==='sta'?'공유기(STA)':'직결 AP'} · ${w.ssid} · ${w.ip} (ch ${w.channel})`;
    $('telMeta').textContent=`칩 온도 ${t.chip_temp_c!=null?t.chip_temp_c.toFixed(1)+' °C':'-'} · 신호 ${w.mode==='sta'?w.rssi+' dBm':'AP'} · LED ${w.led_enabled?'켜짐':'꺼짐(스텔스)'}`;
    $('ledBtn').textContent=w.led_enabled?'LED 끄기 (스텔스)':'LED 켜기';
    syncNightTorchUi();
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
    $('stDot').style.background=s.active?'var(--rd)':'var(--ac)';
  }catch(e){}
}
async function loadFiles(){
  try{
    const dirQ=(curCat==='shared'&&curDir)?'&dir='+encodeURIComponent(curDir):'';
    const d=await fetch('/sd/files?cat='+encodeURIComponent(curCat)+dirQ).then(r=>r.json());
    dirsCache=d.dirs||[];
    filesCache=d.files||[];
    renderFileList();
  }catch(e){$('files').textContent='목록을 불러올 수 없습니다.';}
}
function refreshAll(){loadStatus();loadCamOpts();loadFiles();loadNotes();}
refreshAll();
setInterval(()=>{if(!liveOn && !uploading)loadStatus();},5000);
</script>
</body>
</html>
)HTML";

} // namespace webui
