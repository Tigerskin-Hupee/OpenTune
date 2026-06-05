#!/usr/bin/env python3
"""
OpenTune sig-decode tester  (v1.2.59 logic — Strategy 4: flat-dispatcher).
Mirrors InnertubeApi.kt extractSigOps — shows exactly where it succeeds or fails,
then auto-fetches a real signatureCipher and verifies the decode end-to-end.

Run:  py analyze_yt_sig.py   (Windows)
      python3 analyze_yt_sig.py  (Mac/Linux)
"""
import re, sys, json, urllib.request, urllib.parse, urllib.error

# Same UA as ensurePlayerJsData in InnertubeApi.kt
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

def fetch(url, data=None, headers=None, timeout=25):
    h = {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9",
         "Accept": "*/*", "Accept-Encoding": "identity"}
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h)
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
        return resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"  fetch error: {e}"); return ""

# ─────────────────────────────────────────────────────────────────────────────
# extract_balanced — mirrors the FIXED extractBalancedJs (v1.2.58+)
# 7-state machine: understands JS strings, regex literals, comments.
# ─────────────────────────────────────────────────────────────────────────────
def extract_balanced(js, start):
    if start >= len(js): return None
    if js[start] not in ('{', '(', '['): return None
    depth = 0
    # 0=code 1=str" 2=str' 3=str` 4=regex 5=regex-cc 6=line-cmt 7=block-cmt
    state = 0
    regex_ctx = True
    i = start
    while i < len(js):
        c = js[i]
        if state == 6:
            if c == '\n': state = 0
        elif state == 7:
            if c == '*' and i+1 < len(js) and js[i+1] == '/':
                state = 0; i += 1
        elif state == 1:
            if c == '\\': i += 1
            elif c == '"': state = 0; regex_ctx = False
        elif state == 2:
            if c == '\\': i += 1
            elif c == "'": state = 0; regex_ctx = False
        elif state == 3:
            if c == '\\': i += 1
            elif c == '`': state = 0; regex_ctx = False
        elif state == 4:
            if c == '\\': i += 1
            elif c == '[': state = 5
            elif c == '/': state = 0; regex_ctx = False
        elif state == 5:
            if c == '\\': i += 1
            elif c == ']': state = 4
        else:
            if c == '/' and i+1 < len(js):
                if js[i+1] == '/':   state = 6; i += 1
                elif js[i+1] == '*': state = 7; i += 1
                elif regex_ctx:      state = 4
                else:                regex_ctx = True
            elif c == '"':  state = 1; regex_ctx = False
            elif c == "'":  state = 2; regex_ctx = False
            elif c == '`':  state = 3; regex_ctx = False
            elif c in ('{', '(', '['):
                depth += 1; regex_ctx = True
            elif c in (']', '}', ')'):
                depth -= 1
                if depth == 0: return js[start:i+1]
                regex_ctx = False
            elif c in (';', ','): regex_ctx = True
            elif c in ('=','+','-','*','!','<','>','~','^','&','|','?',':','%'):
                regex_ctx = True
            elif c.isalpha() or c in ('_', '$', '0123456789'): regex_ctx = False
        i += 1
    return None

# ── Helpers shared by Strategies 1, 2, 3 ─────────────────────────────────────

def build_op_map(helper_body):
    h1 = r'(?:function\s*\(\w+\)\s*\{|\(\w+\)\s*=>\s*\{?|\w+\s*=>\s*\{?)'
    h2 = r'(?:function\s*\(\w+,\w+\)\s*\{|\(\w+,\w+\)\s*=>\s*\{?)'
    ops = {}
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h1}[^}}]*\.reverse\(\)', helper_body):
        ops[m.group(1)] = ('reverse', 0)
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h2}[^}}]*\.splice\(0,', helper_body):
        ops[m.group(1)] = ('splice', 0)
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h2}[^}}]*=\w+\.slice\(', helper_body):
        if m.group(1) not in ops: ops[m.group(1)] = ('splice', 0)
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h2}[^}}]*%\w+\.length', helper_body):
        if m.group(1) not in ops: ops[m.group(1)] = ('swap', 0)
    return ops if ops else None

