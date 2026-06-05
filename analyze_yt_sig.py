#!/usr/bin/env python3
"""
OpenTune sig-decode tester.
Mirrors InnertubeApi.kt extractSigOps — shows exactly where it succeeds or fails.

Run:  py analyze_yt_sig.py   (Windows)
      python3 analyze_yt_sig.py  (Mac/Linux)
"""
import re, sys, urllib.request, urllib.error

# Same UA as ensurePlayerJsData in InnertubeApi.kt
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

def fetch(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA, "Accept-Language": "en-US,en;q=0.9",
        "Accept": "*/*", "Accept-Encoding": "identity",
    })
    try:
        return urllib.request.urlopen(req, timeout=25).read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"  fetch error: {e}"); return ""

# ─────────────────────────────────────────────────────────────────────────────
# extract_balanced — mirrors the FIXED extractBalancedJs (v1.2.58)
# Handles JS strings, regex literals (/…/), comments (// and /* */)
# so that { } inside them are NOT counted as brace depth.
# ─────────────────────────────────────────────────────────────────────────────
def extract_balanced(js, start):
    if start >= len(js): return None
    openers = {'{': '}', '(': ')', '[': ']'}
    if js[start] not in openers: return None
    depth = 0
    # state: 0=code 1=str" 2=str' 3=str` 4=regex 5=regex-cc 6=line-cmt 7=block-cmt
    state = 0
    regex_ctx = True   # True → next '/' starts a regex
    i = start
    while i < len(js):
        c = js[i]
        if state == 6:      # line comment
            if c == '\n': state = 0
        elif state == 7:    # block comment
            if c == '*' and i+1 < len(js) and js[i+1] == '/':
                state = 0; i += 1
        elif state == 1:    # double-quote string
            if c == '\\': i += 1
            elif c == '"': state = 0; regex_ctx = False
        elif state == 2:    # single-quote string
            if c == '\\': i += 1
            elif c == "'": state = 0; regex_ctx = False
        elif state == 3:    # template literal
            if c == '\\': i += 1
            elif c == '`': state = 0; regex_ctx = False
        elif state == 4:    # regex body
            if c == '\\': i += 1
            elif c == '[': state = 5
            elif c == '/': state = 0; regex_ctx = False
        elif state == 5:    # regex character class
            if c == '\\': i += 1
            elif c == ']': state = 4
        else:               # normal code
            if c == '/' and i+1 < len(js):
                if js[i+1] == '/':   state = 6; i += 1
                elif js[i+1] == '*': state = 7; i += 1
                elif regex_ctx:      state = 4
                else:                regex_ctx = True
            elif c == '"':  state = 1; regex_ctx = False
            elif c == "'":  state = 2; regex_ctx = False
            elif c == '`':  state = 3; regex_ctx = False
            elif c in openers:
                depth += 1; regex_ctx = True
            elif c in (']', '}', ')'):
                depth -= 1
                if depth == 0: return js[start:i+1]
                regex_ctx = False
            elif c in (';', ','):  regex_ctx = True
            elif c in ('=','+','-','*','!','<','>','~','^','&','|','?',':','%'):
                regex_ctx = True
            elif c.isalpha() or c in ('_', '$'): regex_ctx = False
            elif c.isdigit(): regex_ctx = False
        i += 1
    return None

# ── build_op_map / find_helper_body / parse_op_calls (same as InnertubeApi) ──

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
        for prefix in [f"var {helper_name}={{", f";{helper_name}={{", f",{helper_name}={{"]:
            idx = js.find(prefix, before_idx)
            if idx >= 0: start = idx; break
    if start < 0: return None
    brace_idx = js.find("{", start)
    if brace_idx < 0: return None
    return extract_balanced(js, brace_idx)

