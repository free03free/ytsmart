#!/bin/sh
# L2S: ذاكرة تنزيلات مشتركة بين كل المشاريع (غلاف wget)
REAL=/usr/bin/wget
[ -x "$REAL" ] || REAL=/bin/wget
[ "$L2S_DL_CACHE" = "1" ] || exec "$REAL" "$@"
COMMON="${L2S_COMMON:-/opt/l2s/wrap/l2s-dl-common.sh}"
[ -r "$COMMON" ] || exec "$REAL" "$@"

url=""; out=""; bad=0; n=0; expect=""
for a in "$@"; do
  if [ -n "$expect" ]; then
    case "$expect" in o) out="$a" ;; esac
    expect=""; continue
  fi
  case "$a" in
    http://*|https://*) n=$((n+1)); url="$a" ;;
    -O|--output-document) expect=o ;;
    --output-document=*) out="${a#--output-document=}" ;;
    -t|-T|-w|-U|-e|--tries|--timeout|--user-agent|--referer) expect=s ;;
    -P|--directory-prefix|--directory-prefix=*|-c|--continue|--post-data=*|--post-file=*|-i|--input-file|-r|--recursive|-m|--mirror|-p|--page-requisites|--spider|-o|-a|-N|--timestamping|--header=*[Aa]uthorization*|--header=*[Cc]ookie*|--user=*|--password=*|--http-user=*|--http-password=*|--load-cookies=*) bad=1 ;;
    --*) : ;;
    -[A-Za-z]*O) expect=o ;;
    *) : ;;
  esac
done

base="${url%%[?#]*}"; fname="${base##*/}"
. "$COMMON"
if [ "$n" -ne 1 ] || [ "$bad" -ne 0 ] || [ -z "$fname" ] || ! l2s_ok_name "$url" "$fname"; then exec "$REAL" "$@"; fi

dest=""
if [ -n "$out" ] && [ "$out" != "-" ]; then dest="$out"
elif [ -z "$out" ]; then
  [ -e "$fname" ] && exec "$REAL" "$@"
  dest="$fname"
fi

fetch_part() { "$REAL" -q -c --tries=3 -O "$1" "$url"; }
l2s_main "$@"
