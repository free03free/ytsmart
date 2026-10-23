# L2S: قواعد تخطي الأوامر لكل مشروع (تُحمَّل بـ "." من أغلفة curl و wget و l2s-cmdshim)
# الملف /opt/l2s/skip-rules.txt: كل سطر نصّ جزئي؛ إن ورد في "اسم_الأمر وسيطاته" يُتخطى الأمر ويرجع 0.
# أسطر تبدأ بـ # تُتجاهل. المتغير L2S_CMD_NAME يحمل اسم الأمر.
l2s_skip_check() {
  rf="${L2S_SKIP_RULES:-/opt/l2s/skip-rules.txt}"
  [ -s "$rf" ] || return 1
  cmdline="$L2S_CMD_NAME $*"
  while IFS= read -r rule || [ -n "$rule" ]; do
    rule="${rule%$(printf '\r')}"
    case "$rule" in ""|"#"*) continue ;; esac
    case "$cmdline" in
      *"$rule"*) echo "L2S-SKIP: تُخطّي الأمر (يطابق القاعدة «$rule»): $cmdline" >&2; return 0 ;;
    esac
  done < "$rf"
  return 1
}
