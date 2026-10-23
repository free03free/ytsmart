#!/bin/sh
# l2s-memo: يتخطى تنفيذ أمر إذا لم تتغير ملفات مدخلاته، ويعيد مخرجاته من الذاكرة.
#
# الاستخدام:
#   l2s-memo [-k مفتاح_إضافي] [-i مدخل]... [-o مخرج]... -- الأمر وسيطاته
#   -i ملف أو مجلد يؤثر على الناتج (يُحسب محتواه، والمجلدات بشكل متكرر)
#   -o ملف أو مجلد ينتجه الأمر (يُحفظ ويُستعاد عند التخطي)
#   -k نص يُضاف للمفتاح (مثل رقم إصدار)
# مثال:  l2s-memo -i gen.py -i data/ -o src/main/assets/gen -- python3 gen.py
#
# يتخطى فقط عندما: نفس الأمر + نفس محتوى كل المدخلات + نفس المفتاح، وسبق أن نجح.
# لا يُخزَّن إلا عند نجاح الأمر (رمز 0) ووجود كل المخرجات المعلنة.
# L2S_MEMO_FORCE=1 يفرض التنفيذ دائمًا، و L2S_MEMO=0 يعطّل الذاكرة.
STORE="${L2S_MEMO_DIR:-/root/.gradle/l2s-memo}"
usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; }
key=""; ins=""; outs=""
NL='
'
while [ $# -gt 0 ]; do
  case "$1" in
    -k) key="$2"; shift 2 ;;
    -i) ins="$ins$NL$2"; shift 2 ;;
    -o) outs="$outs$NL$2"; shift 2 ;;
    --) shift; break ;;
    -h|--help) usage; exit 0 ;;
    *) break ;;
  esac
done
[ $# -gt 0 ] || { usage >&2; exit 2; }
if [ "$L2S_MEMO" != "1" ] || [ "$L2S_MEMO_FORCE" = "1" ]; then exec "$@"; fi

abs() { case "$1" in /*) p="$1" ;; *) p="$PWD/$1" ;; esac; printf '%s' "${p%/}"; }

sum_paths() { # $1 = قائمة مسارات مفصولة بأسطر
  printf '%s\n' "$1" | while IFS= read -r p; do
    [ -n "$p" ] || continue
    if [ -d "$p" ]; then find "$p" -type f -print0 | sort -z | xargs -0 sha256sum 2>/dev/null
    elif [ -f "$p" ]; then sha256sum "$p"
    else echo "missing:$p"; fi
  done | sha256sum | cut -d' ' -f1
}

in_abs=""; for p in $(printf '%s' "$ins" | tr '\n' ' '); do in_abs="$in_abs$NL$(abs "$p")"; done
out_abs=""; for p in $(printf '%s' "$outs" | tr '\n' ' '); do out_abs="$out_abs$NL$(abs "$p")"; done

h=$( { printf '%s\n' "$key"; for a in "$@"; do printf '%s\n' "$a"; done; sum_paths "$in_abs"; } | sha256sum | cut -d' ' -f1 )
d="$STORE/$h"

if [ -f "$d/done" ]; then
  ok=1
  if [ -f "$d/out.tar" ]; then
    now=$(sum_paths "$out_abs")
    if [ "$now" != "$(cat "$d/out.sum" 2>/dev/null)" ]; then
      printf '%s\n' "$out_abs" | while IFS= read -r p; do [ -n "$p" ] && rm -rf "$p"; done
      tar -xPf "$d/out.tar" 2>/dev/null && ok=1 || ok=0
      [ "$ok" -eq 1 ] && echo "L2S-MEMO: أُعيدت المخرجات من الذاكرة" >&2
    fi
  fi
  if [ "$ok" -eq 1 ]; then
    touch "$d/done"
    echo "L2S-MEMO: تخطي (لم تتغير المدخلات): $*" >&2
    exit 0
  fi
  rm -rf "$d"
fi

"$@"; rc=$?
[ "$rc" -eq 0 ] || exit "$rc"

mkdir -p "$d" 2>/dev/null || exit 0
if [ -n "$out_abs" ]; then
  missing=0; list=""
  for p in $(printf '%s' "$out_abs" | tr '\n' ' '); do [ -e "$p" ] || missing=1; list="$list $p"; done
  if [ "$missing" -eq 1 ]; then
    echo "L2S-MEMO: مخرج معلن غير موجود بعد التنفيذ، لن يُحفظ في الذاكرة" >&2
    rm -rf "$d"; exit 0
  fi
  tar -cPf "$d/out.tar.tmp" $list 2>/dev/null && mv "$d/out.tar.tmp" "$d/out.tar" || { rm -rf "$d"; exit 0; }
  sum_paths "$out_abs" > "$d/out.sum"
fi
printf '%s\n' "$*" > "$d/cmd"
touch "$d/done"
echo "L2S-MEMO: نُفّذ وحُفظ للمرات القادمة" >&2
exit 0
