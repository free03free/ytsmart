#!/bin/sh
# L2S: ذاكرة تنزيلات مشتركة بين كل المشاريع (غلاف curl)
REAL=/usr/bin/curl
[ -x "$REAL" ] || REAL=/bin/curl
[ "$L2S_DL_CACHE" = "1" ] || exec "$REAL" "$@"
COMMON="${L2S_COMMON:-/opt/l2s/wrap/l2s-dl-common.sh}"
[ -r "$COMMON" ] || exec "$REAL" "$@"

url=""; out=""; remote=0; bad=0; n=0; expect=""
for a in "$@"; do
  if [ -n "$expect" ]; then
    case "$expect" in
      o) out="$a" ;;
      h) case "$a" in *[Aa]uthorization*|*[Cc]ookie*) bad=1 ;; esac ;;
    esac
    expect=""; continue
  fi
  case "$a" in
    http://*|https://*) n=$((n+1)); url="$a" ;;
    -o|--output) expect=o ;;
    --output=*) out="${a#--output=}" ;;
    -O|--remote-name) remote=1 ;;
    -H|--header) expect=h ;;
    -A|--user-agent|-e|--referer|--retry|--retry-delay|--retry-max-time|--connect-timeout|-m|--max-time|--max-redirs|--limit-rate|--proto) expect=s ;;
    -X|--request|-d|--data|--data-*|-F|--form|-T|--upload-file|-I|--head|-u|--user|-b|--cookie|-c|--cookie-jar|-r|--range|-C|--continue-at|-w|--write-out|-K|--config|-G|--get|-z|--time-cond|--output-dir|--create-dirs|--json|--url|-Z|--parallel|--remote-name-all) bad=1 ;;
    --*) : ;;
    -[A-Za-z]*o) expect=o ;;
    -[A-Za-z]*O) remote=1 ;;
    *) : ;;
  esac
done

base="${url%%[?#]*}"; fname="${base##*/}"
. "$COMMON"
if [ "$n" -ne 1 ] || [ "$bad" -ne 0 ] || [ -z "$fname" ] || ! l2s_ok_name "$url" "$fname"; then exec "$REAL" "$@"; fi

dest=""
if [ -n "$out" ] && [ "$out" != "-" ]; then dest="$out"
elif [ -z "$out" ] && [ "$remote" -eq 1 ]; then dest="$fname"
fi

fetch_part() { "$REAL" -fL -sS --retry 3 --retry-delay 2 -C - -o "$1" "$url"; }
l2s_main "$@"
