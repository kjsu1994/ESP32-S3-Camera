#pragma once

#include <pgmspace.h>

namespace webui {

constexpr char kIndexHtml[] PROGMEM = R"HTML(<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>dockycam</title>
<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='16' fill='%230b1411'/%3E%3Crect x='5' y='5' width='54' height='54' rx='13' fill='none' stroke='%2324322d' stroke-width='2'/%3E%3Ccircle cx='32' cy='32' r='18' fill='%2310261e' stroke='%2334d399' stroke-width='3'/%3E%3Ccircle cx='32' cy='32' r='10' fill='%2306130f' stroke='%236ee7b7' stroke-width='1.5' stroke-opacity='.5'/%3E%3Ccircle cx='32' cy='32' r='4.5' fill='%2334d399'/%3E%3Ccircle cx='27' cy='26' r='2.4' fill='%23ffffff' fill-opacity='.85'/%3E%3Ccircle cx='49' cy='15' r='3.5' fill='%2334d399'/%3E%3C/svg%3E">
<style>
:root{--bg:#0b0f0e;--card:#131b18;--alt:#1a2420;--bd:#24322d;--ink:#f3f7f5;--sec:#9cb0a6;--mut:#697d73;--ac:#34d399;--acbg:#123327;--red:#f87171;--redbg:#36181a;--amb:#fbbf24;--ambbg:#33270f}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
.wrap{max-width:960px;margin:0 auto;padding:18px 16px 40px}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px;flex-wrap:wrap}
h1{margin:0;font-size:20px;letter-spacing:-.02em}
.pill{display:inline-flex;align-items:center;gap:6px;padding:5px 11px;border-radius:99px;background:var(--alt);border:1px solid var(--bd);font-size:12px;font-weight:600;color:var(--sec)}
.dot{width:8px;height:8px;border-radius:99px;background:var(--ac)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px;margin-bottom:14px}
.card{background:var(--card);border:1px solid var(--bd);border-radius:16px;padding:16px}
.card h2{margin:0 0 8px;font-size:15px}
.sub{color:var(--sec);font-size:12.5px;margin:0 0 10px}
.bar{height:8px;border-radius:99px;background:var(--bg);border:1px solid var(--bd);overflow:hidden;margin:8px 0}
.bar>div{height:100%;background:var(--ac);transition:width .3s}
.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
button,a.btn{appearance:none;border:1px solid var(--bd);background:var(--alt);color:var(--ink);border-radius:11px;padding:8px 13px;font-size:13px;font-weight:600;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:6px}
button.pri{background:var(--ac);color:#042016;border-color:var(--ac)}
button.red{background:var(--redbg);color:var(--red);border-color:#5a2427}
button.amb{background:var(--ambbg);color:var(--amb);border-color:#594218}
button:disabled{opacity:.45;cursor:default}
.tabs{display:flex;gap:6px;background:var(--bg);padding:4px;border-radius:12px;border:1px solid var(--bd);margin-bottom:12px}
.tabs button{flex:1;border:0;background:transparent;color:var(--sec);padding:7px}
.tabs button.on{background:var(--ac);color:#042016}
.file{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:11px 12px;border-radius:12px;background:var(--alt);border:1px solid var(--bd);margin-bottom:8px;flex-wrap:wrap}
.tag{padding:2px 8px;border-radius:99px;font-size:11px;font-weight:700;margin-right:6px}
.tag.blackbox{background:var(--redbg);color:var(--red)}
.tag.timelapse{background:var(--ambbg);color:var(--amb)}
.tag.snapshots{background:var(--acbg);color:var(--ac)}
.meta{font-size:12px;color:var(--mut)}
#liveBox{margin-bottom:14px;display:none}
#liveImg{width:100%;max-height:70vh;object-fit:contain;background:#000;border-radius:12px;border:1px solid var(--bd)}
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div style="display:flex;align-items:center;gap:10px">
      <svg width="34" height="34" viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="16" fill="#0b1411"/><rect x="5" y="5" width="54" height="54" rx="13" fill="none" stroke="#24322d" stroke-width="2"/><circle cx="32" cy="32" r="18" fill="#10261e" stroke="#34d399" stroke-width="3"/><circle cx="32" cy="32" r="10" fill="#06130f"/><circle cx="32" cy="32" r="4.5" fill="#34d399"/><circle cx="27" cy="26" r="2.4" fill="#fff" fill-opacity=".85"/><circle cx="49" cy="15" r="3.5" fill="#34d399"/></svg>
      <div>
        <h1>dockycam</h1>
        <div class="meta" id="netMeta">상태 불러오는 중...</div>
      </div>
    </div>
    <div class="row">
      <span class="pill"><span class="dot" id="stDot"></span><span id="stTxt">연결됨</span></span>
      <button class="pri" id="liveToggle" onclick="toggleLive()">실시간 영상 열기</button>
    </div>
  </header>

  <div class="card" id="liveBox">
    <div class="row" style="justify-content:space-between;margin-bottom:8px">
      <strong>실시간 스트림 (포트 81)</strong>
      <button class="red" onclick="toggleLive()">영상 닫기</button>
    </div>
    <img id="liveImg" alt="Live Stream">
  </div>

  <div class="grid">
    <div class="card">
      <h2>SD카드 저장소</h2>
      <p class="sub" id="sdMeta">확인 중...</p>
      <div class="bar"><div id="sdFill" style="width:0%"></div></div>
      <div class="row" style="margin-top:10px">
        <button id="bbBtn" onclick="toggleRec('blackbox')">블랙박스 시작</button>
        <button id="tlBtn" class="amb" onclick="toggleRec('timelapse')">타임랩스(5초)</button>
        <button onclick="snap()">SD 스냅샷</button>
      </div>
    </div>
    <div class="card">
      <h2>보드 상태</h2>
      <p class="sub" id="telMeta">온도 · 채널 확인 중...</p>
      <div class="row" style="margin-top:10px">
        <button id="ledBtn" onclick="toggleLed()">상태 LED</button>
        <button onclick="refreshAll()">새로고침</button>
      </div>
    </div>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between;margin-bottom:10px">
      <h2 style="margin:0">SD카드 파일 관리</h2>
      <span class="meta" id="fileSum"></span>
    </div>
    <div class="tabs">
      <button class="on" onclick="setCat('all',this)">전체</button>
      <button onclick="setCat('blackbox',this)">블랙박스</button>
      <button onclick="setCat('timelapse',this)">타임랩스</button>
      <button onclick="setCat('snapshots',this)">스냅샷</button>
    </div>
    <div id="files">불러오는 중...</div>
  </div>
</div>
<script>
let curCat='all', sdState=null, wifiState=null, liveOn=false;
const $=id=>document.getElementById(id);
const fmtSz=b=>b>=1073741824?(b/1073741824).toFixed(2)+' GB':b>=1048576?(b/1048576).toFixed(1)+' MB':b>=1024?Math.round(b/1024)+' KB':b+' B';
const fmtMb=m=>m>=1024?(m/1024).toFixed(1)+' GB':m+' MB';
const fmtTitle=n=>{const m=n.match(/^[A-Z]+_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})\./);return m?`${m[1]}-${m[2]}-${m[3]} ${m[4]}:${m[5]}:${m[6]}`:n};
const catKo=c=>c==='blackbox'?'블랙박스':c==='timelapse'?'타임랩스':c==='snapshots'?'스냅샷':c;

async function postJson(u,obj){
  const r=await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:obj==null?'':JSON.stringify(obj)});
  if(!r.ok) throw new Error(r.status);
  return r.status===204?null:r.json();
}
function toggleLive(){
  liveOn=!liveOn;
  $('liveBox').style.display=liveOn?'block':'none';
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
async function loadStatus(){
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
    $('files').innerHTML=list.map(f=>{
      const q=`cat=${encodeURIComponent(f.cat)}&name=${encodeURIComponent(f.name)}`;
      return `<div class="file"><div><div><span class="tag ${f.cat}">${catKo(f.cat)}</span><strong>${fmtTitle(f.name)}</strong></div><div class="meta">${f.name} · ${fmtSz(f.size)}</div></div><div class="row"><a class="btn" href="/sd/file?${q}" download="${f.name}">다운로드</a><button class="red" onclick="delFile('${f.cat}','${f.name}')">삭제</button></div></div>`;
    }).join('');
  }catch(e){$('files').textContent='목록을 불러올 수 없습니다.';}
}
function refreshAll(){loadStatus();loadFiles();}
refreshAll();
setInterval(()=>{if(!liveOn)loadStatus();},5000);
</script>
</body>
</html>
)HTML";

} // namespace webui