def parse_op_calls(fn_body, fn_param, helper_name, op_map):
    ops = []
    pat = re.compile(r'%s\.([\w$]+)\(%s(?:,(\d+))?\)' % (re.escape(helper_name), re.escape(fn_param)))
    for m in pat.finditer(fn_body):
        method = m.group(1)
        n = int(m.group(2)) if m.group(2) else 0
        if method not in op_map: return None
        kind, _ = op_map[method]
        ops.append((kind, n))
    return ops if ops else None

def apply_ops(sig, ops):
    a = list(sig)
    for kind, n in ops:
        if kind == 'reverse': a.reverse()
        elif kind == 'splice': del a[:n]
        elif kind == 'swap':
            if a: idx = n % len(a); a[0], a[idx] = a[idx], a[0]
    return ''.join(a)

# ── Strategy 1: join-anchor ───────────────────────────────────────────────────
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

def decode_dispatcher(js):
    """Decode the new flat-dispatcher sig pattern (YouTube 2026+).

    Structure:
      Qp=function(R,K,x,...){ var p=K^R; ... var b=x[u[p^48]](u[2]); Pw[u[p^117]](b,2); ... t=b[u[p^5]](u[2]) }
    where u is a {-delimited string table and Pw is the helper object.
    """
    print("\n" + "="*70)
    print("DISPATCHER PATTERN DECODER")
    print("="*70)

    # 1. Find the Qp dispatcher function (contains the sig decode block)
    qp_match = re.search(r'(\w+)=function\(\w+,\w+,\w+[^)]*\)\{[^{]*var p=\w+\^\w+[^}]{0,3000}decodeURIComponent', js)
    if not qp_match:
        # Try broader: any function that has both p=K^R and decodeURIComponent and Pw[
        qp_match = re.search(r'(\w+)=function\([^)]+\)\{[^{]*var p=[^;]+\^[^;]+;.*?decodeURIComponent.*?\bPw\b', js, re.DOTALL)
    if qp_match:
        fn_name = qp_match.group(1)
        fn_brace = js.find("{", qp_match.start())
        fn_body = extract_balanced(js, fn_brace)
        print(f"\n[1] Dispatcher function: {fn_name!r}")
        if fn_body:
            print(f"    Body ({len(fn_body)} chars):")
            for chunk in [fn_body[i:i+120] for i in range(0, min(len(fn_body), 800), 120)]:
                print(f"      {repr(chunk)}")
    else:
        fn_name = None
        print("\n[1] Could not find dispatcher function automatically")
        # Show the sig decode block we found at @37497 manually
        print("    Known block @37497 (from previous run):")
        ctx = js[max(0,37000):38500]
        for chunk in [ctx[i:i+120] for i in range(0, len(ctx), 120)]:
            print(f"      {repr(chunk)}")

    # 2. Find the Pw helper object (traditional reverse/splice/swap methods)
    print("\n[2] Finding Pw helper object:")
    pw_patterns = [
        r'\bPw\s*=\s*\{',
        r'var\s+Pw\s*=\s*\{',
    ]
    for pat in pw_patterns:
        m = re.search(pat, js)
        if m:
            brace = js.find("{", m.start())
            body = extract_balanced(js, brace)
            if body:
                print(f"  Found Pw at @{m.start()}: {repr(body[:400])}")
                h1 = r'(?:function\s*\(\w+\)\s*\{|\(\w+\)\s*=>\s*\{?|\w+\s*=>\s*\{?)'
                h2 = r'(?:function\s*\(\w+,\w+\)\s*\{|\(\w+,\w+\)\s*=>\s*\{?)'
                op_map = build_op_map(body)
                print(f"  Pw opMap: {op_map}")
                break
    else:
        print("  Pw not found via standard patterns")

    # 3. Find the string table u: a string literal containing "split","join","reverse","splice"
    #    delimited by some separator char
    print("\n[3] Finding string table u (should contain split,join,reverse,splice):")
    found_table = False
    for sep in ['{', '|', ';', ',']:
        # Search for a string literal containing all key words
        for m in re.finditer(r'"([^"\n]{100,})"', js):
            content = m.group(1)
            if ('split' in content and 'join' in content and
                    'reverse' in content and sep in content):
                entries = content.split(sep)
                if len(entries) > 20:
                    print(f"  Candidate table at @{m.start()}, sep={repr(sep)}, {len(entries)} entries")
                    # Find key method indices
                    idx = {e: i for i, e in enumerate(entries)}
                    for key in ['', 'split', 'join', 'reverse', 'splice']:
                        if key in idx:
                            print(f"    u[{idx[key]}] = {repr(key)}")
                    # Try to find p: if split at index s → p = s^48
                    if 'split' in idx and 'join' in idx:
                        p_from_split = idx['split'] ^ 48
                        p_from_join  = idx['join'] ^ 5
                        print(f"    p (from split index {idx['split']}^48) = {p_from_split}")
                        print(f"    p (from join  index {idx['join']}^5)  = {p_from_join}")
                        if p_from_split == p_from_join:
                            p = p_from_split
                            print(f"  *** p CONFIRMED = {p} ***")
                            # Decode operation method names
                            print(f"  Decoded Pw methods:")
                            for xor_idx, label in [(117,'op_117'),(123,'op_123'),(44,'op_44')]:
                                table_idx = p ^ xor_idx
                                method = entries[table_idx] if table_idx < len(entries) else "?"
                                print(f"    Pw[u[p^{xor_idx}]] = Pw[u[{table_idx}]] = Pw['{method}']")
                            # Decode argument values
                            print(f"  Decoded arguments (p={p}):")
                            for arg_xor, label in [(14,'arg1'),(45,'arg2'),(27,'arg3'),(127,'arg4'),(50,'arg5'),(18,'arg6')]:
                                print(f"    p^{arg_xor} = {p^arg_xor}")
                            # Full decoded op sequence
                            print(f"\n  *** FULL OP SEQUENCE ***")
                            ops_lookup = [(117,2),(123,p^14),(117,2),(44,p^45),(123,p^27),(44,p^127),(44,p^50),(123,p^18)]
                            for i, (xor_idx, arg) in enumerate(ops_lookup):
                                table_idx = p ^ xor_idx
                                method = entries[table_idx] if table_idx < len(entries) else "?"
                                print(f"    op{i+1}: Pw['{method}'](b, {arg})")
                            found_table = True
                        else:
                            print(f"    p mismatch: split gives {p_from_split}, join gives {p_from_join}")
                    if found_table:
                        break
        if found_table:
            break
    if not found_table:
        # Show the known fragment and try partial decode
        print("  Full table not found. Showing the fragment from @3482:")
        ctx = js[max(0,3400):4200]
        print(f"  {repr(ctx)}")

    # 4. Find calls to the dispatcher near signatureCipher context
    print("\n[4] Calls to Qp (or the dispatcher) for sig decode:")
    # Search for the dispatcher function name called near decodeURIComponent
    if fn_name:
        for m in re.finditer(re.escape(fn_name) + r'\s*\(', js):
            ctx = js[max(0, m.start()-100): m.end()+200]
            if 'decodeURIComponent' in ctx or 'get("s")' in ctx or '.s)' in ctx:
                print(f"  CALL @{m.start()}: {repr(ctx)}")

    # Also search for where Qp is called in general (first 5)
    if fn_name:
        calls = [(m.start(), js[max(0,m.start()-60):m.end()+100])
                 for m in re.finditer(re.escape(fn_name) + r'\s*\(', js)]
        print(f"\n  Total {fn_name!r} calls: {len(calls)}")
        for pos, ctx in calls[:5]:
            print(f"  @{pos}: {repr(ctx)}")


