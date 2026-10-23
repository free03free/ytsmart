# L2S: منطق مشترك لذاكرة التنزيلات (يُحمَّل من غلافَي curl و wget)
# المتغيرات المتوقعة: REAL url dest fname (و fetch_part معرّفة في الغلاف)
CACHE=/root/.gradle/l2s-dl-cache
L2S_TTL="${L2S_DL_TTL:-30}"

l2s_log() { echo "L2S: $*" >&2; }

# هل الملف مناسب للتخزين؟ (يرفض الروابط الديناميكية والنصوص المتغيرة)
l2s_ok_name() {
  case "$1" in *\?*|*\#*|*/latest|*/latest/*) return 1 ;; esac
  case "$2" in *.*) ;; *) return 1 ;; esac
  case "$2" in
    *.html|*.htm|*.php|*.asp|*.aspx|*.jsp|*.cgi|*.json|*.xml|*.txt|*.js|*.css|*.md|*.csv|*.yml|*.yaml|*.sh|*.py|*.properties|*.sig|*.asc|*.sha1|*.sha256|*.sha512|*.md5|*.sha256sum|*.pom.sha1) return 1 ;;
  esac
  return 0
}

# ترويسات الخادم: etag|length|last-modified (فارغ إن تعذّر الوصول)
l2s_remote_meta() {
  h=""
  if [ -x /usr/bin/curl ]; then
    h=$(/usr/bin/curl -sIL --connect-timeout 5 --max-time 10 "$1" 2>/dev/null | tr -d '\r')
  elif [ -x /usr/bin/wget ]; then
    h=$(/usr/bin/wget -q -S --spider --timeout=8 --tries=1 "$1" 2>&1 | tr -d '\r' | sed 's/^  //')
  fi
  [ -n "$h" ] || return 0
  case "$h" in HTTP/*" 2"*|HTTP/*" 3"*) ;; *) return 0 ;; esac
  et=$(printf '%s\n' "$h" | grep -i '^etag:' | tail -n1 | cut -d: -f2- | tr -d ' ')
  ln=$(printf '%s\n' "$h" | grep -i '^content-length:' | tail -n1 | cut -d: -f2- | tr -d ' ')
  lm=$(printf '%s\n' "$h" | grep -i '^last-modified:' | tail -n1 | cut -d: -f2- | sed 's/^ *//')
  printf '%s|%s|%s' "$et" "$ln" "$lm"
}

# هل ما في الذاكرة ما زال مطابقًا للخادم؟ 0=مطابق 1=تغيّر 2=تعذّر التحقق
l2s_same_meta() {
  [ -n "$1" ] || return 2
  [ -f "$f.meta" ] || return 1
  old=$(cat "$f.meta")
  [ "$old" = "$1" ] && return 0
  oe="${old%%|*}"; ne="${1%%|*}"
  if [ -n "$oe" ] && [ -n "$ne" ]; then
    [ "$oe" = "$ne" ] && return 0 || return 1
  fi
  orest="${old#*|}"; nrest="${1#*|}"
  [ "$orest" = "$nrest" ] && return 0
  return 1
}

# فحص سلامة الملف قبل تخزينه: لا صفحة خطأ HTML، الحجم يطابق الخادم، والأرشيف سليم
l2s_verify() {
  file="$1"; name="$2"; rmeta="$3"
  [ -s "$file" ] || return 1
  if head -c 64 "$file" 2>/dev/null | grep -qi '<!doctype html\|<html'; then
    case "$name" in *.html|*.htm) ;; *) return 1 ;; esac
  fi
  exp=""
  [ -n "$rmeta" ] && { t="${rmeta#*|}"; exp="${t%%|*}"; }
  if [ -n "$exp" ]; then
    got=$(wc -c < "$file" | tr -d ' ')
    [ "$got" = "$exp" ] || return 1
  fi
  case "$name" in
    *.zip|*.jar|*.aar|*.apk|*.aab|*.whl) command -v unzip >/dev/null 2>&1 && { unzip -tqq "$file" >/dev/null 2>&1 || return 1; } ;;
    *.tar.gz|*.tgz|*.gz) command -v gzip >/dev/null 2>&1 && { gzip -t "$file" 2>/dev/null || return 1; } ;;
    *.tar.bz2|*.tbz2|*.bz2) command -v bzip2 >/dev/null 2>&1 && { bzip2 -t "$file" 2>/dev/null || return 1; } ;;
    *.tar.xz|*.txz|*.xz) command -v xz >/dev/null 2>&1 && { xz -t "$file" 2>/dev/null || return 1; } ;;
    *.tar) tar -tf "$file" >/dev/null 2>&1 || return 1 ;;
  esac
  return 0
}

l2s_deliver() {
  if [ -n "$dest" ]; then cp "$f" "$dest" 2>/dev/null
  else cat "$f"; fi
}

l2s_main() {
  key=$(printf '%s' "$url" | sha256sum | cut -d' ' -f1)
  f="$CACHE/$key"
  mkdir -p "$CACHE" 2>/dev/null || exec "$REAL" "$@"
  rmeta=$(l2s_remote_meta "$url")

  # 1) موجود في الذاكرة: تحقق من الخادم قبل الاستخدام
  if [ -s "$f" ] && [ -n "$(find "$f" -mtime -"$L2S_TTL" 2>/dev/null)" ]; then
    l2s_same_meta "$rmeta"; st=$?
    if [ "$st" -eq 0 ]; then
      l2s_deliver && { l2s_log "من ذاكرة التنزيلات (مطابق للخادم): $fname"; exit 0; }
    elif [ "$st" -eq 2 ]; then
      l2s_deliver && { l2s_log "من ذاكرة التنزيلات (تعذّر الاتصال للتحقق): $fname"; exit 0; }
    else
      l2s_log "تغيّر الملف على الخادم، سيُعاد تنزيله: $fname"
      rm -f "$f" "$f.meta" "$f.url" "$f.part" "$f.part.meta"
    fi
  else
    rm -f "$f" "$f.meta" "$f.url" 2>/dev/null
  fi

  # 2) تنزيل قابل للاستئناف إلى ملف جزئي داخل الذاكرة
  if [ -f "$f.part" ] && [ -n "$rmeta" ] && [ "$(cat "$f.part.meta" 2>/dev/null)" != "$rmeta" ]; then
    rm -f "$f.part" "$f.part.meta"    # بدأ التنزيل الجزئي لنسخة قديمة من الملف
  fi
  [ -n "$rmeta" ] && printf '%s' "$rmeta" > "$f.part.meta"
  [ -s "$f.part" ] && l2s_log "استئناف تنزيل غير مكتمل: $fname"
  fetch_part "$f.part" || {
    rm -f "$f.part"                      # قد يكون الجزئي تالفًا أو الخادم لا يدعم الاستئناف
    fetch_part "$f.part" || rm -f "$f.part"
  }

  if [ -s "$f.part" ] && l2s_verify "$f.part" "$fname" "$rmeta"; then
    mv "$f.part" "$f" && printf '%s' "$rmeta" > "$f.meta" && printf '%s\n' "$url" > "$f.url"
    rm -f "$f.part.meta"
    l2s_deliver && exit 0
  fi
  rm -f "$f.part" "$f.part.meta"

  # 3) فشل أو ملف غير سليم: نفّذ الأمر الأصلي كما كتبه المشروع بلا تخزين
  l2s_log "تعذّر التنزيل المُخزَّن، تشغيل الأمر الأصلي: $fname"
  exec "$REAL" "$@"
}