def find_helper_body(js, helper_name, before_idx):
    before = js[:before_idx]
    start = -1
    for prefix in [f"var {helper_name}={{", f"const {helper_name}={{",
                   f"let {helper_name}={{", f";{helper_name}={{", f",{helper_name}={{"]:
        idx = before.rfind(prefix)
        if idx >= 0: start = idx; break
    if start < 0:
        for prefix in [f"var {helper_name}={{", f";{helper_name}={{",
                       f",{helper_name}={{", f" {helper_name}={{"]:
            idx = js.find(prefix, before_idx)
            if idx >= 0: start = idx; break
    if start < 0: return None
    brace_idx = js.find("{", start)
    if brace_idx < 0: return None
    return extract_balanced(js, brace_idx)

def apply_ops(sig, ops):
    a = list(sig)
    for kind, n in ops:
        if kind == 'reverse':
            a.reverse()
        elif kind == 'splice':
            del a[:n]
        elif kind == 'swap':
            if a: idx = n % len(a); a[0], a[idx] = a[idx], a[0]
    return ''.join(a)

# ── Strategy 4: flat-dispatcher (YouTube 2026+) ───────────────────────────────
# Mirrors InnertubeApi.kt extractSigOpsDispatcher exactly.
# Dynamically extracts all XOR constants — does NOT hardcode player-specific values.
def extract_dispatcher_ops(js):
    """
    Returns (ops_list, status_string) or (None, error_string).
    ops_list is like [('splice',2),('reverse',0),('swap',17),...]
    """
    # Step 1: nested call site  FNAME(R1,K1, FNAME(R2,K2, SIG.s))
    cs = re.search(
        r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)', js)
    if not cs:
        return None, "d0-noCallSite"
    disp_name = cs.group(1)
    outer_r, outer_k = int(cs.group(2)), int(cs.group(3))
    p = outer_r ^ outer_k
    print(f"  [Strategy 4] call site: {disp_name}({outer_r},{outer_k},...) → p={p}")

    # Step 2: dispatcher function body
    fn_idx = js.rfind(f'{disp_name}=function(', 0, cs.start())
    if fn_idx < 0:
        fn_idx = js.find(f'{disp_name}=function(')
    if fn_idx < 0:
        fn_idx = js.rfind(f'var {disp_name}=function(', 0, cs.start())
    if fn_idx < 0:
        return None, f"d1-noFnDef({disp_name})"
    brace = js.find('{', fn_idx)
    disp_body = extract_balanced(js, brace)
    if not disp_body:
        return None, f"d1-noBody({disp_name})"

    # Step 3: XOR variable ("p" from "var p = K^R")
    xm = re.search(r'var\s+([\w$]+)\s*=\s*[\w$]+\s*\^\s*[\w$]+', disp_body)
    if not xm:
        return None, f"d2-noXorVar({disp_name})"
    xor_var = xm.group(1)

    # Step 4: string table var (from "tableVar[xorVar^48]")
    tm = re.search(r'([\w$]+)\[' + re.escape(xor_var) + r'\^48\]', disp_body)
    if not tm:
        return None, f"d3-noTableVar({disp_name}/{xor_var})"
    table_var = tm.group(1)

    # Step 4b: find the split-result array variable name (typically "b").
    # "var b = x[tableVar[xorVar^48]](tableVar[2])" → split_var = "b".
    split_res_m = re.search(
        r'var\s+([\w$]+)\s*=\s*\w+\[' + re.escape(table_var) + r'\[' + re.escape(xor_var) + r'\^48\]\]\(' + re.escape(table_var) + r'\[2\]\)',
        disp_body
    )
    split_var = split_res_m.group(1) if split_res_m else 'b'

    # Step 5: helper object name — require SPLIT_VAR as first arg to avoid matching the split
    # call itself: x[u[p^48]](u[2]) whose first arg is u[2], not the array variable.
    hm = re.search(
        r'([\w$]+)\[' + re.escape(table_var) + r'\[' + re.escape(xor_var) + r'\^\d+\]\]\s*\(' + re.escape(split_var),
        disp_body
    )
    if not hm:
        return None, f"d4-noHelperVar({disp_name}/{table_var}/{split_var})"
    helper_name = hm.group(1)
    print(f"  [Strategy 4] dispatcher={disp_name} xorVar={xor_var} tableVar={table_var} splitVar={split_var} helper={helper_name}")

    # Step 6: string table (tableVar = "...long...".split("{"))
    te = re.search(
        r'(?<![.\w])' + re.escape(table_var) + r'\s*=\s*"([^"]{300,})"\s*\.split\s*\(\s*"\{"\s*\)', js)
    if not te:
        return None, f"d5-noTableStr({table_var})"
    u = te.group(1).split('{')

    # Step 7: verify p
    if 'split' not in u:
        return None, "d6-noSplitInTable"
    split_idx = u.index('split')
    if (split_idx ^ 48) != p:
        return None, f"d6-pMismatch(table={split_idx^48} call={p})"
    if u[p ^ 5] != 'join':
        return None, f"d6-noJoinVerify(u[{p^5}]={u[p^5]!r})"
    if 'reverse' not in u or 'splice' not in u:
        return None, "d6-missingOpsInTable"
    reverse_idx = u.index('reverse')
    splice_idx  = u.index('splice')
    print(f"  [Strategy 4] table OK: p={p} split@{split_idx} join@{u.index('join')} "
          f"reverse@{reverse_idx} splice@{splice_idx}")

    # Step 8: Pw helper object body + opMap (uses string table indices)
    pw_body = None
    for prefix in [f'var {helper_name}={{', f';{helper_name}={{',
                   f',{helper_name}={{', f' {helper_name}={{']:
        idx = js.find(prefix)
        if idx >= 0:
            brace = js.find('{', idx)
            pw_body = extract_balanced(js, brace)
            if pw_body: break
    if not pw_body:
        return None, f"d7-noPwBody({helper_name})"
    pw_map = {}
    for m in re.finditer(r'([\w$]+)\s*:\s*function\s*\(([^)]*)\)\s*\{([^}]*)\}', pw_body):
        mn = m.group(1)
        n_params = len([x for x in m.group(2).split(',') if x.strip()])
        body = m.group(3)
        if n_params == 1 and f'u[{reverse_idx}]' in body:
            pw_map[mn] = ('reverse', 0)
        elif n_params == 2 and f'u[{splice_idx}]' in body:
            pw_map[mn] = ('splice', 0)
        elif n_params == 2 and '%' in body and mn not in pw_map:
            pw_map[mn] = ('swap', 0)
    if len(pw_map) < 2:
        return None, f"d7-pwMapSmall({helper_name} {pw_map})"
    print(f"  [Strategy 4] pw_map: {pw_map}")

    # Step 9: extract ordered ops from dispatcher body
    esc_h = re.escape(helper_name)
    esc_t = re.escape(table_var)
    esc_x = re.escape(xor_var)
    esc_s = re.escape(split_var)
    call_re = re.compile(
        r'%s\[%s\[%s\^(\d+)\]\]\(%s(?:,%s\^(\d+)|,(\d+))?\)' % (esc_h, esc_t, esc_x, esc_s, esc_x)
    )
    ops = []
    for m in call_re.finditer(disp_body):
        method_xor = int(m.group(1))
        method_idx = p ^ method_xor
        method_name = u[method_idx] if method_idx < len(u) else None
        if not method_name or method_name not in pw_map:
            continue
        kind, _ = pw_map[method_name]
        if m.group(2):
            arg = p ^ int(m.group(2))
        elif m.group(3):
            arg = int(m.group(3))
        else:
            arg = 0
        ops.append((kind, arg))
    if not ops:
        return None, f"d8-emptyOps({disp_name}/{helper_name})"

    return ops, f"OK p={p} {disp_name}/{helper_name} {len(ops)} ops"