def deep_analysis(js):
    """Extra diagnostics for when Strategy 1/3 both fail."""
    print("\n" + "="*70)
    print("DEEP ANALYSIS (new obfuscation detection)")
    print("="*70)

    # 1. Show 800 chars of context around EVERY decodeURIComponent
    print("\n[A] Full context around decodeURIComponent occurrences:")
    for m in re.finditer(r'decodeURIComponent', js):
        pos = m.start()
        ctx = js[max(0, pos-300): pos+500]
        print(f"\n  @{pos}:")
        # Show in segments
        for chunk in [ctx[i:i+120] for i in range(0, len(ctx), 120)]:
            print(f"    {repr(chunk)}")

    # 2. Look for split called via bracket notation (obfuscated split)
    print("\n[B] Obfuscated .split('') patterns (bracket notation):")
    patterns = [
        r'\["split"\]\s*\(\s*""\s*\)',
        r'\["split"\]\s*\(\s*\'\'\s*\)',
        r'\.split\s*\(\s*""\s*\)',
        r'\[.{1,20}\]\s*\(\s*""\s*\)',   # x[anything]("")
        r'\[.{1,20}\]\s*\(\s*""\s*,',    # x[anything]("", ...)
    ]
    for pat in patterns:
        hits = [(m.start(), js[max(0,m.start()-60):m.end()+60]) for m in re.finditer(pat, js)]
        if hits:
            print(f"  Pattern {repr(pat)}: {len(hits)} hit(s)")
            for pos, ctx in hits[:3]:
                print(f"    @{pos}: {repr(ctx)}")

    # 3. Look for .splice and .reverse near each other
    print("\n[C] .splice(0, and .reverse() proximity:")
    splice_pos = [m.start() for m in re.finditer(r'\.splice\(0,', js)]
    reverse_pos = [m.start() for m in re.finditer(r'\.reverse\(\)', js)]
    print(f"  .splice(0, : {len(splice_pos)} occurrences: {splice_pos}")
    print(f"  .reverse()  : {len(reverse_pos)} occurrences: {reverse_pos}")
    for sp in splice_pos:
        for rv in reverse_pos:
            dist = abs(sp - rv)
            if dist < 5000:
                lo, hi = min(sp, rv), max(sp, rv)
                ctx = js[max(0, lo-200): hi+200]
                print(f"  splice@{sp} and reverse@{rv} are {dist} chars apart:")
                for chunk in [ctx[i:i+120] for i in range(0, min(len(ctx), 600), 120)]:
                    print(f"    {repr(chunk)}")

    # 4. Look for the 'u' lookup table near the suspicious decodeURIComponent
    print("\n[D] Lookup table 'u' near decodeURIComponent@37497:")
    # Find the function containing the first decodeURIComponent
    dc_hits = list(re.finditer(r'decodeURIComponent', js))
    if dc_hits:
        pos = dc_hits[0].start()
        # Show a wider window
        wide = js[max(0, pos-2000): pos+2000]
        # Look for array/object assignments that could be the lookup table
        table_hits = list(re.finditer(r'\bvar\s+u\s*=\s*\[', wide))
        table_hits += list(re.finditer(r'\bu\s*=\s*\[', wide))
        print(f"  Found {len(table_hits)} 'u=[' patterns near first decodeURIComponent")
        for th in table_hits[:3]:
            snippet = wide[th.start(): th.start()+200]
            print(f"    {repr(snippet)}")

    # 5. Search for the sig param name "s" retrieval pattern
    print("\n[E] Sig 's' parameter retrieval patterns:")
    sig_patterns = [
        r'\.get\s*\(\s*["\']s["\']\s*\)',
        r'\["s"\]',
        r'sp\s*=\s*["\']sig["\']',
        r'"sig"',
        r'"sp"',
        r'signatureCipher',
        r'cipher',
    ]
    for pat in sig_patterns:
        hits = list(re.finditer(pat, js, re.IGNORECASE))
        if hits:
            print(f"  {repr(pat)}: {len(hits)} hit(s)")
            for h in hits[:2]:
                ctx = js[max(0,h.start()-80):h.end()+80]
                print(f"    @{h.start()}: {repr(ctx)}")


