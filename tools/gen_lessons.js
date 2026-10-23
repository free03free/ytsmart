#!/usr/bin/env node
/* يولّد tools/lessons.json (قائمة النصوص التي تُجهَّز أصواتها مسبقًا) من app/src/main/assets/speech-data.js
   ويمرّر كل نص على مدقّق التشكيل أولًا؛ أي خطأ يوقف التوليد.  الاستعمال:  node tools/gen_lessons.js */
"use strict";
const fs = require("fs"), path = require("path");
const S = require("../app/src/main/assets/speech-data.js");
const { lintAll } = require("./lint_arabic.js");

const BASE = 0.9, spd = k => Number((BASE * k).toFixed(3));
function learnArgs(text) {
  const t = S.SAY[text] || text, k = S.sylKind(t);
  return [t, k === "short" ? spd(0.8) : k === "madd" ? spd(0.84) : spd(0.91)];
}
const items = [], seen = new Set();
function add(text, sp, num) {
  const key = text + "|" + sp;
  if (seen.has(key)) return; seen.add(key);
  items.push(num === undefined ? [text, sp] : [text, sp, num]);
}
const lintList = [];
S.letters.forEach((L, i) => {
  const l = L[0];
  lintList.push(["name", L[1]]);
  let a = learnArgs(L[1]); add(a[0], a[1]);
  S.harakat(l).concat(S.madds(l)).forEach(x => { lintList.push(["syl", x[0]]); a = learnArgs(x[0]); add(a[0], a[1]); });
  S.words[i].forEach(w => { lintList.push(["word", w]); a = learnArgs(w); add(a[0], a[1]); });
  // كلمات الصور الإضافية في المطابقة
  S.pictures[i].forEach(e => { lintList.push(["word", e[0]]); a = learnArgs(e[0]); add(a[0], a[1]); });
  // «الاسم، الكلمة» في وضع الدروس
  const w0 = S.SAY[S.lessonWords[i][0]] || S.lessonWords[i][0];
  a = learnArgs(L[1] + "، " + w0); add(a[0], a[1]);
});
S.colors.forEach(c => { lintList.push(["word", c[0]]); add(c[0], BASE); });
for (let n = 1; n <= 1000; n++) { const w = S.numberWord(n); lintList.push(["num", w]); add(w, BASE, n); }
const phrases = ["مَرْحَبًا أَنَا القِطُّ المُتَكَلِّم", "أَحْسَنْتَ", "جَيِّد", "صَحِيح", "خَطَأ", "أَعِدِ المُحَاوَلَةَ مَرَّةً أُخْرَى", "تَمَّ حِفْظُ الإِعْدَادَات", "كَمْ عَدَدُهَا", "أَيُّهُمَا أَكْثَر"];
phrases.forEach(p => { lintList.push(["phrase", p]); add(p, BASE); });

const problems = lintAll(lintList);
if (problems.length) { console.error("أخطاء تشكيل (" + problems.length + "):\n" + problems.slice(0, 20).join("\n")); process.exit(1); }
// الصورة والكلمة: الكلمة الأولى لكل حرف يجب أن تطابق كلمة الصورة
S.lessonWords.forEach((w, i) => { if (w[0] !== S.words[i][0]) { console.error("عدم تطابق كلمة الصورة للحرف " + S.letters[i][0]); process.exit(1); } });
// كل صورة فريدة، وكل كلمة إضافية تبدأ بحرفها
{ const seenP = new Map();
  S.pictures.forEach((L, i) => L.forEach(e => {
    const first = e[0].replace(/[\u064B-\u0652]/g, "")[0], letter = S.letters[i][0];
    const ok = first === letter || (letter === "أ" && "أإا".includes(first)) || (letter === "ه" && first === "ه");
    if (!ok) { console.error("الكلمة لا تبدأ بحرفها: " + e[0] + " ← " + letter); process.exit(1); }
    if (seenP.has(e[1])) { console.error("صورة مكررة " + e[1] + ": " + seenP.get(e[1]) + " و " + e[0]); process.exit(1); }
    seenP.set(e[1], e[0]);
  }));
}
fs.writeFileSync(path.join(__dirname, "lessons.json"), "[\n" + items.map(r => JSON.stringify(r)).join(",\n") + "\n]\n");
console.log("lessons.json:", items.length, "عنصرًا؛ اجتاز المدقّق", lintList.length, "نصًا");
