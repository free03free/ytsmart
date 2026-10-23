#!/bin/sh
# L2S: غلاف عام (curl / wget / python3 / pip / node / npm ...): يطبّق قواعد التخطي، ويسجّل الأوامر البطيئة (3 ثوانٍ فأكثر)، ثم ينفّذ الأصلي.
# السجل: R = ما زال يعمل بعد 3 ثوانٍ، H = نبضة كل 5 ثوانٍ (تحفظ آخر لحظة كان حيًّا فيها)، D = انتهى وأخذ وقتًا.
# لو أُوقف البناء أو أُغلق التطبيق وهو عالق يبقى R/H فقط، فيُعرف أنه كان عالقًا وكم مضى عليه.
L2S_CMD_NAME="$(basename "$0")"
WRAPDIR="${L2S_WRAPDIR:-/opt/l2s/wrap}"
SKIPLIB="${L2S_SKIPLIB:-$WRAPDIR/l2s-skipcheck.sh}"
if [ -r "$SKIPLIB" ]; then
  . "$SKIPLIB"
  if l2s_skip_check "$@"; then exit 0; fi
fi
REALBIN=""
if [ -x "$WRAPDIR/l2s-$L2S_CMD_NAME-cache" ]; then
  REALBIN="$WRAPDIR/l2s-$L2S_CMD_NAME-cache"   # غلاف ذاكرة التنزيلات (يستدعي الأصلي بنفسه)
else
  for d in /usr/local/bin /usr/bin /bin; do
    [ -x "$d/$L2S_CMD_NAME" ] && { REALBIN="$d/$L2S_CMD_NAME"; break; }
  done
fi
if [ -z "$REALBIN" ]; then echo "$L2S_CMD_NAME: command not found" >&2; exit 127; fi
LOG="${L2S_CMDLOG:-/opt/l2s/cmd-log.txt}"
line=$(printf '%s %s' "$L2S_CMD_NAME" "$*" | tr '\n\t' '  ' | cut -c1-200)
me=$$
t0=$(date +%s)
(
  sleep 3
  kill -0 "$me" 2>/dev/null || exit 0
  printf 'R\t%s\t%s\t%s\n' "$me" "$t0" "$line" >> "$LOG"
  while sleep 5 && kill -0 "$me" 2>/dev/null; do
    printf 'H\t%s\t%s\n' "$me" "$(date +%s)" >> "$LOG"
  done
) >/dev/null 2>&1 </dev/null &
"$REALBIN" "$@"
rc=$?
el=$(( $(date +%s) - t0 ))
if [ "$el" -ge 3 ]; then
  printf 'D\t%s\t%s\t%s\n' "$me" "$el" "$line" >> "$LOG" 2>/dev/null
fi
exit $rc
