#!/usr/bin/env python3
"""
OpenTune sig-decode tester.
Fetches the YouTube player JS and runs the same 3-strategy extraction
logic as InnertubeApi.kt — shows exactly where it succeeds or fails.

Run:  py analyze_yt_sig.py   (Windows)
      python3 analyze_yt_sig.py  (Mac/Linux)
"""
import re, sys, urllib.request, urllib.error

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")

def fetch(url, ua=UA):
    req = urllib.request.Request(url, headers={
        "User-Agent": ua,
        "Accept-Language": "en-US,en;q=0.9",
        "Accept": "*/*",
        "Accept-Encoding": "identity",
    })
    try:
        return urllib.request.urlopen(req, timeout=25).read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return ""
    except Exception as e:
        return ""

# ─────────────────────────────────────────────────────────────────────────────
# extract_balanced: same logic as extractBalancedJs in InnertubeApi.kt
# ─────────────────────────────────────────────────────────────────────────────
def extract_balanced(js, start):
    openers = {'{': '}', '(': ')', '[': ']'}
    close_ch = openers.get(js[start] if start < len(js) else '')
    if close_ch is None:
        return None
    depth = 0; in_str = False; str_ch = ''; i = start
    while i < len(js):
        c = js[i]
        if in_str:
            if c == str_ch and js[i-1] != '\\':
                in_str = False
        else:
            if c in ('"', "'", '`'):
                in_str = True; str_ch = c
            elif c in openers:
                depth += 1
            elif c == close_ch:
                depth -= 1
                if depth == 0:
                    return js[start:i+1]
        i += 1
    return None