# ── Strategies 1 + 3 (original patterns) ─────────────────────────────────────

FN_PATTERNS = [
    (r'([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{',    "NAME=function(a){"),
    (r'function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{',     "function NAME(a){"),
    (r'([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',       "NAME=(a)=>{"),
    (r'([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{',                 "NAME=a=>{"),
    (r'([\w$]+)\s*:\s*function\s*\(\s*([\w$]+)\s*\)\s*\{', "NAME:function(a){ [colon]"),
    (r'([\w$]+)\s*:\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',       "NAME:(a)=>{ [colon]"),
    (r'([\w$]+)\s*:\s*([\w$]+)\s*=>\s*\{',                 "NAME:a=>{ [colon]"),
]
SPLIT_TOKENS = ['.split("")', ".split('')", "Array.from(", "[..."]
JOIN_TOKENS  = ['.join("")',  ".join('')"]

def extract_sig_ops(js):
    sp = ("dq" if '.split("")' in js else "") + ("sq" if ".split('')" in js else "") + ("af" if "Array.from(" in js else "")
    jn = "dq" if '.join("")' in js else ""
    print(f"  JS: {len(js):,} bytes  sp={sp} jn={jn} rev={'.reverse()' in js} spl={'.splice(0,' in js}")

    # Strategy 1: join-anchor
    print("\n  [Strategy 1: join-anchor]")
    joins = [m.start() for m in re.finditer(r'\.join\(""\)', js)]
    print(f"  .join(\"\") count: {len(joins)}")
    for join_idx in joins:
        lb_off = max(0, join_idx - 10000)
        seg = js[lb_off: join_idx]
        if not any(t in seg for t in SPLIT_TOKENS): continue
        fd = None; fd_style = ""
        for pat, desc in FN_PATTERNS:
            mm = list(re.finditer(pat, seg))
            if mm: fd = mm[-1]; fd_style = desc; break
        if not fd: continue
        fn_name = fd.group(1); fn_param = fd.group(2)
        fd_abs = lb_off + fd.start()
        fn_brace = js.find("{", fd_abs)
        if fn_brace < 0: continue
        fn_body = extract_balanced(js, fn_brace)
        if not fn_body:
            print(f"    join@{join_idx}: fn={fn_name!r} ({fd_style}) extractBalanced FAILED")
            continue
        if not any(t in fn_body for t in SPLIT_TOKENS) or not any(t in fn_body for t in JOIN_TOKENS):
            print(f"    join@{join_idx}: fn={fn_name!r} ({fd_style}) body incomplete split={any(t in fn_body for t in SPLIT_TOKENS)} join={any(t in fn_body for t in JOIN_TOKENS)}")
            print(f"      body: {repr(fn_body[:200])}")
            continue
        helper_m = re.search(r'([\w$]+)\.([\w$]+)\(' + fn_param, fn_body)
        if not helper_m: helper_m = re.search(r'([\w$]+)\["[\w$]+"\]\(' + fn_param, fn_body)
        if not helper_m:
            print(f"    join@{join_idx}: fn={fn_name!r} — no helper call"); continue
        helper_name = helper_m.group(1)
        helper_body = find_helper_body(js, helper_name, fd_abs)
        if not helper_body:
            print(f"    join@{join_idx}: helper={helper_name!r} body not found"); continue
        op_map = build_op_map(helper_body)
        if not op_map:
            print(f"    join@{join_idx}: helper={helper_name!r} opMap empty"); continue
        ops = []
        for m in re.finditer(r'%s\.([\w$]+)\(%s(?:,(\d+))?\)' % (re.escape(helper_name), re.escape(fn_param)), fn_body):
            method = m.group(1); n = int(m.group(2)) if m.group(2) else 0
            if method not in op_map: ops = None; break
            kind, _ = op_map[method]; ops.append((kind, n))
        if ops:
            print(f"\n  *** Strategy 1 SUCCESS: fn={fn_name!r} helper={helper_name!r} ***")
            print(f"    ops: {ops}")
            return ops, fn_name
    print(f"  Strategy 1 failed.")

    # Strategy 3: call-site
    print("\n  [Strategy 3: call-site anchor]")
    cs_pats = [
        (r'\b[\w$]+&&\([\w$]+=([a-zA-Z0-9_$]+)\((?:\d+,)?decodeURIComponent', "&&(X=FN(decodeURI...)"),
        (r'[;,=]\s*([a-zA-Z0-9$]+)\(decodeURIComponent\([^)]+\.get\("s"\)',     "X=FN(get(s))"),
        (r'\.set\(["\']sig["\'],([a-zA-Z0-9$]+)\(',                             ".set('sig',FN(...))"),
        (r'[;({,\s=]([a-zA-Z0-9$]{2,})\s*\(\s*decodeURIComponent\(',           "FN(decodeURI [broad]"),
    ]
    for pat, desc in cs_pats:
        mm = re.search(pat, js)
        if mm:
            print(f"  call-site [{desc}]: {mm.group(1)!r}")
            break
    else:
        print(f"  No call-site pattern matched.")

    return None, None