def extract_sig_ops(js):
    sp = ("dq" if '.split("")' in js else "") + ("sq" if ".split('')" in js else "") + ("af" if "Array.from(" in js else "")
    jn = "dq" if '.join("")' in js else ""
    print(f"  JS: {len(js):,} bytes  sp={sp} jn={jn} rev={'.reverse()' in js} spl={'.splice(0,' in js}")

    # Strategy 1
    print("\n  [Strategy 1: join-anchor]")
    joins = [m.start() for m in re.finditer(r'\.join\(""\)', js)]
    print(f"  .join(\"\") count: {len(joins)}")
    hint = "s0-noJoin"
    for join_idx in joins:
        lb_off = max(0, join_idx - 10000)
        seg = js[lb_off: join_idx]
        if not any(t in seg for t in SPLIT_TOKENS): continue

        fd = None; fd_style = ""
        for pat, desc in FN_PATTERNS:
            mm = list(re.finditer(pat, seg))
            if mm: fd = mm[-1]; fd_style = desc; break
        if not fd:
            print(f"    join@{join_idx}: has split but no fn-def pattern matched")
            print(f"      seg[-200:] = {repr(seg[-200:])}")
            continue

        fn_name = fd.group(1); fn_param = fd.group(2)
        fd_abs = lb_off + fd.start()
        fn_brace = js.find("{", fd_abs)
        if fn_brace < 0: continue
        fn_body = extract_balanced(js, fn_brace)
        if fn_body is None:
            # Diagnose why it failed
            ctx = js[fn_brace: fn_brace+200]
            regex_hits = re.findall(r'/[^/\n]{0,60}?\{[^/\n]{0,60}?/', ctx)
            print(f"    join@{join_idx}: fn={fn_name!r} ({fd_style}) — extractBalanced FAILED")
            print(f"      code at fn_brace+0: {repr(ctx[:150])}")
            if regex_hits:
                print(f"      *** Regex literals with unmatched brace: {regex_hits[:3]}")
            continue

        fn_has_split = any(t in fn_body for t in SPLIT_TOKENS)
        fn_has_join  = any(t in fn_body for t in JOIN_TOKENS)
        if not fn_has_split or not fn_has_join:
            print(f"    join@{join_idx}: fn={fn_name!r} ({fd_style}) body incomplete split={fn_has_split} join={fn_has_join}")
            print(f"      body: {repr(fn_body[:200])}")
            continue

        hint = f"s1-noHelper/{fn_name}"
        helper_m = re.search(r'([\w$]+)\.([\w$]+)\(' + fn_param, fn_body)
        if not helper_m: helper_m = re.search(r'([\w$]+)\["[\w$]+"\]\(' + fn_param, fn_body)
        if not helper_m:
            print(f"    join@{join_idx}: fn={fn_name!r} — no helper call in body")
            print(f"      fn_body: {repr(fn_body[:300])}")
            continue

        helper_name = helper_m.group(1)
        hint = f"s2-noHelperDef/{helper_name}"
        helper_body = find_helper_body(js, helper_name, fd_abs)
        if not helper_body:
            print(f"    join@{join_idx}: fn={fn_name!r} helper={helper_name!r} — helper body not found")
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

        print(f"\n  *** Strategy 1 SUCCESS ***")
        print(f"    fn={fn_name!r} ({fd_style}), param={fn_param!r}, helper={helper_name!r}")
        print(f"    ops: {ops}")
        return ops, fn_name
    print(f"  Strategy 1 failed. Last hint: {hint}")

    # Strategy 3: call-site
    print("\n  [Strategy 3: call-site anchor]")
    cs_pats = [
        (r'\b[\w$]+&&\([\w$]+=([a-zA-Z0-9_$]+)\((?:\d+,)?decodeURIComponent', "&&(X=FN(decodeURI...)"),
        (r'\bc&&\(c=([a-zA-Z0-9$]+)\(decodeURIComponent',                       "c&&(c=FN(...)"),
        (r'[;,=]\s*([a-zA-Z0-9$]+)\(decodeURIComponent\([^)]+\.get\("s"\)',     "X=FN(get(s))"),
        (r'\.set\(["\']sig["\'],([a-zA-Z0-9$]+)\(',                             ".set('sig',FN(...))"),
        (r'b=([a-zA-Z0-9$]+)\(decodeURIComponent\(b\.get\("s"\)\)\)',           "b=FN(get(s))"),
        (r'[;({,\s=]([a-zA-Z0-9$]{2,})\s*\(\s*decodeURIComponent\(',           "FN(decodeURI(...) [broad]"),
    ]
    for pat, desc in cs_pats:
        mm = re.search(pat, js)
        if mm:
            ctx = js[max(0, mm.start()-60): mm.end()+120]
            print(f"  call-site FOUND [{desc}]: captured={mm.group(1)!r}")
            print(f"    {repr(ctx)}")
            break
    else:
        dc = len(re.findall(r'decodeURIComponent', js))
        print(f"  No call-site pattern matched. decodeURIComponent count: {dc}")
        for m in re.finditer(r'decodeURIComponent', js):
            ctx = js[max(0, m.start()-80): m.end()+80]
            print(f"    @{m.start()}: {repr(ctx)}")

    return None, None

