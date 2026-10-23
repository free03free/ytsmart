# L2S: ذاكرة تنزيلات مشتركة لسكربتات بايثون التي تستخدم urllib.request.urlretrieve
# نفس سياسة غلاف curl: مفتاح = الرابط، تحقق من الخادم (ETag/الحجم/التاريخ)، سلامة الأرشيف، وأي خطأ => السلوك الأصلي.
import os, hashlib, shutil, time, zipfile, gzip, bz2, lzma, tarfile, urllib.request, urllib.parse

_CACHE = os.environ.get("L2S_DL_CACHE_DIR", "/root/.gradle/l2s-dl-cache")
_TTL = float(os.environ.get("L2S_DL_TTL", "30")) * 86400
_BAD = (".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".cgi", ".json", ".xml", ".txt", ".js", ".css", ".md",
        ".csv", ".yml", ".yaml", ".sh", ".py", ".properties", ".sig", ".asc", ".sha1", ".sha256", ".sha512", ".md5")

def _ok(url):
    p = urllib.parse.urlsplit(url)
    if p.scheme not in ("http", "https") or p.query or p.fragment:
        return None
    name = os.path.basename(p.path)
    if "." not in name or name.lower().endswith(_BAD) or p.path.endswith("/latest") or "/latest/" in p.path:
        return None
    return name

def _meta(url):
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=10)
        h = r.headers
        return "%s|%s|%s" % ((h.get("ETag") or "").strip(), (h.get("Content-Length") or "").strip(), (h.get("Last-Modified") or "").strip())
    except Exception:
        return ""

def _same(old, new):
    if not new: return 2
    if old == new: return 0
    oe, ne = old.split("|", 1)[0], new.split("|", 1)[0]
    if oe and ne: return 0 if oe == ne else 1
    return 0 if old.split("|", 1)[1:] == new.split("|", 1)[1:] else 1

def _verify(path, name, meta):
    if not os.path.getsize(path): return False
    with open(path, "rb") as fh:
        head = fh.read(64).lower()
    if (b"<!doctype html" in head or b"<html" in head) and not name.lower().endswith((".html", ".htm")): return False
    exp = meta.split("|")[1] if meta else ""
    if exp and str(os.path.getsize(path)) != exp: return False
    n = name.lower()
    try:
        if n.endswith((".zip", ".jar", ".aar", ".apk", ".aab", ".whl")):
            with zipfile.ZipFile(path) as z:
                if z.testzip() is not None: return False
        elif n.endswith((".tar.gz", ".tgz", ".gz")):
            with gzip.open(path) as g:
                while g.read(1 << 20): pass
        elif n.endswith((".tar.bz2", ".tbz2", ".bz2")):
            with bz2.open(path) as g:
                while g.read(1 << 20): pass
        elif n.endswith((".tar.xz", ".txz", ".xz")):
            with lzma.open(path) as g:
                while g.read(1 << 20): pass
        elif n.endswith(".tar"):
            with tarfile.open(path) as t: t.getmembers()
    except Exception:
        return False
    return True

_orig = urllib.request.urlretrieve

def _urlretrieve(url, filename=None, reporthook=None, data=None):
    try:
        name = _ok(url) if (isinstance(url, str) and data is None and filename) else None
        if name:
            os.makedirs(_CACHE, exist_ok=True)
            f = os.path.join(_CACHE, hashlib.sha256(url.encode()).hexdigest())
            rm = _meta(url)
            fresh = os.path.isfile(f) and (time.time() - os.path.getmtime(f)) < _TTL
            if fresh:
                try: old = open(f + ".meta").read()
                except Exception: old = ""
                st = _same(old, rm) if old else 1
                if st in (0, 2):
                    shutil.copyfile(f, filename)
                    print("L2S: من ذاكرة التنزيلات (%s): %s" % ("مطابق للخادم" if st == 0 else "تعذّر الاتصال للتحقق", name))
                    return filename, None
                print("L2S: تغيّر الملف على الخادم، سيُعاد تنزيله:", name)
            part = f + ".part"
            _orig(url, part, reporthook)
            if _verify(part, name, rm):
                os.replace(part, f)
                open(f + ".meta", "w").write(rm)
                open(f + ".url", "w").write(url + "\n")
                shutil.copyfile(f, filename)
                return filename, None
            try: os.remove(part)
            except Exception: pass
    except Exception:
        pass
    return _orig(url, filename, reporthook, data)

if os.environ.get("L2S_DL_CACHE") == "1":
    urllib.request.urlretrieve = _urlretrieve