# ── Dispatcher display (for when Strategies 1+3 fail) ─────────────────────────

def show_dispatcher_analysis(js):
    """Show dispatcher structure details (informational)."""
    print("\n" + "="*70)
    print("DISPATCHER PATTERN ANALYSIS")
    print("="*70)
    cs = re.search(
        r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)', js)
    if cs:
        print(f"Call site found: {js[max(0,cs.start()-30):cs.end()+30]!r}")
    for m in re.finditer(r'"([^"\n]{500,})"', js):
        content = m.group(1)
        if 'split' in content and 'join' in content and 'reverse' in content and '{' in content:
            u = content.split('{')
            if len(u) > 20:
                idx = {e: i for i, e in enumerate(u)}
                if 'split' in idx and 'join' in idx:
                    p = idx['split'] ^ 48
                    if (idx['join'] ^ 5) == p:
                        print(f"\nString table at @{m.start()}: {len(u)} entries, p={p}")
                        for key in ['split', 'join', 'reverse', 'splice', '']:
                            if key in idx: print(f"  u[{idx[key]}] = {repr(key)}")
                        break

# ── Auto-fetch signatureCipher from YouTube ───────────────────────────────────

# Well-known music video IDs (mix of international/K-pop/VEVO, available in most regions).
# WEB_REMIX client (YouTube Music) reliably returns signatureCipher for these.
YTMUSIC_VIDEOS = [
    "jNQXAC9IVRw",  # PSY - Gangnam Style (VEVO, universal)
    "gdZLi9oWNZg",  # BTS - Dynamite
    "ioNng23DkIM",  # BLACKPINK - How You Like That
    "fRh_vgS2dFE",  # Justin Bieber - Sorry
    "DyDfgMOUjCI",  # Billie Eilish - bad guy
    "2Vv-BfVoq4g",  # Ed Sheeran - Perfect
    "dQw4w9WgXcQ",  # Rick Astley - Never Gonna Give You Up
    "hT_nvWreIhg",  # OneRepublic - Counting Stars
]