# ── Main ──────────────────────────────────────────────────────────────────────
def find_player_url(html):
    # Same regex as ensurePlayerJsData: /s/player/HEX/*/base.js
    m = re.search(r'/s/player/[0-9a-fA-F]+/[^\s"\']*base\.js', html)
    if m: return "https://www.youtube.com" + m.group(0)
    return None

print("="*70)
print("OpenTune sig-decode tester  (v1.2.58 logic)")
print("="*70)
print(f"Using UA: {UA}\n")

# Same source list as ensurePlayerJsData in InnertubeApi.kt
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
    print("Chrome DevTools → Network → filter 'base.js' → copy Request URL")
    player_url = input("Paste player JS URL: ").strip()

if not player_url:
    sys.exit(1)

# If we got es6, also try the ias variant (Android app gets ias)
ias_url = player_url.replace("player_es6.vflset", "player_ias.vflset")

js = None
for try_url, label in [(ias_url, "player_ias (preferred)"), (player_url, "player_es6 (fallback)")]:
    if try_url == player_url and label.startswith("player_ias"):
        # es6 URL wasn't substituted (already ias or no es6 in URL)
        pass
    print(f"\nFetching {label} …  {try_url}")
    candidate = fetch(try_url)
    if len(candidate) > 100000:
        js = candidate
        print(f"OK ({len(js):,} bytes)")
        break
    else:
        print(f"  got only {len(candidate)} bytes, trying next …")

if not js:
    print("ERROR: could not fetch any player JS"); sys.exit(1)
print()

print("Running extraction …")
ops, fn_name = extract_sig_ops(js)

if not ops:
    decode_dispatcher(js)
    deep_analysis(js)

# Optional: test with a real signatureCipher
print("\n" + "="*70)
print("Optional: test with a real signatureCipher")
print("(from OpenTune app logcat, search for 'signatureCipher' or 'allFail')")
cipher_input = input("Paste signatureCipher or raw 's=' value (Enter to skip): ").strip()

if cipher_input and ops:
    import urllib.parse
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
        print(f"\nRaw sig ({len(raw_sig)} chars): {raw_sig[:80]}…")
        print(f"Decoded ({len(decoded)} chars): {decoded[:80]}…")
        print(f"Ops: {ops}")
    else:
        print("Could not parse input.")
elif cipher_input:
    print("Extraction failed — cannot decode.")

print("\nDone.")