# ─────────────────────────────────────────────────────────────────────────────
# build_op_map: mirrors buildOpMap in InnertubeApi.kt
# ─────────────────────────────────────────────────────────────────────────────
def build_op_map(helper_body):
    h1 = r'(?:function\s*\(\w+\)\s*\{|\(\w+\)\s*=>\s*\{?|\w+\s*=>\s*\{?)'
    h2 = r'(?:function\s*\(\w+,\w+\)\s*\{|\(\w+,\w+\)\s*=>\s*\{?)'
    ops = {}
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h1}[^}}]*\.reverse\(\)', helper_body):
        ops[m.group(1)] = ('reverse', 0)
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h2}[^}}]*\.splice\(0,', helper_body):
        ops[m.group(1)] = ('splice', 0)
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h2}[^}}]*=\w+\.slice\(', helper_body):
        if m.group(1) not in ops:
            ops[m.group(1)] = ('splice', 0)
    for m in re.finditer(fr'([\w$]+)\s*:\s*{h2}[^}}]*%\w+\.length', helper_body):
        if m.group(1) not in ops:
            ops[m.group(1)] = ('swap', 0)
    return ops if ops else None

# ─────────────────────────────────────────────────────────────────────────────
# find_helper_body: mirrors findHelperBody in InnertubeApi.kt
# ─────────────────────────────────────────────────────────────────────────────
def find_helper_body(js, helper_name, before_idx):
    before = js[:before_idx]
    start = -1
    for prefix in [f"var {helper_name}={{", f"const {helper_name}={{",
                   f"let {helper_name}={{", f";{helper_name}={{", f",{helper_name}={{"]:
        idx = before.rfind(prefix)
        if idx >= 0:
            start = idx; break
    if start < 0:
        for prefix in [f"var {helper_name}={{", f"const {helper_name}={{",
                       f"let {helper_name}={{", f";{helper_name}={{", f",{helper_name}={{"]:
            idx = js.find(prefix, before_idx)
            if idx >= 0:
                start = idx; break
    if start < 0:
        return None
    brace_idx = js.find("{", start)
    if brace_idx < 0:
        return None
    body = extract_balanced(js, brace_idx)
    return body

# ─────────────────────────────────────────────────────────────────────────────
# parse_op_calls: mirrors parseOpCalls in InnertubeApi.kt
# ─────────────────────────────────────────────────────────────────────────────
def parse_op_calls(fn_body, fn_param, helper_name, op_map):
    ops = []
    pat = re.compile(r'%s\.([\w$]+)\(%s(?:,(\d+))?\)' % (re.escape(helper_name), re.escape(fn_param)))
    for m in pat.finditer(fn_body):
        method = m.group(1)
        n = int(m.group(2)) if m.group(2) else 0
        if method not in op_map:
            return None
        kind, _ = op_map[method]
        ops.append((kind, n))
    return ops if ops else None

# ─────────────────────────────────────────────────────────────────────────────
# apply_ops: apply decoded ops to the raw signature
# ─────────────────────────────────────────────────────────────────────────────
def apply_ops(sig, ops):
    a = list(sig)
    for kind, n in ops:
        if kind == 'reverse':
            a.reverse()
        elif kind == 'splice':
            del a[:n]
        elif kind == 'swap':
            if a:
                idx = n % len(a)
                a[0], a[idx] = a[idx], a[0]
    return ''.join(a)

# ─────────────────────────────────────────────────────────────────────────────
# extract_sig_ops: mirrors extractSigOps in InnertubeApi.kt (all 3 strategies)
# ─────────────────────────────────────────────────────────────────────────────
def extract_sig_ops(js):
    split_tokens = ['.split("")', ".split('')", "Array.from(", "[..."]
    join_tokens  = ['.join("")',  ".join('')"]

    sp = ""
    if '.split("")' in js:  sp += "dq"
    if ".split('')" in js:  sp += "sq"
    if "Array.from(" in js: sp += "af"
    jn = "dq" if '.join("")' in js else ""
    hint = f"len={len(js)} sp={sp} jn={jn} rev={'.reverse()' in js} spl={'.splice(0,' in js}"
    print(f"  JS fingerprint: {hint}")

    FN_PATTERNS = [
        (r'([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{',    "NAME=function(a){"),
        (r'function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{',     "function NAME(a){"),
        (r'([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',       "NAME=(a)=>{"),
        (r'([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{',                 "NAME=a=>{"),
        (r'([\w$]+)\s*:\s*function\s*\(\s*([\w$]+)\s*\)\s*\{', "NAME:function(a){ [colon]"),
        (r'([\w$]+)\s*:\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',       "NAME:(a)=>{ [colon]"),
        (r'([\w$]+)\s*:\s*([\w$]+)\s*=>\s*\{',                 "NAME:a=>{ [colon]"),
    ]

    # ── Strategy 1: join-anchor ───────────────────────────────────────────────
    print("\n  [Strategy 1: join-anchor]")
    sig_ops_hint = "s0-noJoin"
    j_from = 0
    join_positions = [m.start() for m in re.finditer(r'\.join\(""\)', js)]
    print(f"  .join(\"\") count: {len(join_positions)}")

    for join_idx in join_positions:
        lb_off = max(0, join_idx - 10000)
        seg = js[lb_off: join_idx]
        has_split = any(t in seg for t in split_tokens)
        if not has_split:
            continue

        fd = None; fd_style = ""
        for pat, desc in FN_PATTERNS:
            matches = list(re.finditer(pat, seg))
            if matches:
                fd = matches[-1]; fd_style = desc; break
        if fd is None:
            print(f"    join@{join_idx}: has split, but NO fn-def pattern matched")
            print(f"      seg tail 300: {repr(seg[-300:])}")
            continue

        fn_name = fd.group(1); fn_param = fd.group(2)
        fd_abs = lb_off + fd.start()
        fn_brace = js.find("{", fd_abs)
        if fn_brace < 0: continue
        fn_body = extract_balanced(js, fn_brace)
        if fn_body is None: continue

        fn_has_split = any(t in fn_body for t in split_tokens)
        fn_has_join  = any(t in fn_body for t in join_tokens)
        if not fn_has_split or not fn_has_join:
            print(f"    join@{join_idx}: fn={fn_name!r} ({fd_style})")
            print(f"      body missing: split={fn_has_split} join={fn_has_join}")
            print(f"      body: {repr(fn_body[:200])}")
            continue

        sig_ops_hint = f"s1-noHelper/{fn_name}"
        helper_m = re.search(r'([\w$]+)\.([\w$]+)\(' + fn_param, fn_body)
        if not helper_m:
            helper_m = re.search(r'([\w$]+)\["[\w$]+"\]\(' + fn_param, fn_body)
        if not helper_m:
            print(f"    join@{join_idx}: fn={fn_name!r} — no helper call in body")
            print(f"      fn_body: {repr(fn_body[:300])}")
            continue

        helper_name = helper_m.group(1)
        sig_ops_hint = f"s2-noHelperDef/{helper_name}"
        helper_body = find_helper_body(js, helper_name, fd_abs)
        if not helper_body:
            print(f"    join@{join_idx}: fn={fn_name!r} helper={helper_name!r} — helper body NOT FOUND")
            continue

        op_map = build_op_map(helper_body)
        if not op_map:
            print(f"    join@{join_idx}: fn={fn_name!r} helper={helper_name!r} — opMap empty")
            print(f"      helper_body: {repr(helper_body[:300])}")
            continue

        ops = parse_op_calls(fn_body, fn_param, helper_name, op_map)
        if not ops:
            print(f"    join@{join_idx}: fn={fn_name!r} helper={helper_name!r} — op calls not parsed")
            print(f"      fn_body: {repr(fn_body[:300])}")
            print(f"      opMap: {op_map}")
            continue

        print(f"    SUCCESS via join-anchor!")
        print(f"      fn={fn_name!r} ({fd_style}), param={fn_param!r}, helper={helper_name!r}")
        print(f"      ops: {ops}")
        return ops, fn_name

    print(f"  Strategy 1 hint: {sig_ops_hint}")

    # ── Strategy 2: splice-anchor ─────────────────────────────────────────────
    print("\n  [Strategy 2: splice-anchor]")
    splice_positions = [m.start() for m in re.finditer(r'\.splice\(0,', js)]
    print(f"  .splice(0, count: {len(splice_positions)}")
    for splice_idx in splice_positions[:10]:
        lb_off = max(0, splice_idx - 2000)
        lb = js[lb_off: splice_idx]
        hm = re.findall(r'(?:var\s+|[;,}\s(])([\w$]+)\s*=\s*\{', lb)
        if hm:
            helper_name = hm[-1]
            print(f"    splice@{splice_idx}: potential helper={helper_name!r}")
    print(f"  (skipping full Strategy 2 detail for brevity)")

    # ── Strategy 3: call-site anchor ─────────────────────────────────────────
    print("\n  [Strategy 3: call-site anchor]")
    cs_patterns = [
        (r'\b[\w$]+&&\([\w$]+=([a-zA-Z0-9_$]+)\((?:\d+,)?decodeURIComponent', "&&(X=FN(decodeURI...)"),
        (r'\bc&&\(c=([a-zA-Z0-9$]+)\(decodeURIComponent',                       "c&&(c=FN(...))"),
        (r'[;,=]\s*([a-zA-Z0-9$]+)\(decodeURIComponent\([^)]+\.get\("s"\)',     "X=FN(get(s))"),
        (r'\.set\(["\']sig["\'],([a-zA-Z0-9$]+)\(',                             ".set('sig',FN(...))"),
        (r'b=([a-zA-Z0-9$]+)\(decodeURIComponent\(b\.get\("s"\)\)\)',           "b=FN(get(s))"),
        (r'[;({,\s=]([a-zA-Z0-9$]{2,})\s*\(\s*decodeURIComponent\(',           "FN(decodeURI(...) [broad]"),
        (r'([\w$]+\.[\w$]+)\s*\(\s*decodeURIComponent\(',                       "OBJ.FN(decodeURI(...) [obj-method]"),
    ]
    dc_count = len(re.findall(r'decodeURIComponent', js))
    print(f"  decodeURIComponent count: {dc_count}")
    found_cs = False
    for pat, desc in cs_patterns:
        mm = re.search(pat, js)
        if mm:
            ctx = js[max(0, mm.start()-60): mm.end()+120]
            print(f"  call-site FOUND [{desc}]")
            print(f"    captured: {mm.group(1)!r}")
            print(f"    context: {repr(ctx)}")
            found_cs = True
            break
    if not found_cs:
        print("  No call-site pattern matched!")
        # Show all decodeURIComponent contexts
        for m in re.finditer(r'decodeURIComponent', js):
            ctx = js[max(0, m.start()-80): m.end()+60]
            print(f"    decodeURIComponent@{m.start()}: {repr(ctx)}")

    return None, None


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────
def find_player_url_from_html(html):
    for pat in [
        r'"PLAYER_JS_URL"\s*:\s*"(/s/player/[^"]+base\.js)"',
        r'(/s/player/[a-f0-9]+/player_ias\.vflset/[^/"\']+/base\.js)',
        r'(/s/player/[a-f0-9]+/player_es6\.vflset/[^/"\']+/base\.js)',
        r'src="(/s/player/[^"]+base\.js)"',
    ]:
        m = re.search(pat, html)
        if m:
            return "https://www.youtube.com" + m.group(1)
    return None

print("="*70)
print("OpenTune sig-decode tester")
print("="*70)

player_url = None
for page in [
    "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
    "https://www.youtube.com/",
]:
    print(f"\nTrying {page} …")
    html = fetch(page)
    player_url = find_player_url_from_html(html) if html else None
    if player_url:
        print(f"Found: {player_url}")
        break

if not player_url:
    print("\nAuto-detection failed.")
    print("Get the URL from Chrome DevTools → Network → filter 'base.js'.")
    player_url = input("Paste player JS URL (or press Enter to skip): ").strip()

if player_url:
    print(f"\nFetching player JS …")
    js = fetch(player_url)
    if len(js) < 50000:
        print(f"ERROR: fetch returned only {len(js)} bytes.")
        js = None
    else:
        print(f"OK ({len(js):,} bytes)\n")
        print("Running extraction …")
        ops, fn_name = extract_sig_ops(js)
else:
    js = None
    ops = None

# ── Optional: test with a real signatureCipher ────────────────────────────────
print("\n" + "="*70)
print("Optional: test with a real signatureCipher")
print("="*70)
print("You can get one from the OpenTune app log (look for 'signatureCipher' or 's=...')")
print("Or press Enter to skip.\n")
cipher_input = input("Paste signatureCipher (or raw s= value): ").strip()

if cipher_input and ops:
    # Parse the cipher string
    raw_sig = None
    if "s=" in cipher_input and "url=" in cipher_input:
        parts = dict(p.split("=", 1) for p in cipher_input.split("&") if "=" in p)
        raw_sig = urllib.parse.unquote(parts.get("s", "")) if parts.get("s") else None
        import urllib.parse
    elif cipher_input:
        raw_sig = cipher_input

    if raw_sig:
        import urllib.parse
        decoded = apply_ops(urllib.parse.unquote(raw_sig), ops)
        print(f"\nRaw sig: {raw_sig[:60]}…")
        print(f"Decoded: {decoded[:60]}…")
        print(f"Ops applied: {ops}")
    else:
        print("Could not parse signatureCipher.")
elif cipher_input and not ops:
    print("Extraction failed so cannot decode the cipher.")

print("\nDone.")