def _extract_cipher_from_response(raw):
    """Parse a player API response and return (cipher, base_url) or (None, None)."""
    try:
        root = json.loads(raw)
        status = root.get("playabilityStatus", {}).get("status", "?")
        if status != "OK":
            reason = root.get("playabilityStatus", {}).get("reason", "")
            return None, None, f"status={status} {reason}"
        for fmt in root.get("streamingData", {}).get("adaptiveFormats", []):
            if "audio/" not in fmt.get("mimeType", ""):
                continue
            cipher = fmt.get("signatureCipher") or fmt.get("cipher")
            if cipher:
                params = {}
                for part in cipher.split("&"):
                    if "=" in part:
                        k, v = part.split("=", 1)
                        params[k] = urllib.parse.unquote(v)
                return cipher, params.get("url", ""), "ok"
        # No cipher — direct URL (video doesn't need sig decode)
        return None, None, "no-cipher(directURL)"
    except Exception as e:
        return None, None, f"parse-error:{e}"

def fetch_cipher_from_youtube(video_id, sig_ts=0):
    """
    Try WEB_EMBEDDED_PLAYER then WEB_REMIX to get signatureCipher.
    Returns (cipher_string, base_url) or (None, None).
    """
    sig_ts_frag = (f',"playbackContext":{{"contentPlaybackContext":{{"signatureTimestamp":{sig_ts}}}}}'
                   if sig_ts > 0 else "")

    clients = [
        # WEB_EMBEDDED_PLAYER — same as OpenTune's primary client
        {
            "name": "WEB_EMBEDDED_PLAYER", "id": "56", "version": "1.20250101.09.00",
            "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "url": "https://www.youtube.com/youtubei/v1/player",
            "extra_context": f'"thirdParty":{{"embedUrl":"https://www.youtube.com/watch?v={video_id}"}}',
        },
        # WEB_REMIX — YouTube Music client, very likely to return signatureCipher
        {
            "name": "WEB_REMIX", "id": "67", "version": "1.20260213.01.00",
            "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            "url": "https://music.youtube.com/youtubei/v1/player",
            "extra_context": "",
        },
    ]
    for cl in clients:
        extra_ctx = f',{cl["extra_context"]}' if cl["extra_context"] else ""
        body = (
            f'{{"videoId":"{video_id}","contentCheckOk":true,"racyCheckOk":true,'
            f'"context":{{"client":{{"clientName":"{cl["name"]}","clientVersion":"{cl["version"]}",'
            f'"hl":"en","gl":"US","timeZone":"UTC","utcOffsetMinutes":0}}{extra_ctx}}}{sig_ts_frag}}}'
        ).encode("utf-8")
        headers = {
            "User-Agent": cl["ua"],
            "Content-Type": "application/json",
            "X-YouTube-Client-Name": cl["id"],
            "X-YouTube-Client-Version": cl["version"],
            "Origin": cl["url"].rsplit("/youtubei", 1)[0],
            "Cookie": "SOCS=CAI=",
        }
        raw = fetch(cl["url"], data=body, headers=headers)
        if not raw:
            continue
        cipher, base_url, msg = _extract_cipher_from_response(raw)
        if cipher:
            print(f"    [{cl['name']}] got signatureCipher ✓")
            return cipher, base_url
        print(f"    [{cl['name']}] {msg}")
    return None, None

