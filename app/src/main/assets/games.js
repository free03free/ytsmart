/* games.js v2 — وضع الألعاب: شاشة مستقلة لكل لعبة، تصميم عصري، عبارات صوتية حسب الحالة، ونطق فوري.
   يُحمَّل من index.html بعد app.js:  <script src="games.js?v=2"></script>
   كل الأصوات هنا بسرعة الحزمة الجاهزة (0.9) فتُنطق من tts-pre فورًا بلا توليد على الجهاز. */
(function(){
"use strict";
if(document.getElementById("cg-mode"))return;
const $=id=>document.getElementById(id);
let maxNumber=10;
function readPrefs(){maxNumber=parseInt(localStorage.getItem("learningMaxNumber")||"10",10);if(!isFinite(maxNumber)||maxNumber<1)maxNumber=10;if(maxNumber>1000)maxNumber=1000}
readPrefs();
/* ----- بيانات منسوخة من app.js بنفس الصيغة ليتطابق النطق مع الحزمة المسبقة ----- */
const {letters,colors,lessonWords,pictures:PICS,numberWord,ones,tens,words:LETTER_WORDS,SAY:LS_SAY,harakat:mkHarakat,madds:mkMadds,sylKind}=window.CatSpeech;
const LS_SOLO=window.CatSpeech.SAY_SOLO||{}; // المصدر الوحيد للنصوص: speech-data.js
const LS_SVG={
 tamr:"<svg viewBox='0 0 120 120' xmlns='http://www.w3.org/2000/svg' aria-hidden='true'><defs><radialGradient id='tm1' cx='.35' cy='.28' r='.9'><stop offset='0' stop-color='#e0924f'/><stop offset='.45' stop-color='#a24e1e'/><stop offset='1' stop-color='#4a1e09'/></radialGradient></defs><path d='M8 46Q60 -8 112 46' fill='none' stroke='#2e7d32' stroke-width='3.5' stroke-linecap='round'/><ellipse cx='18.4' cy='36.3' rx='8.2' ry='2.6' transform='rotate(18 18.4 36.3) translate(8.2 0)' fill='#43a047'/><ellipse cx='18.4' cy='36.3' rx='8.2' ry='2.6' transform='rotate(-98 18.4 36.3) translate(8.2 0)' fill='#43a047'/><ellipse cx='28.8' cy='28.7' rx='8.9' ry='2.6' transform='rotate(26 28.8 28.7) translate(8.9 0)' fill='#43a047'/><ellipse cx='28.8' cy='28.7' rx='8.9' ry='2.6' transform='rotate(-90 28.8 28.7) translate(8.9 0)' fill='#43a047'/><ellipse cx='39.2' cy='23.3' rx='9.6' ry='2.6' transform='rotate(35 39.2 23.3) translate(9.6 0)' fill='#43a047'/><ellipse cx='39.2' cy='23.3' rx='9.6' ry='2.6' transform='rotate(-81 39.2 23.3) translate(9.6 0)' fill='#43a047'/><ellipse cx='49.6' cy='20.1' rx='10.3' ry='2.6' transform='rotate(46 49.6 20.1) translate(10.3 0)' fill='#43a047'/><ellipse cx='49.6' cy='20.1' rx='10.3' ry='2.6' transform='rotate(-70 49.6 20.1) translate(10.3 0)' fill='#43a047'/><ellipse cx='60.0' cy='19.0' rx='11.0' ry='2.6' transform='rotate(58 60.0 19.0) translate(11.0 0)' fill='#43a047'/><ellipse cx='60.0' cy='19.0' rx='11.0' ry='2.6' transform='rotate(-58 60.0 19.0) translate(11.0 0)' fill='#43a047'/><ellipse cx='70.4' cy='20.1' rx='10.3' ry='2.6' transform='rotate(70 70.4 20.1) translate(10.3 0)' fill='#43a047'/><ellipse cx='70.4' cy='20.1' rx='10.3' ry='2.6' transform='rotate(-46 70.4 20.1) translate(10.3 0)' fill='#43a047'/><ellipse cx='80.8' cy='23.3' rx='9.6' ry='2.6' transform='rotate(81 80.8 23.3) translate(9.6 0)' fill='#43a047'/><ellipse cx='80.8' cy='23.3' rx='9.6' ry='2.6' transform='rotate(-35 80.8 23.3) translate(9.6 0)' fill='#43a047'/><ellipse cx='91.2' cy='28.7' rx='8.9' ry='2.6' transform='rotate(90 91.2 28.7) translate(8.9 0)' fill='#43a047'/><ellipse cx='91.2' cy='28.7' rx='8.9' ry='2.6' transform='rotate(-26 91.2 28.7) translate(8.9 0)' fill='#43a047'/><ellipse cx='101.6' cy='36.3' rx='8.2' ry='2.6' transform='rotate(98 101.6 36.3) translate(8.2 0)' fill='#43a047'/><ellipse cx='101.6' cy='36.3' rx='8.2' ry='2.6' transform='rotate(-18 101.6 36.3) translate(8.2 0)' fill='#43a047'/><g stroke='#6b4a1f' stroke-width='2.4' stroke-linecap='round' fill='none'><path d='M60 15L60 30'/><path d='M60 30L36 58'/><path d='M60 30L60 62'/><path d='M60 30L84 58'/></g><g transform='rotate(14 36 60)'><path d='M36 60C50 60 53 82 52 92C50 108 44 116 36 116C28 116 22 108 20 92C19 82 22 60 36 60Z' fill='url(#tm1)' stroke='#2a1105' stroke-width='2.6'/><path d='M27 80Q36 76 45 80M25 94Q36 90 47 94M27 106Q36 102 45 106' fill='none' stroke='#2a1105' stroke-opacity='.28' stroke-width='1.6' stroke-linecap='round'/><ellipse cx='29' cy='80' rx='2.6' ry='9' fill='#fff' fill-opacity='.32'/><path d='M31 61Q36 55 41 61L39.5 66Q36 63 32.5 66Z' fill='#6b4a1f' stroke='#2a1105' stroke-width='1.6' stroke-linejoin='round'/></g><g transform='rotate(-14 84 60)'><path d='M84 60C98 60 101 82 100 92C98 108 92 116 84 116C76 116 70 108 68 92C67 82 70 60 84 60Z' fill='url(#tm1)' stroke='#2a1105' stroke-width='2.6'/><path d='M75 80Q84 76 93 80M73 94Q84 90 95 94M75 106Q84 102 93 106' fill='none' stroke='#2a1105' stroke-opacity='.28' stroke-width='1.6' stroke-linecap='round'/><ellipse cx='77' cy='80' rx='2.6' ry='9' fill='#fff' fill-opacity='.32'/><path d='M79 61Q84 55 89 61L87.5 66Q84 63 80.5 66Z' fill='#6b4a1f' stroke='#2a1105' stroke-width='1.6' stroke-linejoin='round'/></g><g transform='rotate(0 60 66)'><path d='M60 66C74 66 77 88 76 98C74 114 68 122 60 122C52 122 46 114 44 98C43 88 46 66 60 66Z' fill='url(#tm1)' stroke='#2a1105' stroke-width='2.6'/><path d='M51 86Q60 82 69 86M49 100Q60 96 71 100M51 112Q60 108 69 112' fill='none' stroke='#2a1105' stroke-opacity='.28' stroke-width='1.6' stroke-linecap='round'/><ellipse cx='53' cy='86' rx='2.6' ry='9' fill='#fff' fill-opacity='.32'/><path d='M55 67Q60 61 65 67L63.5 72Q60 69 56.5 72Z' fill='#6b4a1f' stroke='#2a1105' stroke-width='1.6' stroke-linejoin='round'/></g></svg>",
 rumman:"<svg viewBox=\"0 0 120 120\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\"><defs><radialGradient id=\"rr\" cx=\".4\" cy=\".3\" r=\".9\"><stop offset=\"0\" stop-color=\"#ee5a4f\"/><stop offset=\".7\" stop-color=\"#b8182a\"/><stop offset=\"1\" stop-color=\"#7d0f1d\"/></radialGradient></defs>\n<path d=\"M44 20L50 6L57 17L64 4L71 17L78 6L84 20Z\" fill=\"#9c1426\" stroke=\"#6e0b17\" stroke-width=\"2\" stroke-linejoin=\"round\"/>\n<circle cx=\"60\" cy=\"66\" r=\"46\" fill=\"url(#rr)\" stroke=\"#6e0b17\" stroke-width=\"3\"/>\n<circle cx=\"60\" cy=\"66\" r=\"40\" fill=\"#fff0cf\"/>\n<path d=\"M60 26V106M20 66H100M32 38L88 94M88 38L32 94\" stroke=\"#f1d6a0\" stroke-width=\"3\"/>\n<ellipse cx=\"90.0\" cy=\"66.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(90 90.0 66.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"88.5\" cy=\"63.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"86.0\" cy=\"81.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(120 86.0 81.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"84.5\" cy=\"78.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"75.0\" cy=\"92.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(150 75.0 92.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"73.5\" cy=\"89.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"60.0\" cy=\"96.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(180 60.0 96.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"58.5\" cy=\"93.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"45.0\" cy=\"92.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(210 45.0 92.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"43.5\" cy=\"89.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"34.0\" cy=\"81.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(240 34.0 81.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"32.5\" cy=\"78.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"30.0\" cy=\"66.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(270 30.0 66.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"28.5\" cy=\"63.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"34.0\" cy=\"51.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(300 34.0 51.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"32.5\" cy=\"48.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"45.0\" cy=\"40.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(330 45.0 40.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"43.5\" cy=\"37.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"60.0\" cy=\"36.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(360 60.0 36.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"58.5\" cy=\"33.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"75.0\" cy=\"40.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(390 75.0 40.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"73.5\" cy=\"37.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"86.0\" cy=\"51.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(420 86.0 51.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"84.5\" cy=\"48.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"77.5\" cy=\"73.4\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(113 77.5 73.4)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"76.0\" cy=\"71.2\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"67.1\" cy=\"83.6\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(158 67.1 83.6)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"65.6\" cy=\"81.4\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"52.6\" cy=\"83.5\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(203 52.6 83.5)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"51.1\" cy=\"81.3\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"42.4\" cy=\"73.1\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(248 42.4 73.1)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"40.9\" cy=\"70.9\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"42.5\" cy=\"58.6\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(293 42.5 58.6)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"41.0\" cy=\"56.4\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"52.9\" cy=\"48.4\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(338 52.9 48.4)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"51.4\" cy=\"46.2\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"67.4\" cy=\"48.5\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(383 67.4 48.5)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"65.9\" cy=\"46.3\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"77.6\" cy=\"58.9\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(428 77.6 58.9)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"76.1\" cy=\"56.7\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"65.6\" cy=\"71.7\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(136 65.6 71.7)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"64.1\" cy=\"69.5\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"52.2\" cy=\"68.0\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(256 52.2 68.0)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"50.7\" cy=\"65.8\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/><ellipse cx=\"62.2\" cy=\"58.3\" rx=\"6.2\" ry=\"7.4\" transform=\"rotate(376 62.2 58.3)\" fill=\"#c4122b\" stroke=\"#7a0a1a\" stroke-width=\"1.3\"/><ellipse cx=\"60.7\" cy=\"56.1\" rx=\"1.8\" ry=\"2.6\" fill=\"#fff\" fill-opacity=\".55\"/></svg>",
 bear:"<svg viewBox=\"0 0 120 120\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\"><g fill=\"#8a5a33\" stroke=\"#4e2f16\" stroke-width=\"3\"><circle cx=\"38\" cy=\"22\" r=\"11\"/><circle cx=\"82\" cy=\"22\" r=\"11\"/><ellipse cx=\"26\" cy=\"68\" rx=\"11\" ry=\"20\" transform=\"rotate(18 26 68)\"/><ellipse cx=\"94\" cy=\"68\" rx=\"11\" ry=\"20\" transform=\"rotate(-18 94 68)\"/><ellipse cx=\"60\" cy=\"76\" rx=\"30\" ry=\"32\"/><ellipse cx=\"42\" cy=\"108\" rx=\"15\" ry=\"10\"/><ellipse cx=\"78\" cy=\"108\" rx=\"15\" ry=\"10\"/><circle cx=\"60\" cy=\"40\" r=\"26\"/></g><circle cx=\"38\" cy=\"22\" r=\"5\" fill=\"#e8a98a\"/><circle cx=\"82\" cy=\"22\" r=\"5\" fill=\"#e8a98a\"/><ellipse cx=\"60\" cy=\"80\" rx=\"18\" ry=\"22\" fill=\"#e8c9a0\"/><ellipse cx=\"60\" cy=\"48\" rx=\"13\" ry=\"10\" fill=\"#e8c9a0\"/><circle cx=\"50\" cy=\"36\" r=\"3.5\" fill=\"#222\"/><circle cx=\"70\" cy=\"36\" r=\"3.5\" fill=\"#222\"/><ellipse cx=\"60\" cy=\"44\" rx=\"5\" ry=\"3.5\" fill=\"#222\"/><path d=\"M60 47V51M54 52Q60 56 66 52\" stroke=\"#222\" stroke-width=\"2\" fill=\"none\" stroke-linecap=\"round\"/><ellipse cx=\"42\" cy=\"108\" rx=\"8\" ry=\"5\" fill=\"#e8c9a0\"/><ellipse cx=\"78\" cy=\"108\" rx=\"8\" ry=\"5\" fill=\"#e8c9a0\"/></svg>"
};
const lsPic=p=>p.indexOf("svg:")===0?LS_SVG[p.slice(4)]:'<span class="ls-emoji">'+p+'</span>';

/* ================= الصوت: سرعة ثابتة = سرعة الحزمة الجاهزة، فيُنطق كل شيء فورًا بلا توليد ================= */
const BASE=0.9;
const spd=k=>Math.round(BASE*k*1000)/1000;
function learnArgs(text){
 const s0=String(text||""),t=LS_SOLO[s0]||LS_SAY[s0]||s0,k=sylKind(t);
 if(k==="short")return[t,spd(.8)];
 if(k==="madd")return[t,spd(.84)];
 return[t,spd(.91)];
}
const nameArgs=i=>learnArgs(letters[i][1]);
const wordArgs=i=>learnArgs(LS_SAY[lessonWords[i][0]]||lessonWords[i][0]);
const wordArgsW=w=>learnArgs(LS_SAY[w]||w);
/* تدوير الصور: لكل حرف نختار الصورة الأقل ظهورًا (محفوظ بين الجلسات) فلا تتكرر صورة قبل أن تظهر كل صور الحرف */
const PIC_KEY="catPicSeen";let picSeen={};try{picSeen=JSON.parse(localStorage.getItem(PIC_KEY)||"{}")||{}}catch(e){}
function nextPic(i){const L=PICS[i],c=picSeen[i]||{};let min=1e9;L.forEach((_,j)=>{min=Math.min(min,c[j]||0)});const cand=[];L.forEach((_,j)=>{if((c[j]||0)===min)cand.push(j)});const j=cand[Math.floor(Math.random()*cand.length)];c[j]=(c[j]||0)+1;picSeen[i]=c;try{localStorage.setItem(PIC_KEY,JSON.stringify(picSeen))}catch(e){}return L[j]}
const randPic=i=>PICS[i][Math.floor(Math.random()*PICS[i].length)];
/* عبارات التشجيع والتصحيح (موجودة في الحزمة الجاهزة tools/lessons.json بنفس النص والسرعة) */
const Q={count:"كَمْ عَدَدُهَا",more:"أَيُّهُمَا أَكْثَر"};
const FB={great:"أَحْسَنْتَ",good:"جَيِّد",ok:"صَحِيح",no:"خَطَأ",again:"أَعِدِ المُحَاوَلَةَ مَرَّةً أُخْرَى"};

const sleep=ms=>new Promise(r=>setTimeout(r,ms));
let waiter=null;
function cancelWait(){if(waiter){const w=waiter;waiter=null;clearTimeout(w.to);w.res()}}
/* نعرف نهاية النطق من حدث «speaking» القادم من التطبيق، فنتابع بلا انتظار ثابت ولا قطع للكلام */
const prevEv=window.onNativeEvent;
window.onNativeEvent=function(t,p){
 if(waiter&&t==="speaking"){
  if(p==="1")waiter.started=Date.now();
  else if(p==="0"&&waiter.started){const w=waiter;waiter=null;clearTimeout(w.to);setTimeout(w.res,120)}
 }
 if(typeof prevEv==="function")return prevEv.apply(this,arguments);
};
function say(text,sp){if(window.TomNative&&TomNative.say){TomNative.say(text,Number(sp),0);return true}return false}
function sayWait(text,sp,maxMs){
 return new Promise(res=>{
  cancelWait();
  const w={res:res,started:0,to:0};
  w.to=setTimeout(()=>{if(waiter===w)waiter=null;res()},maxMs||4000);
  waiter=w;
  if(!say(text,sp)){waiter=null;clearTimeout(w.to);setTimeout(res,500)}
 });
}
/* تسخين: يفكّ مقاطع اللعبة مسبقًا في الذاكرة ليصير النطق عند الضغط فوريًا */
function warm(kind){
 if(!window.TomNative||!TomNative.warm)return;
 const items=[],seen=new Set(),add=a=>{const k=a[0]+"|"+a[1];if(!seen.has(k)){seen.add(k);items.push([a[0],a[1]])}};
 Object.keys(FB).forEach(k=>add([FB[k],BASE]));
 if(kind==="letters"||kind==="match"||kind==="matchl")letters.forEach((_,i)=>add(nameArgs(i)));
 if(kind==="match"||kind==="matchl")PICS.forEach(L=>L.forEach(e=>add(wordArgsW(e[0]))));
 if(kind==="colors")colors.forEach(c=>add([c[0],BASE]));
 if(kind==="numbers"){const N=Math.max(5,Math.min(maxNumber,20));for(let n=1;n<=N;n++)add([numberWord(n),BASE])}
 if(kind==="count"){for(let n=1;n<=10;n++)add([numberWord(n),BASE]);add([Q.count,BASE])}
 if(kind==="more")add([Q.more,BASE]);
 try{TomNative.warm(JSON.stringify(items))}catch(e){}
}

/* ================= الألعاب ================= */
const ROUNDS=10,OBJ=["🍎","⭐","🐟","🎈","🦋","🍓","🚗","🌼","🐥","⚽"];
const GAMES={
 letters:{title:"لعبة الحروف",sub:"اسمع الحرف ثم اختره",ico:"🔤",c1:"#ff9a5a",c2:"#f2506e"},
 numbers:{title:"لعبة الأرقام",sub:"اسمع الرقم ثم اختره",ico:"🔢",c1:"#4facfe",c2:"#3b5bdb"},
 count:{title:"لعبة العدّ",sub:"عُدَّ الأشياء واختر العدد",ico:"🍎",c1:"#34c759",c2:"#1b8f5a"},
 colors:{title:"لعبة الألوان",sub:"اسمع اللون ثم اختره",ico:"🎨",c1:"#b57bee",c2:"#6a3de8"},
 match:{title:"مطابقة الحرف بالصورة",sub:"شاهد الحرف واختر صورته",ico:"🖼️",c1:"#2dd4a7",c2:"#0f9b8e"},
 matchl:{title:"مطابقة الصورة بالحرف",sub:"شاهد الصورة واختر حرفها",ico:"🔠",c1:"#38bdf8",c2:"#2563eb"},
 more:{title:"أيّهما أكثر؟",sub:"اختر المجموعة الأكبر",ico:"⚖️",c1:"#ff7eb3",c2:"#d6336c"}
};
let gm=null;
const rnd=n=>Math.floor(Math.random()*n);
function shuffle(a){for(let i=a.length-1;i>0;i--){const j=rnd(i+1);const x=a[i];a[i]=a[j];a[j]=x}return a}
function pick(n,count,not){const s=new Set();if(not!=null)s.add(not);const out=[];while(out.length<count&&s.size<n){const r=rnd(n);if(!s.has(r)){s.add(r);out.push(r)}}return out}
function getBest(k){try{return parseInt(localStorage.getItem("gmBest_"+k)||"0",10)||0}catch(e){return 0}}
function setBest(k,v){try{if(v>getBest(k))localStorage.setItem("gmBest_"+k,String(v))}catch(e){}}
const starsOf=s=>s>=9?3:(s>=6?2:(s>=3?1:0));
const starsText=n=>"★★★".slice(0,n)+"☆☆☆".slice(0,3-n);

/* ----- التنسيق ----- */
const CSS=`
.mode-switch{grid-auto-flow:column;grid-auto-columns:minmax(0,1fr)}
#cg-games [hidden],#gm-screen[hidden],#gm-screen [hidden]{display:none!important}
.gh-wrap{padding:6px 2px 28px}
.gh-title{margin:6px 4px 14px;font-size:22px;font-weight:900;color:#594536}
.gh-list{display:grid;gap:14px}
.gh-card{position:relative;overflow:hidden;border:0;border-radius:28px;padding:16px 18px;display:flex;align-items:center;gap:14px;color:#fff;text-align:right;background:linear-gradient(135deg,var(--c1),var(--c2));box-shadow:0 10px 22px -10px var(--c2),inset 0 -5px 0 rgba(0,0,0,.14);transition:transform .15s}
.gh-card:active{transform:scale(.97)}
.gh-card::after{content:"";position:absolute;top:-40%;left:-18%;width:55%;height:180%;background:rgba(255,255,255,.14);transform:rotate(18deg);pointer-events:none}
.gh-ico{flex:none;width:68px;height:68px;border-radius:22px;background:rgba(255,255,255,.25);display:grid;place-items:center;font-size:38px}
.gh-txt{flex:1;display:grid;gap:3px;min-width:0}
.gh-txt b{font-size:22px;font-weight:900}
.gh-txt small{font-size:14px;opacity:.93}
.gh-best{flex:none;font-size:20px;letter-spacing:2px;direction:ltr;color:#ffe66d;text-shadow:0 1px 0 rgba(0,0,0,.25)}
body.gm-open{overflow:hidden}
#gm-screen{position:fixed;inset:0;z-index:9999;display:flex;flex-direction:column;color:#fff;background:linear-gradient(160deg,var(--c1,#ff9a5a),var(--c2,#f2506e));padding:max(env(safe-area-inset-top,0px),14px) 16px max(env(safe-area-inset-bottom,0px),16px);overflow:hidden;animation:gmScr .28s ease-out;user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent}
@keyframes gmScr{from{opacity:0;transform:translateY(26px) scale(.98)}to{opacity:1;transform:none}}
#gm-screen::before,#gm-screen::after{content:"";position:absolute;border-radius:50%;background:rgba(255,255,255,.11);pointer-events:none}
#gm-screen::before{width:320px;height:320px;top:-130px;left:-110px}
#gm-screen::after{width:260px;height:260px;bottom:-100px;right:-90px}
.gms-top{position:relative;z-index:1;display:flex;align-items:center;gap:10px}
.gms-x{flex:none;border:0;width:46px;height:46px;border-radius:50%;background:rgba(255,255,255,.26);color:#fff;font-size:22px;font-weight:900;display:grid;place-items:center}
.gms-x:active{transform:scale(.9)}
.gms-title{flex:1;display:flex;align-items:center;gap:8px;font-size:21px;font-weight:900;min-width:0}
.gms-pill{flex:none;background:rgba(255,255,255,.26);border-radius:999px;padding:7px 13px;font-weight:900;font-size:16px;direction:ltr}
.gms-prog{position:relative;z-index:1;height:12px;margin:14px 2px 4px;border-radius:99px;background:rgba(0,0,0,.2);overflow:hidden}
.gms-prog i{display:block;height:100%;width:0;border-radius:inherit;background:linear-gradient(90deg,#fff7ae,#ffd93b);transition:width .5s cubic-bezier(.3,1.4,.5,1)}
.gms-stage{position:relative;z-index:1;flex:1;min-height:0;display:flex;flex-direction:column;align-items:center;gap:12px;padding-top:50px}
.gms-mascot{position:absolute;left:0;top:0;font-size:44px;line-height:1;filter:drop-shadow(0 4px 0 rgba(0,0,0,.15));animation:gmBob 2.4s ease-in-out infinite}
@keyframes gmBob{50%{transform:translateY(-6px) rotate(-4deg)}}
.gms-prompt{flex:none;min-height:96px;max-width:100%;display:flex;flex-wrap:wrap;gap:6px;justify-content:center;align-items:center;font-size:44px;font-weight:900;line-height:1.15;text-align:center;text-shadow:0 3px 0 rgba(0,0,0,.16)}
.gms-prompt.big{font-size:clamp(84px,26vw,120px);animation:gmIn .4s both}
.gms-prompt.pic svg{width:clamp(110px,34vw,150px);height:clamp(110px,34vw,150px)}
.gms-prompt.pic .ls-emoji{font-size:clamp(90px,28vw,124px);line-height:1}
.gms-prompt.small{font-size:19px;background:rgba(255,255,255,.24);border-radius:999px;padding:9px 20px;min-height:0;margin-top:14px}
.gms-prompt.objs{font-size:48px}
.gms-prompt.objs.many{font-size:38px}
.gms-o{display:inline-block;position:relative;padding:2px 3px;border-radius:14px;transition:transform .2s;-webkit-tap-highlight-color:transparent}
.gms-o.cnt{transform:scale(1.18);background:rgba(255,255,255,.28)}
.gms-o.cnt::after{content:attr(data-n);position:absolute;left:50%;bottom:-8px;transform:translateX(-50%);min-width:24px;height:24px;border-radius:12px;background:#ffd93b;color:#333;font-size:15px;line-height:24px;text-align:center;font-weight:900;text-shadow:none}
.gms-opts.two{grid-template-columns:1fr}
.gms-grp{display:flex;flex-wrap:wrap;gap:2px 6px;align-content:center;justify-content:center;font-size:36px;line-height:1.15;padding:10px 14px}
.gms-hear{flex:none;width:96px;height:96px;border:0;border-radius:50%;font-size:44px;background:#fff;color:#333;box-shadow:0 8px 0 rgba(0,0,0,.18);animation:gmRing 2s infinite}
@keyframes gmRing{0%{box-shadow:0 8px 0 rgba(0,0,0,.18),0 0 0 0 rgba(255,255,255,.55)}70%,100%{box-shadow:0 8px 0 rgba(0,0,0,.18),0 0 0 22px rgba(255,255,255,0)}}
.gms-hear:active{transform:translateY(5px);box-shadow:0 3px 0 rgba(0,0,0,.18)}
.gms-opts{flex:1;min-height:0;max-height:430px;align-self:stretch;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));grid-auto-rows:minmax(0,1fr);gap:14px;padding:6px 0 2px}
.gms-opt{position:relative;border:0;border-radius:28px;background:#fff;color:var(--c2,#f2506e);font-weight:900;font-size:clamp(52px,16vw,76px);display:flex;align-items:center;justify-content:center;padding:6px;overflow:hidden;box-shadow:0 8px 0 rgba(0,0,0,.2);animation:gmIn .42s cubic-bezier(.3,1.5,.5,1) both;animation-delay:calc(var(--i,0)*70ms);transition:transform .12s,box-shadow .12s,opacity .2s}
.gms-opt:active{transform:translateY(6px);box-shadow:0 2px 0 rgba(0,0,0,.2)}
@keyframes gmIn{from{opacity:0;transform:translateY(30px) scale(.8)}to{opacity:1;transform:none}}
.gms-num{color:#1f6fc4}
.gms-color{border:6px solid rgba(255,255,255,.95);box-shadow:0 8px 0 rgba(0,0,0,.22),inset 0 -10px 0 rgba(0,0,0,.1)}
.gms-light{border-color:#d9d9d9}
.gms-pic .ls-emoji{font-size:clamp(56px,18vw,82px);line-height:1}
.gms-pic svg{width:min(92px,72%);height:auto;max-height:88%}
.gms-right{background:linear-gradient(#86ee8e,#2fbf68)!important;color:#fff!important;box-shadow:0 8px 0 #1c8a47!important;animation:gmPop .55s both!important}
.gms-right::after{content:"\\2713";position:absolute;top:6px;right:12px;font-size:26px;color:#fff}
.gms-right[data-n]::after{content:attr(data-n);top:6px;right:12px;width:34px;height:34px;border-radius:50%;background:rgba(0,0,0,.2);font-size:20px;line-height:34px;text-align:center}
.gms-wrong{background:#ffd7d7!important;animation:gmShake .45s!important}
.gms-off{opacity:.35;pointer-events:none}
.gms-hint{animation:gmPulse .8s infinite!important}
@keyframes gmPop{0%{transform:scale(1)}50%{transform:scale(1.14)}100%{transform:scale(1)}}
@keyframes gmShake{0%,100%{transform:translateX(0)}25%{transform:translateX(-12px)}75%{transform:translateX(12px)}}
@keyframes gmPulse{50%{transform:scale(1.06);box-shadow:0 8px 0 rgba(0,0,0,.2),0 0 0 12px rgba(134,238,142,.6)}}
.gms-end{position:relative;z-index:1;flex:1;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;text-align:center}
.gms-trophy{font-size:112px;line-height:1;animation:gmPop .9s both}
.gms-endstars{font-size:58px;letter-spacing:6px;direction:ltr;color:#ffe14d;text-shadow:0 4px 0 rgba(0,0,0,.22)}
.gms-score{font-size:34px;font-weight:900;margin-bottom:10px}
.gms-btn{border:0;border-radius:22px;padding:14px 28px;min-width:220px;font-size:20px;font-weight:900;background:#fff;color:var(--c2,#f2506e);box-shadow:0 6px 0 rgba(0,0,0,.2)}
.gms-btn:active{transform:translateY(4px);box-shadow:0 2px 0 rgba(0,0,0,.2)}
.gms-btn.alt{background:rgba(255,255,255,.26);color:#fff}
.gms-fx{position:absolute;inset:0;pointer-events:none;overflow:hidden;z-index:5}
.gms-fx i{position:absolute;top:-16px;width:10px;height:16px;border-radius:3px;animation:gmFall 1.5s ease-in forwards}
@keyframes gmFall{to{transform:translate(var(--dx,0),110vh) rotate(540deg)}}
@media (max-height:640px){.gms-hear{width:76px;height:76px;font-size:34px}.gms-prompt{min-height:70px}.gms-opt{border-radius:22px}}
@media (prefers-reduced-motion:reduce){#gm-screen,.gms-opt,.gms-mascot,.gms-hear{animation:none!important}}
`;
const st=document.createElement("style");st.id="cg-style";st.textContent=CSS;document.head.appendChild(st);

/* ----- الصفحة الرئيسية للألعاب (قائمة فقط) + الشاشة المستقلة لكل لعبة ----- */
const sw=document.querySelector(".mode-switch"),mainEl=document.querySelector("main.app")||document.body;
const btn=document.createElement("button");btn.id="cg-mode";btn.className="mode";btn.textContent="🎮 الألعاب";sw.appendChild(btn);
const sec=document.createElement("section");sec.id="cg-games";sec.className="mode-page";
sec.innerHTML='<div class="gh-wrap"><h2 class="gh-title">🐱 اختر لعبتك</h2><div class="gh-list">'+
 Object.keys(GAMES).map(k=>{const g=GAMES[k];return '<button type="button" class="gh-card" data-game="'+k+'" style="--c1:'+g.c1+';--c2:'+g.c2+'"><span class="gh-ico">'+g.ico+'</span><span class="gh-txt"><b>'+g.title+'</b><small>'+g.sub+'</small></span><span class="gh-best" data-best="'+k+'">☆☆☆</span></button>'}).join("")+
 '</div></div>';
mainEl.appendChild(sec);

const scr=document.createElement("div");scr.id="gm-screen";scr.hidden=true;scr.setAttribute("role","dialog");
scr.innerHTML='<div id="gm-fx" class="gms-fx"></div>'+
 '<header class="gms-top"><button id="gm-back" class="gms-x" type="button" aria-label="رجوع">✕</button><div class="gms-title"><span id="gm-ico"></span><span id="gm-title"></span></div><div id="gm-score-pill" class="gms-pill">⭐ 0</div><div id="gm-count" class="gms-pill">1 / 10</div></header>'+
 '<div class="gms-prog"><i id="gm-prog"></i></div>'+
 '<main id="gm-stage" class="gms-stage"><div id="gm-mascot" class="gms-mascot">😺</div><div id="gm-prompt" class="gms-prompt"></div><button id="gm-hear" class="gms-hear" type="button" aria-label="اسمع">🔊</button><div id="gm-options" class="gms-opts"></div></main>'+
 '<section id="gm-end" class="gms-end" hidden><div id="gm-trophy" class="gms-trophy">🏆</div><div id="gm-endstars" class="gms-endstars"></div><div id="gm-score" class="gms-score"></div><button id="gm-again" class="gms-btn" type="button">🔄 العب مرة أخرى</button><button id="gm-menu" class="gms-btn alt" type="button">🏠 الألعاب</button></section>';
document.body.appendChild(scr);

function refreshHub(){[].slice.call(sec.querySelectorAll("[data-best]")).forEach(e=>{e.textContent=starsText(starsOf(getBest(e.dataset.best)))})}
function setMascot(e){const m=$("gm-mascot");if(m)m.textContent=e}
function hud(){
 if(!gm)return;
 $("gm-count").textContent=Math.min(gm.round+1,ROUNDS)+" / "+ROUNDS;
 $("gm-prog").style.width=(gm.round/ROUNDS*100)+"%";
 $("gm-score-pill").textContent="⭐ "+gm.score;
}
function confetti(n){
 const fx=$("gm-fx"),cols=["#ffd93b","#ff6b81","#4ade80","#60a5fa","#fff","#c084fc"];
 for(let i=0;i<n;i++){const e=document.createElement("i");e.style.left=(Math.random()*100)+"%";e.style.background=cols[i%cols.length];e.style.setProperty("--dx",((Math.random()-.5)*160)+"px");e.style.animationDelay=(Math.random()*250)+"ms";e.style.animationDuration=(1.2+Math.random()*.8)+"s";fx.appendChild(e);setTimeout(()=>e.remove(),2300)}
}

/* ----- ما يُنطق للسؤال الحالي ----- */
/* ملاحظة: في «العدّ» و«أيّهما أكثر» الصوت هو السؤال نفسه (Q) لا الجواب، وبعد الإجابة الصحيحة يُنطق عدد العدّ تثبيتًا */
function targetArgs(){
 const k=gm.kind;
 if(k==="letters")return nameArgs(gm.t);
 if(k==="colors")return[colors[gm.t][0],BASE];
 if(k==="match"||k==="matchl")return gm.dir==="pic"?nameArgs(gm.t):(gm.word?wordArgsW(gm.word):wordArgs(gm.t));
 return[numberWord(gm.t),BASE];
}
function speakTarget(){if(!gm)return;const a=targetArgs();say(a[0],a[1])}
/* في العدّ والمطابقة لم يسمع الطفل المطلوب قبل الجواب، فنُسمعه بعد الإجابة الصحيحة تثبيتًا للتعلم */
const reinforce=()=>gm.count||gm.kind==="match"||gm.kind==="matchl";

const qArgs=()=>[Q[gm.kind]||"",BASE];
function tapObj(sp){
 if(!gm||gm.locked||gm.kind!=="count"||sp.classList.contains("cnt"))return;
 gm.act++;gm.tapped++;sp.classList.add("cnt");sp.dataset.n=String(gm.tapped);
 say(numberWord(gm.tapped),BASE);
}
/* العدّ بصوت القط: يُنطق السؤال ثم يعدّ الأشياء واحدًا واحدًا مع إضاءة كل واحد */
async function countAloud(){
 const a=++gm.act,objs=[].slice.call($("gm-prompt").querySelectorAll(".gms-o"));
 const alive=()=>gm&&gm.act===a&&!gm.locked;
 objs.forEach(o=>{o.classList.remove("cnt");delete o.dataset.n});gm.tapped=0;
 await sayWait(Q.count,BASE,3000);
 for(let i=0;i<objs.length;i++){
  if(!alive())return;
  objs[i].classList.add("cnt");objs[i].dataset.n=String(i+1);gm.tapped=i+1;
  await sayWait(numberWord(i+1),BASE,2500);
 }
}
function start(kind){
 readPrefs();
 gm={kind:kind,round:0,score:0,last:-1,locked:false,tries:0,t:0,count:false,mute:false,dir:"",act:0};
 const g=GAMES[kind];
 scr.style.setProperty("--c1",g.c1);scr.style.setProperty("--c2",g.c2);
 $("gm-ico").textContent=g.ico;$("gm-title").textContent=g.title;
 $("gm-end").hidden=true;$("gm-stage").hidden=false;
 scr.hidden=false;document.body.classList.add("gm-open");
 warm(kind);
 next();
}
function next(){
 if(!gm)return;
 if(gm.round>=ROUNDS){finish();return}
 gm.tries=0;gm.locked=false;gm.count=false;gm.mute=false;gm.tapped=0;const a=++gm.act;
 hud();
 const op=$("gm-options"),pr=$("gm-prompt"),hear=$("gm-hear");
 op.textContent="";op.className="gms-opts";pr.textContent="";pr.className="gms-prompt";hear.hidden=false;setMascot("😺");
 const opts=[],k=gm.kind;let t;
 if(k==="letters"){
  const n=letters.length;do{t=rnd(n)}while(t===gm.last&&n>1);gm.last=t;gm.t=t;
  shuffle([t].concat(pick(n,3,t))).forEach(i=>opts.push({id:i,text:letters[i][0],cls:"gms-letter"}));
 }else if(k==="colors"){
  const n=colors.length;do{t=rnd(n)}while(t===gm.last&&n>1);gm.last=t;gm.t=t;
  shuffle([t].concat(pick(n,3,t))).forEach(i=>opts.push({id:i,color:colors[i][1],label:colors[i][0],cls:"gms-color"+(colors[i][1]==="#fff"?" gms-light":"")}));
 }else if(k==="match"||k==="matchl"){
  const n=letters.length;do{t=rnd(n)}while(t===gm.last&&n>1);gm.last=t;gm.t=t;
  gm.dir=k==="match"?"pic":"letter";
  const ids=shuffle([t].concat(pick(n,3,t)));
  const e=nextPic(t);gm.word=e[0];
  if(gm.dir==="pic"){pr.textContent=letters[t][0];pr.classList.add("big");ids.forEach(i=>opts.push({id:i,html:lsPic(i===t?e[1]:randPic(i)[1]),cls:"gms-pic"}))}
  else{pr.innerHTML=lsPic(e[1]);pr.classList.add("pic");ids.forEach(i=>opts.push({id:i,text:letters[i][0],cls:"gms-letter"}))}
 }else if(k==="more"){
  gm.mute=true;op.classList.add("two");
  const o=OBJ[rnd(OBJ.length)];let x,y;do{x=1+rnd(9);y=1+rnd(9)}while(x===y);
  gm.t=x>y?0:1;
  pr.textContent="أيّهما أكثر؟";pr.classList.add("small");
  [x,y].forEach((c,i)=>opts.push({id:i,html:Array(c+1).join(o+" "),cls:"gms-grp"}));
 }else if(k==="count"){
  gm.count=true;gm.mute=true;
  const top=Math.min(Math.max(5,Math.min(maxNumber,20)),10);do{t=1+rnd(top)}while(t===gm.last&&top>1);gm.last=t;gm.t=t;
  shuffle([t].concat(pick(top,3,t-1).map(x=>x+1))).forEach(i=>opts.push({id:i,text:String(i),cls:"gms-num"}));
  const o=OBJ[rnd(OBJ.length)];pr.classList.add("objs");if(t>5)pr.classList.add("many");
  for(let n=1;n<=t;n++){const sp=document.createElement("span");sp.className="gms-o";sp.textContent=o;sp.onclick=()=>tapObj(sp);pr.appendChild(sp)}
 }else{
  const N=Math.max(5,Math.min(maxNumber,20));
  do{t=1+rnd(N)}while(t===gm.last&&N>1);gm.last=t;gm.t=t;
  shuffle([t].concat(pick(N,3,t-1).map(x=>x+1))).forEach(i=>opts.push({id:i,text:String(i),cls:"gms-num"}));
 }
 opts.forEach((o,i)=>{
  const b=document.createElement("button");b.type="button";b.className="gms-opt "+o.cls;b.style.setProperty("--i",i);
  if(o.color){b.style.background=o.color;b.setAttribute("aria-label",o.label)}else if(o.html)b.innerHTML=o.html;else b.textContent=o.text;
  if(o.id===gm.t)b.dataset.ok="1";b.onclick=()=>answer(b,o.id===gm.t);
  op.appendChild(b);
 });
 setTimeout(()=>{if(gm&&gm.act===a&&!gm.locked){if(gm.mute)say(qArgs()[0],BASE);else speakTarget()}},450);
}

function hint(){
 const op=$("gm-options");
 const k=[].slice.call(op.children).find(x=>x.dataset.ok==="1");
 if(k&&!k.disabled)k.classList.add("gms-hint");
}
function wrong(btn,a){
 gm.tries++;
 btn.disabled=true;btn.classList.add("gms-off");
 btn.classList.add("gms-wrong");setTimeout(()=>btn.classList.remove("gms-wrong"),500);
 setMascot("🙀");try{if(navigator.vibrate)navigator.vibrate(60)}catch(e){}
 const again=gm.tries>=2;
 if(again)hint();
 sayWait(again?FB.again:FB.no,BASE,3500).then(()=>{
  if(gm&&gm.act===a){setMascot("😺");if(again){if(gm.mute)say(qArgs()[0],BASE);else speakTarget()}}
 });
}
async function roundDone(a,pre,praise){
 const alive=()=>gm&&gm.act===a;
 gm.locked=true;setMascot("😸");confetti(18);hud();
 try{
  if(pre){await sayWait(pre[0],pre[1],3500);if(!alive())return}
  await sayWait(praise,BASE,3000);if(!alive())return;
 }catch(e){}
 await sleep(250);if(!alive())return;
 gm.round++;hud();next();
}
const praiseFor=tries=>tries===0?FB.great:(tries===1?FB.good:FB.ok);
function answer(btn,ok){
 if(!gm||gm.locked||btn.disabled)return;
 const a=++gm.act;
 if(!ok){wrong(btn,a);return}
 btn.classList.add("gms-right");
 if(gm.tries===0)gm.score++;
 roundDone(a,reinforce()?targetArgs():null,praiseFor(gm.tries));
}
function finish(){
 const a=++gm.act;gm.locked=true;
 $("gm-stage").hidden=true;$("gm-end").hidden=false;
 const s=gm.score;
 $("gm-trophy").textContent=s>=8?"🏆":(s>=5?"🎉":"💪");
 $("gm-endstars").textContent=starsText(starsOf(s));
 $("gm-score").textContent=s+" / "+ROUNDS;
 $("gm-prog").style.width="100%";$("gm-count").textContent=ROUNDS+" / "+ROUNDS;
 setBest(gm.kind,s);confetti(s>=5?40:14);
 const ph=s>=8?FB.great:(s>=5?FB.good:FB.again);
 setTimeout(()=>{if(gm&&gm.act===a)say(ph,BASE)},300);
}
function closeGame(){
 cancelWait();gm=null;scr.hidden=true;document.body.classList.remove("gm-open");
 try{if(window.TomNative&&TomNative.stop)TomNative.stop()}catch(e){}
 refreshHub();
}
function openGames(){
 [].slice.call(document.querySelectorAll(".mode-page")).forEach(p=>p.classList.remove("active"));
 [].slice.call(document.querySelectorAll(".mode-switch .mode")).forEach(b=>b.classList.remove("active"));
 sec.classList.add("active");btn.classList.add("active");
 const s=$("subtitle");if(s)s.textContent="نلعب ونتعلّم";
 closeGame();
}
function leaveGames(){closeGame();sec.classList.remove("active");btn.classList.remove("active")}
btn.onclick=openGames;
[].slice.call(sw.querySelectorAll(".mode")).forEach(b=>{if(b!==btn)b.addEventListener("click",leaveGames)});
[].slice.call(sec.querySelectorAll(".gh-card")).forEach(c=>{c.onclick=()=>start(c.dataset.game)});
$("gm-back").onclick=closeGame;
$("gm-menu").onclick=closeGame;
$("gm-hear").onclick=()=>{
 if(!gm||gm.locked)return;
 if(gm.kind==="count"){countAloud();return}
 gm.act++;
 if(gm.mute)say(qArgs()[0],BASE);else speakTarget();
};
$("gm-again").onclick=()=>start(gm?gm.kind:"letters");
/* زر الرجوع في الجهاز: يُستدعى من MainActivity.onBackPressed */
window.gamesBack=function(){if(!scr.hidden){closeGame();return true}return false};

/* ================= قسم «استمع وتعلّم»: نطق متسلسل مع عرض كبير للرقم/الحرف/اللون ================= */
const LSK={
 numbers:{ico:"🔢",label:"أرقام",c1:"#4facfe",c2:"#3b5bdb"},
 letters:{ico:"🔤",label:"حروف",c1:"#ff9a5a",c2:"#f2506e"},
 colors:{ico:"🎨",label:"ألوان",c1:"#b57bee",c2:"#6a3de8"}
};
const LSCSS=`
#ls-screen{position:fixed;inset:0;z-index:9999;display:flex;flex-direction:column;color:#fff;background:linear-gradient(160deg,var(--c1),var(--c2));padding:max(env(safe-area-inset-top,0px),14px) 16px max(env(safe-area-inset-bottom,0px),16px);overflow:hidden;animation:gmScr .28s ease-out;user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent}
#ls-screen[hidden]{display:none!important}
#ls-screen::before,#ls-screen::after{content:"";position:absolute;border-radius:50%;background:rgba(255,255,255,.11);pointer-events:none}
#ls-screen::before{width:320px;height:320px;top:-130px;left:-110px}
#ls-screen::after{width:260px;height:260px;bottom:-100px;right:-90px}
.ls-top{position:relative;z-index:1;display:flex;align-items:center;gap:10px}
.ls-top .gms-title{flex:1}
.ls-tabs{position:relative;z-index:1;display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin:12px 0 8px}
.ls-tab{border:0;border-radius:18px;padding:10px 4px;font-size:18px;font-weight:900;color:#fff;background:rgba(255,255,255,.22)}
.ls-tab.on{background:#fff;color:var(--c2);box-shadow:0 4px 0 rgba(0,0,0,.18)}
.ls-stage{position:relative;z-index:1;flex:1;min-height:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:14px;text-align:center;overflow:hidden}
.ls-big{font-weight:900;line-height:1.05;text-shadow:0 6px 0 rgba(0,0,0,.18);animation:gmIn .4s both}
.ls-big.num{font-size:min(46vw,26vh,230px)}
.ls-big.num.l3{font-size:min(32vw,19vh,170px)}
.ls-big.let{font-size:min(30vw,15vh,140px);line-height:1.8;padding:.05em 0}
.ls-circle{width:min(62vw,250px);height:min(62vw,250px);border-radius:50%;border:10px solid rgba(255,255,255,.95);box-shadow:0 10px 0 rgba(0,0,0,.2),inset 0 -14px 0 rgba(0,0,0,.1);animation:gmIn .4s both}
.ls-circle.light{border-color:#d9d9d9}
.lsx-pic{flex:none;width:clamp(110px,32vw,140px);height:clamp(110px,32vw,140px);display:grid;place-items:center;background:#ffffffd9;border-radius:22px;overflow:hidden}
.lsx-pic svg{width:76%;height:76%}
.lsx-pic .ls-emoji{font-size:clamp(60px,18vw,84px);line-height:1}
.ls-stage>*{flex:none}
.ls-b svg{width:40%;height:40%;fill:currentColor}
.ls-b.main svg{width:46%;height:46%}
.ls-sub{font-size:clamp(28px,8vw,40px);font-weight:900;background:rgba(255,255,255,.24);border-radius:999px;padding:8px 26px;max-width:100%}
.ls-bar{position:relative;z-index:1;height:12px;border-radius:99px;background:rgba(0,0,0,.2);overflow:hidden;margin:6px 2px}
.ls-bar i{display:block;height:100%;width:0;border-radius:inherit;background:linear-gradient(90deg,#fff7ae,#ffd93b);transition:width .4s}
.ls-ctl{position:relative;z-index:1;display:flex;direction:ltr;align-items:center;justify-content:center;gap:14px;margin-top:8px}
.ls-b{border:0;border-radius:50%;width:64px;height:64px;font-size:28px;background:rgba(255,255,255,.26);color:#fff;display:grid;place-items:center}
.ls-b.main{width:88px;height:88px;font-size:42px;background:#fff;color:var(--c2);box-shadow:0 8px 0 rgba(0,0,0,.2)}
.ls-b:active{transform:translateY(4px)}
.ls-pulse{animation:gmPop .5s}
`;
const lst=document.createElement("style");lst.textContent=LSCSS;document.head.appendChild(lst);

const lsScr=document.createElement("div");lsScr.id="ls-screen";lsScr.hidden=true;lsScr.setAttribute("role","dialog");
lsScr.innerHTML='<header class="ls-top"><button id="ls-back" class="gms-x" type="button" aria-label="رجوع">✕</button><div class="gms-title"><span>🎧</span><span>استمع وتعلّم</span></div><div id="lsx-count" class="gms-pill">1 / 10</div></header>'+
 '<nav class="ls-tabs">'+Object.keys(LSK).map(k=>'<button type="button" class="ls-tab" data-k="'+k+'">'+LSK[k].ico+" "+LSK[k].label+'</button>').join("")+'</nav>'+
 '<div class="ls-bar"><i id="ls-prog"></i></div>'+
 '<main id="ls-stage" class="ls-stage"></main>'+
 '<div class="ls-ctl"><button id="lsx-prev" class="ls-b" type="button" aria-label="التالي">⏮</button><button id="lsx-play" class="ls-b main" type="button" aria-label="تشغيل"></button><button id="lsx-next" class="ls-b" type="button" aria-label="السابق">⏭</button></div>';
document.body.appendChild(lsScr);

let ls=null;
const IC_PAUSE='<svg viewBox="0 0 24 24"><path d="M6 4h4v16H6zM14 4h4v16h-4z"/></svg>',IC_PLAY='<svg viewBox="0 0 24 24"><path d="M7 4l13 8-13 8z"/></svg>',IC_REPLAY='<svg viewBox="0 0 24 24"><path d="M12 5V1L7 6l5 5V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z"/></svg>';
const lsTotal=kind=>kind==="numbers"?maxNumber:(kind==="letters"?letters.length:colors.length);
function lsItem(kind,i){
 if(kind==="numbers"){const n=i+1,w=numberWord(n);return{big:'<div class="ls-big num'+(String(n).length>=3?" l3":"")+'">'+n+'</div>',sub:w,say:[w,BASE]}}
 if(kind==="letters"){return{big:'<div class="ls-big let">'+letters[i][0]+'</div>',pic:lsPic(lessonWords[i][1]),sub:letters[i][1],say:learnArgs(letters[i][1])}}
 const c=colors[i];return{big:'<div class="ls-circle'+(c[1]==="#fff"?" light":"")+'" style="background:'+c[1]+'"></div>',sub:c[0],say:[c[0],BASE]};
}
function lsRender(){
 const it=lsItem(ls.kind,ls.i),st=$("ls-stage"),n=lsTotal(ls.kind);
 st.innerHTML=it.big+(it.pic?'<div class="lsx-pic">'+it.pic+'</div>':"")+'<div class="ls-sub">'+it.sub+'</div>';
 st.firstChild.onclick=()=>lsGo(ls.i);
 $("lsx-count").textContent=(ls.i+1)+" / "+n;
 $("ls-prog").style.width=((ls.i+1)/n*100)+"%";
 $("lsx-play").innerHTML=ls.done?IC_REPLAY:(ls.playing?IC_PAUSE:IC_PLAY);$("lsx-play").setAttribute("aria-label",ls.done?"من البداية":(ls.playing?"إيقاف":"تشغيل"));
}
async function lsGo(i){
 if(!ls)return;
 const n=lsTotal(ls.kind);ls.i=Math.max(0,Math.min(n-1,i));
 ls.done=false;const tok=++ls.tok;lsRender();
 const it=lsItem(ls.kind,ls.i);
 await sayWait(it.say[0],it.say[1],6000);
 if(!ls||ls.tok!==tok||!ls.playing)return;
 await sleep(1100);
 if(!ls||ls.tok!==tok||!ls.playing)return;
 if(ls.i<n-1)lsGo(ls.i+1);else{ls.playing=false;ls.done=true;lsRender()}
}
function lsStart(kind){
 readPrefs();closeGame();
 ls={kind:kind,i:0,playing:true,tok:0,done:false};
 const k=LSK[kind];lsScr.style.setProperty("--c1",k.c1);lsScr.style.setProperty("--c2",k.c2);
 [].slice.call(lsScr.querySelectorAll(".ls-tab")).forEach(b=>b.classList.toggle("on",b.dataset.k===kind));
 lsScr.hidden=false;document.body.classList.add("gm-open");
 if(window.TomNative&&TomNative.warm){
  const items=[],seen=new Set();
  for(let i=0;i<Math.min(lsTotal(kind),30);i++){const a=lsItem(kind,i).say,key=a[0]+"|"+a[1];if(!seen.has(key)){seen.add(key);items.push([a[0],a[1]])}}
  try{TomNative.warm(JSON.stringify(items))}catch(e){}
 }
 lsGo(0);
}
function lsClose(){
 cancelWait();if(ls)ls.tok++;ls=null;lsScr.hidden=true;document.body.classList.remove("gm-open");
 try{if(window.TomNative&&TomNative.stop)TomNative.stop()}catch(e){}
}
[].slice.call(lsScr.querySelectorAll(".ls-tab")).forEach(b=>{b.onclick=()=>lsStart(b.dataset.k)});
$("ls-back").onclick=lsClose;
/* الزر الأيسر (⏮) = التالي، والأيمن (⏭) = السابق، بما يوافق اتجاه القراءة العربي */
$("lsx-next").onclick=()=>{if(ls)lsGo(ls.i-1)};
$("lsx-prev").onclick=()=>{if(ls)lsGo(ls.i+1)};
$("lsx-play").onclick=()=>{
 if(!ls)return;
 if(ls.done){ls.playing=true;lsGo(0);return}
 if(ls.playing){ls.playing=false;ls.tok++;cancelWait();try{if(window.TomNative&&TomNative.stop)TomNative.stop()}catch(e){}lsRender()}
 else{ls.playing=true;lsGo(ls.i)}
};
/* عند مغادرة التطبيق يُوقَف التسلسل ولا يُستأنف من تلقاء نفسه */
window.appPaused=function(){
 if(ls&&ls.playing){ls.playing=false;ls.tok++;cancelWait();lsRender()}
};
const lsCard=document.createElement("button");lsCard.type="button";lsCard.className="gh-card";lsCard.style.cssText="--c1:#fbbf24;--c2:#f97316";
lsCard.innerHTML='<span class="gh-ico">🎧</span><span class="gh-txt"><b>استمع وتعلّم</b><small>أرقام وحروف وألوان بالتسلسل</small></span><span class="gh-best">▶</span>';
lsCard.onclick=()=>lsStart("numbers");
sec.querySelector(".gh-list").insertBefore(lsCard,sec.querySelector(".gh-list").firstChild);
const prevBack=window.gamesBack;
window.gamesBack=function(){if(!lsScr.hidden){lsClose();return true}return prevBack?prevBack():false};
['learning-mode','lessons-mode'].forEach(id=>{const b=$(id);if(b)b.addEventListener("click",lsClose)});
refreshHub();
})();
