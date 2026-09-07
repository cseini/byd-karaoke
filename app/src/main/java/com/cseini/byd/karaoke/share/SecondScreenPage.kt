package com.cseini.byd.karaoke.share

/**
 * 뒷좌석 태블릿용 세컨드스크린 페이지(자체완결 HTML/CSS/JS). ReserveServer 의 /screen 에서 서빙.
 * 헤드유닛이 지금 트는 영상을 무음으로 동기화 재생(소리는 차량 스피커)하고, 점수·음성검색 상태 표시,
 * 검색·예약·재생·일시정지·다음곡·음성검색을 원격 조작한다.
 *
 * 주의: Kotlin 삼중따옴표는 `$` 를 문자열 템플릿으로 해석하므로 JS 는 백틱 템플릿 없이 문자열 결합으로 쓰고
 * `$` 문자를 쓰지 않는다.
 */
object SecondScreenPage {
    val HTML = """
<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>노래방 뒷좌석 화면</title>
<style>
 *{box-sizing:border-box}
 html,body{margin:0;height:100%;background:#000;color:#eee;font-family:-apple-system,'Noto Sans KR',sans-serif;overflow:hidden}
 #v{position:fixed;inset:0;width:100%;height:100%;object-fit:contain;background:#000}
 .toast{position:fixed;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.72);padding:10px 18px;border-radius:22px;font-size:18px;font-weight:700;z-index:5;white-space:nowrap}
 #title{top:14px;color:#41e0ff;max-width:80%;overflow:hidden;text-overflow:ellipsis}
 #voice{top:64px;color:#ffd23f;display:none}
 /* 하단 리모컨 바 */
 #bar{position:fixed;left:0;right:0;bottom:0;display:flex;gap:10px;justify-content:center;align-items:center;padding:12px;z-index:6;
      background:linear-gradient(to top,rgba(0,0,0,.6),rgba(0,0,0,0));opacity:.15;transition:opacity .25s}
 #bar.show{opacity:1}
 .btn{border:none;border-radius:14px;background:#1d2740;color:#fff;font-size:26px;min-width:64px;height:60px;font-weight:700}
 .btn:active{background:#2b6cff}
 .btn.wide{font-size:18px;padding:0 16px}
 /* 우상단 설정(오프셋) */
 #gear{position:fixed;top:12px;right:12px;z-index:6;background:rgba(0,0,0,.5);border:none;color:#fff;font-size:24px;width:52px;height:52px;border-radius:12px;opacity:.4}
 #offbox{position:fixed;top:70px;right:12px;z-index:7;background:#12162a;border:1px solid #2a3350;border-radius:14px;padding:14px 16px;width:280px;display:none}
 #offbox h4{margin:0 0 6px;color:#41e0ff;font-size:15px}
 #offbox .hint{font-size:12px;color:#8ab;line-height:1.4;margin-bottom:8px}
 #off{width:100%}
 #offval{color:#ffd23f;font-weight:700}
 /* 검색·예약 패널 */
 #panel{position:fixed;top:0;right:0;bottom:0;width:min(460px,86%);background:#0b0f1d;z-index:8;transform:translateX(100%);transition:transform .25s;display:flex;flex-direction:column}
 #panel.show{transform:translateX(0)}
 #panel header{padding:14px;background:#12162a;font-size:18px;font-weight:800;color:#41e0ff;display:flex;align-items:center;gap:10px}
 #panel header .x{margin-left:auto;background:#33334a;border:none;color:#fff;border-radius:10px;width:44px;height:44px;font-size:20px}
 .pwrap{padding:14px;overflow-y:auto;flex:1}
 .srow{display:flex;gap:8px;margin-bottom:12px}
 .srow input{flex:1;padding:14px;border:none;border-radius:10px;font-size:16px}
 .srow button{border:none;border-radius:10px;background:#2b6cff;color:#fff;font-size:16px;font-weight:700;padding:0 18px}
 .item{background:#161c30;border-radius:12px;padding:12px;margin-bottom:8px}
 .item .t{font-size:15px;line-height:1.35;margin-bottom:8px}
 .item .c{font-size:12px;color:#8ab}
 .item .row{display:flex;gap:8px}
 .item .row button{flex:1;border:none;border-radius:10px;padding:12px;font-size:15px;font-weight:700}
 .play{background:#00b368;color:#fff}
 .res{background:#ff3b8b;color:#fff}
 h3{color:#ffcf3f;margin:16px 0 8px;font-size:16px}
 .q{background:#141a2c;border-left:4px solid #41e0ff;border-radius:8px;padding:10px 12px;margin-bottom:6px;font-size:15px;display:flex;align-items:center;gap:8px}
 .q .qt{flex:1}
 .q button{background:#33334a;color:#ffb3b3;border:none;border-radius:8px;padding:8px 12px;font-size:13px}
 .empty{color:#889;font-size:14px;padding:8px 0}
 .num{min-width:22px;color:#41e0ff;font-weight:800}
 /* 점수 오버레이 */
 #score{position:fixed;inset:0;z-index:9;background:rgba(6,8,18,.92);display:none;flex-direction:column;justify-content:center;align-items:center;text-align:center;padding:24px}
 #score .n{font-size:120px;font-weight:900;color:#ffd23f;line-height:1}
 #score .g{font-size:30px;font-weight:800;margin:8px 0 14px}
 #score .d{font-size:17px;color:#cdd;white-space:pre-line;line-height:1.6}
 /* 시작 게이트 */
 #gate{position:fixed;inset:0;z-index:20;background:#06080f;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center;padding:24px}
 #gate h1{color:#41e0ff;font-size:34px;margin:0 0 10px}
 #gate p{color:#9ab;font-size:17px;line-height:1.5;max-width:520px}
 #gate button{margin-top:26px;border:none;border-radius:16px;background:#2b6cff;color:#fff;font-size:22px;font-weight:800;padding:18px 40px}
</style></head><body>
<video id="v" muted playsinline webkit-playsinline></video>
<div id="title" class="toast">연결 중…</div>
<div id="voice" class="toast"></div>

<button id="gear">⚙</button>
<div id="offbox">
  <h4>영상 ↔ 소리 맞추기</h4>
  <div class="hint">영상이 소리보다 <b>빠르면 +</b>, <b>느리면 −</b> 쪽으로.<br>차량 스피커 지연을 보정합니다.</div>
  <input id="off" type="range" min="-200" max="500" step="10">
  <div style="text-align:center;margin-top:6px">보정 <span id="offval">0</span> ms</div>
</div>

<div id="bar">
  <button class="btn" onclick="cmd('pause')" title="재생/일시정지">⏯</button>
  <button class="btn" onclick="cmd('next')" title="다음곡">⏭</button>
  <button class="btn" onclick="cmd('mute')" title="반주 음소거">🔇</button>
  <button class="btn" onclick="cmd('voice')" title="음성검색">🎤</button>
  <button class="btn wide" onclick="togglePanel()">🔎 검색·예약</button>
</div>

<div id="panel">
  <header>🎤 검색·예약<button class="x" onclick="togglePanel()">✕</button></header>
  <div class="pwrap">
    <div class="srow">
      <input id="q" placeholder="노래 제목·가수 검색" enterkeyhint="search">
      <button onclick="doSearch()">검색</button>
    </div>
    <div id="results"></div>
    <h3>🎫 예약된 곡</h3>
    <div id="queue"><div class="empty">아직 예약된 곡이 없어요.</div></div>
  </div>
</div>

<div id="score"><div class="n" id="sn">0</div><div class="g" id="sg"></div><div class="d" id="sd"></div></div>

<div id="gate">
  <h1>🎤 뒷좌석 노래방 화면</h1>
  <p>화면을 누르면 헤드유닛이 지금 트는 영상이 여기서 재생됩니다.<br>소리는 차량 스피커로 나오고, 영상만 여기에 맞춰 보여줘요.</p>
  <button onclick="startGate()">화면 켜기 ▶</button>
</div>

<script>
 var video=document.getElementById('v');
 var elTitle=document.getElementById('title'), elVoice=document.getElementById('voice');
 var elScore=document.getElementById('score'), elSN=document.getElementById('sn'), elSG=document.getElementById('sg'), elSD=document.getElementById('sd');
 var offInput=document.getElementById('off'), offVal=document.getElementById('offval');
 var curVid='', started=false, rtt=[], barTimer=null, lastTarget=0;
 // 곡이 바뀌어 새 스트림을 load() 한 직후엔 메타데이터가 없어 seek 이 버려진다 → 준비되면 마지막 target 으로 한 번 더.
 video.addEventListener('loadedmetadata',function(){ try{ video.currentTime=lastTarget/1000; }catch(e){} });

 // 보정값(로컬 저장). +면 영상을 소리에 맞춰 앞으로, -면 뒤로.
 var userOffset=parseInt(localStorage.getItem('ss_offset')||'150',10);
 offInput.value=userOffset; offVal.textContent=userOffset;
 offInput.addEventListener('input',function(){ userOffset=parseInt(offInput.value,10); offVal.textContent=userOffset; localStorage.setItem('ss_offset',String(userOffset)); });
 document.getElementById('gear').onclick=function(){ var b=document.getElementById('offbox'); b.style.display=b.style.display==='block'?'none':'block'; };

 function startGate(){
   started=true;
   document.getElementById('gate').style.display='none';
   video.play().catch(function(){});
   var r=document.documentElement; if(r.requestFullscreen) r.requestFullscreen().catch(function(){});
 }
 // 화면 아무 곳이나 탭하면 하단 리모컨 바를 잠깐 보여준다.
 document.addEventListener('click',function(){ var bar=document.getElementById('bar'); bar.classList.add('show'); if(barTimer)clearTimeout(barTimer); barTimer=setTimeout(function(){bar.classList.remove('show')},3500); });

 function esc(s){return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')}
 function cmd(a,vid,title){ var u='/cmd?action='+encodeURIComponent(a); if(vid)u+='&videoId='+encodeURIComponent(vid); if(title)u+='&title='+encodeURIComponent(title); fetch(u).catch(function(){}); }

 // 싱크 결정(헤드유닛 SecondScreenState.syncDecision 미러링)
 function syncDecision(targetMs,videoMs,speed,changed){
   if(changed || Math.abs(targetMs-videoMs)>1000) return {seek:true,rate:speed};
   var adj=Math.max(-0.03,Math.min(0.03,(targetMs-videoMs)/2000));
   return {seek:false,rate:speed*(1+adj)};
 }

 function showScore(d){
   elSN.textContent=d.score;
   elSG.textContent = d.score>=95?'🏆 완벽한 무대!':d.score>=88?'✨ 명 가수!':d.score>=80?'🔥 열창!':'👏 잘했어요!';
   elSD.textContent=d.breakdown||'';
   elScore.style.display='flex';
 }
 function hideScore(){ elScore.style.display='none'; }

 function applyVoice(v){
   if(!v||v==='idle'){ elVoice.style.display='none'; return; }
   elVoice.style.display='block';
   elVoice.textContent = v==='listening'?'🎤 듣는 중…' : v==='processing'?'⏳ 인식 중…' : ('🔎 '+v);
 }

 async function tick(){
   var t0=performance.now(), d;
   try{ var r=await fetch('/now',{cache:'no-store'}); d=await r.json(); }catch(e){ return; }
   var ms=performance.now()-t0; rtt.push(ms); if(rtt.length>8)rtt.shift();
   var minRtt=Math.min.apply(null,rtt);

   elTitle.textContent = d.title || (d.phase==='idle'?'헤드유닛에서 곡을 고르세요':'연결됨');
   applyVoice(d.voice);
   if(d.phase==='scoring' && d.score>=0) showScore(d); else hideScore();

   // 곡이 바뀌었으면 새 스트림 로드
   var changed=false;
   if(d.videoId && d.streamUrl && curVid!==d.videoId){
     curVid=d.videoId; changed=true;
     video.src=d.streamUrl; video.muted=true;
     video.load(); if(started) video.play().catch(function(){});
   }
   if(!d.videoId){ curVid=''; return; }
   if(!d.streamUrl){ elTitle.textContent=(d.title||'')+' — 불러오는 중…'; return; }

   if(d.playing && d.phase==='playing'){
     var speed=d.speed||1;
     var target=d.live + (minRtt/2)*speed - userOffset;   // 소리 지연만큼 영상을 맞춤
     if(d.duration>0) target=Math.max(0,Math.min(target,d.duration-200));
     lastTarget=target;   // loadedmetadata 재seek 용
     var vms=video.currentTime*1000;
     var dec=syncDecision(target,vms,speed,changed);
     if(dec.seek){ try{ video.currentTime=target/1000; }catch(e){} }
     else video.playbackRate=dec.rate;
     if(started && video.paused) video.play().catch(function(){});
   } else {
     if(!video.paused) video.pause();
   }
 }
 setInterval(tick,1000); tick();

 // ── 검색·예약 패널 ──
 function togglePanel(){ document.getElementById('panel').classList.toggle('show'); loadQueue(); }
 async function doSearch(){
   var q=document.getElementById('q').value.trim(); if(!q)return;
   var el=document.getElementById('results'); el.innerHTML='<div class="empty">검색 중…</div>';
   try{
     var res=await fetch('/search?q='+encodeURIComponent(q)); var d=await res.json();
     if(d.error){ el.innerHTML='<div class="empty">'+esc(d.error)+'</div>'; return; }
     if(!d.items.length){ el.innerHTML='<div class="empty">결과가 없어요.</div>'; return; }
     el.innerHTML=d.items.map(function(it){
       var t=esc(it.title), v=esc(it.videoId), c=esc(it.channel);
       return '<div class="item"><div class="t">'+t+'<div class="c">'+c+'</div></div>'+
         '<div class="row"><button class="play" onclick="playNow(\''+v+'\',\''+t.replace(/\'/g,"&#39;")+'\')">지금 재생</button>'+
         '<button class="res" onclick="reserve(this,\''+v+'\',\''+t.replace(/\'/g,"&#39;")+'\')">예약</button></div></div>';
     }).join('');
   }catch(e){ el.innerHTML='<div class="empty">차에 연결하지 못했습니다.</div>'; }
 }
 function playNow(vid,title){ cmd('play',vid,title); document.getElementById('panel').classList.remove('show'); }
 async function reserve(btn,vid,title){ btn.disabled=true; btn.textContent='예약됨'; try{ await fetch('/reserve?videoId='+encodeURIComponent(vid)+'&title='+encodeURIComponent(title)); loadQueue(); }catch(e){ btn.disabled=false; btn.textContent='예약'; } }
 async function loadQueue(){
   try{
     var res=await fetch('/queue'); var d=await res.json(); var el=document.getElementById('queue');
     if(!d.items.length){ el.innerHTML='<div class="empty">아직 예약된 곡이 없어요.</div>'; return; }
     el.innerHTML=d.items.map(function(it,i){ return '<div class="q"><span class="num">'+(i+1)+'</span><span class="qt">'+esc(it.title)+'</span><button onclick="cancelRes(\''+esc(it.videoId)+'\')">취소</button></div>'; }).join('');
   }catch(e){}
 }
 async function cancelRes(vid){ try{ await fetch('/cancel?videoId='+encodeURIComponent(vid)); loadQueue(); }catch(e){} }
 document.getElementById('q').addEventListener('keydown',function(e){ if(e.key==='Enter')doSearch(); });
 setInterval(function(){ if(document.getElementById('panel').classList.contains('show')) loadQueue(); },3000);
</script></body></html>
""".trimIndent()
}