def decode_cipher_url(cipher, ops):
    """Apply ops to the raw sig in cipher and return the decoded URL."""
    params = {}
    for part in cipher.split("&"):
        if "=" in part:
            k, v = part.split("=", 1)
            params[k] = urllib.parse.unquote(v)
    base_url = params.get("url", "")
    raw_sig  = params.get("s", "")
    sig_param = params.get("sp", "sig")
    if not base_url or not raw_sig:
        return None
    decoded_sig = apply_ops(raw_sig, ops)
    sep = "&" if "?" in base_url else "?"
    return f"{base_url}{sep}{sig_param}={urllib.parse.quote(decoded_sig, safe='')}"

def test_url_accessible(url):
    """HEAD request to check if URL returns 200 or 206 (not 403)."""
    req = urllib.request.Request(url, method="HEAD", headers={
        "User-Agent": "com.google.android.apps.youtube.music/7.27.52",
        "Range": "bytes=0-1",
    })
    try:
        resp = urllib.request.urlopen(req, timeout=10)
        return resp.status, None
    except urllib.error.HTTPError as e:
        return e.code, str(e)
    except Exception as e:
        return None, str(e)

# ── Main ──────────────────────────────────────────────────────────────────────

def find_player_url(html):
    m = re.search(r'/s/player/[0-9a-fA-F]+/[^\s"\']*base\.js', html)
    if m: return "https://www.youtube.com" + m.group(0)
    return None

print("="*70)
print("OpenTune sig-decode tester  (v1.2.59 — Strategy 4: flat-dispatcher)")
print("="*70)
print(f"Using UA: {UA}\n")

SOURCE_URLS = [
    "https://www.youtube.com/watch?v=jNQXAC9IVRw",
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "https://www.youtube.com",
]

player_url = None
for page in SOURCE_URLS:
    print(f"Trying {page} …")
    html = fetch(page)
    player_url = find_player_url(html) if html else None
    if player_url: print(f"Found: {player_url}"); break

if not player_url:
    print("\nAuto-detection failed.")
    player_url = input("Paste player JS URL: ").strip()
if not player_url:
    sys.exit(1)

# Prefer player_ias (same as InnertubeApi.kt)
ias_url = player_url.replace("player_es6.vflset", "player_ias.vflset")
js = None
for try_url, label in [(ias_url, "player_ias (preferred)"), (player_url, "fallback")]:
    print(f"\nFetching {label} …  {try_url}")
    candidate = fetch(try_url)
    if len(candidate) > 100_000:
        js = candidate
        print(f"OK ({len(js):,} bytes)")
        break
    else:
        print(f"  got only {len(candidate)} bytes")

if not js:
    print("ERROR: could not fetch player JS"); sys.exit(1)
print()

# Extract signatureTimestamp from player JS (needed for WEB client API calls)
sig_ts_m = re.search(r'signatureTimestamp[=:]\s*(\d+)', js)
sig_ts = int(sig_ts_m.group(1)) if sig_ts_m else 0
if sig_ts: print(f"signatureTimestamp: {sig_ts}")

print("Running extraction …")
ops, fn_name = extract_sig_ops(js)

if not ops:
    # Strategy 4: flat-dispatcher
    print("\n  [Strategy 4: flat-dispatcher (YouTube 2026+)]")
    ops, hint = extract_dispatcher_ops(js)
    if ops:
        print(f"\n*** Strategy 4 SUCCESS: {hint} ***")
        print(f"    ops ({len(ops)}): {ops}")
    else:
        print(f"\n  Strategy 4 failed: {hint}")
        show_dispatcher_analysis(js)

# ── End-to-end cipher test ────────────────────────────────────────────────────

print("\n" + "="*70)
print("END-TO-END TEST: auto-fetch signatureCipher and verify decode")
print("="*70)

if not ops:
    print("Extraction failed — skipping cipher test.")
else:
    cipher = None
    for vid in YTMUSIC_VIDEOS:
        print(f"Fetching cipher for video {vid} …")
        cipher, base_url = fetch_cipher_from_youtube(vid, sig_ts)
        if cipher:
            print(f"  Got signatureCipher ({len(cipher)} chars)")
            break

    if cipher:
        decoded_url = decode_cipher_url(cipher, ops)
        if decoded_url:
            print(f"\nDecoded URL (first 120 chars): {decoded_url[:120]}…")
            print("Testing accessibility (HEAD request) …")
            status, err = test_url_accessible(decoded_url)
            if status in (200, 206):
                print(f"  HTTP {status} — SUCCESS! Sig decode is working correctly.")
            elif status == 403:
                print(f"  HTTP 403 — sig decode may be WRONG (wrong ops or n-param not decoded).")
                print("  Note: n-param decode needs the player JS decodeN function (not tested here).")
            else:
                print(f"  HTTP {status} err={err}")
        else:
            print("  Could not decode cipher URL (parse error)")
    else:
        print("Could not fetch a real signatureCipher from any test video.")
        print("Manual test: paste a signatureCipher from OpenTune logcat below.")

# Manual paste fallback
print("\n" + "="*70)
print("Manual test (optional)")
cipher_input = input("Paste signatureCipher or raw 's=' value (Enter to skip): ").strip()
if cipher_input and ops:
    raw_sig = None
    if "s=" in cipher_input and "&" in cipher_input:
        parts = {}
        for p in cipher_input.split("&"):
            if "=" in p: k, v = p.split("=", 1); parts[k] = v
        raw_sig = urllib.parse.unquote(parts.get("s", ""))
    else:
        raw_sig = urllib.parse.unquote(cipher_input)
    if raw_sig:
        decoded = apply_ops(raw_sig, ops)
        print(f"Raw sig    ({len(raw_sig)} chars): {raw_sig[:80]}…")
        print(f"Decoded    ({len(decoded)} chars): {decoded[:80]}…")
        print(f"Ops used: {ops}")
    else:
        print("Could not parse input.")
elif cipher_input:
    print("Extraction failed — cannot decode.")

print("\nDone.")
