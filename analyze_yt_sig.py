#!/usr/bin/env python3
"""
OpenTune sig-decode tester  (v1.2.63 — Strategy 4: flat-dispatcher)
Mirrors InnertubeApi.kt extractSigOps — shows exactly where it succeeds or
fails, then auto-fetches a real signatureCipher and verifies the decode.

Run:  py analyze_yt_sig.py   (Windows)
      python3 analyze_yt_sig.py  (Mac/Linux)
"""
import re, sys, json, urllib.request, urllib.parse, urllib.error
from collections import Counter

# Same UA as InnertubeApi.kt ensurePlayerJsData
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

# ─────────────────────────────────────────────────────────────────────────────
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
# extract_balanced — mirrors extractBalancedJs from InnertubeApi.kt
# 7-state machine that understands JS strings, regex literals, and comments.
# ─────────────────────────────────────────────────────────────────────────────
def extract_balanced(js, start):
    if start >= len(js): return None
    if js[start] not in ('{', '(', '['): return None
    depth = 0
    # states: 0=code 1=str" 2=str' 3=str` 4=regex 5=regex-cc 6=line-cmt 7=block-cmt
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
            elif c in ('=', '+', '-', '*', '!', '<', '>', '~', '^', '&', '|', '?', ':', '%'):
                regex_ctx = True
            elif c.isalpha() or c in ('_', '$', '0123456789'): regex_ctx = False
        i += 1
    return None

# ─────────────────────────────────────────────────────────────────────────────
# Helpers shared by Strategies 1, 2, 3
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

# ─────────────────────────────────────────────────────────────────────────────
# Strategy 4: flat-dispatcher (YouTube 2026+)
# Mirrors InnertubeApi.kt extractSigOpsDispatcher exactly.
# Dynamically extracts all XOR constants — does NOT hardcode player-specific values.
# ─────────────────────────────────────────────────────────────────────────────

def discover_p_from_table(js, table_var, u):
    """
    Table-first p discovery: find XOR key p from consistent XOR-constant triples.
    Used when the nested call-site pattern is absent (player 82ae1e3c+).
    Returns (p, xor_var, split_K, reverse_K, splice_K) or None.
    """
    if not all(x in u for x in ('split', 'reverse', 'splice')):
        return None
    split_idx   = u.index('split')
    reverse_idx = u.index('reverse')
    splice_idx  = u.index('splice')
    diff_sr = split_idx ^ reverse_idx
    diff_ss = split_idx ^ splice_idx
    eT = re.escape(table_var)
    by_xv = {}
    for m in re.finditer(r'([\w$]+)\[' + eT + r'\[([\w$]+)\^(\d+)\]\]', js):
        xv = m.group(2)
        k  = int(m.group(3))
        if xv != table_var:
            by_xv.setdefault(xv, set()).add(k)
    for xv, constants in by_xv.items():
        for k1 in constants:
            k2 = k1 ^ diff_sr
            k3 = k1 ^ diff_ss
            if k2 in constants and k3 in constants:
                p_cand = k1 ^ split_idx
                if (p_cand ^ k2) == reverse_idx and (p_cand ^ k3) == splice_idx:
                    return (p_cand, xv, k1, k2, k3)
    return None


def _run_dispatcher_steps(js, disp_body, table_var, xor_var, p, u):
    """
    Shared Steps 4b-9 for Strategy 4.  All inputs (p, xor_var, table_var,
    disp_body, u) are provided by either the call-site path or the table-first path.
    Returns (ops_list, status) or (None, error_string).
    """
    eT = re.escape(table_var); eX = re.escape(xor_var)

    # Step 4b: split-result array variable name.
    _sr = (re.search(r'var\s+([\w$]+)\s*=\s*\w+\[' + eT + r'\[' + eX + r'\^\d+\]\]\(' + eT + r'\[\d+\]\)', disp_body)
        or re.search(r'var\s+([\w$]+)\s*=\s*\w+\[' + eT + r'\[' + eX + r'\^\d+\]\]\(""\)', disp_body)
        or re.search(r'return\s+([\w$]+)\[' + eT + r'\[' + eX + r'\^\d+\]\]', disp_body))
    split_var = _sr.group(1) if _sr else 'b'
    print(f"  [Strategy 4] splitVar={split_var!r}")

    # Step 5: helper object name.
    # Primary: HELPER[TABLE[XOR^N]](SPLIT_VAR  — splitVar as first arg.
    # Fallback: variable with TABLE[XOR^N] subscript called 2+ times.
    eS = re.escape(split_var)
    hm = re.search(r'([\w$]+)\[' + eT + r'\[' + eX + r'\^\d+\]\]\s*\(' + eS, disp_body)
    if not hm:
        _counts = Counter(
            m.group(1) for m in re.finditer(r'([\w$]+)\[' + eT + r'\[' + eX + r'\^\d+\]\]\(', disp_body)
            if m.group(1) not in (table_var, xor_var, split_var)
        )
        _best = _counts.most_common(1)
        if _best and _best[0][1] >= 2:
            helper_name = _best[0][0]
        else:
            return None, f"d4-noHelperVar({table_var}/{split_var})"
    else:
        helper_name = hm.group(1)
    print(f"  [Strategy 4] dispatcher={xor_var!r} tableVar={table_var!r} helper={helper_name!r}")

    # Step 7: verify string table has all required op names.
    for op in ('split', 'join', 'reverse', 'splice'):
        if op not in u:
            return None, f"d6-missing({op})InTable"
    split_idx   = u.index('split')
    join_idx    = u.index('join')
    reverse_idx = u.index('reverse')
    splice_idx  = u.index('splice')
    print(f"  [Strategy 4] table OK: p={p} split@{split_idx} join@{join_idx} "
          f"reverse@{reverse_idx} splice@{splice_idx}")

    # Step 8: helper object body + pwMap (method → op kind)
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
    rev_mark = f'{table_var}[{reverse_idx}]'
    spl_mark = f'{table_var}[{splice_idx}]'
    for m in re.finditer(r'([\w$]+)\s*:\s*function\s*\(([^)]*)\)\s*\{([^}]*)\}', pw_body):
        mn      = m.group(1)
        n_param = len([x for x in m.group(2).split(',') if x.strip()])
        body    = m.group(3)
        if n_param == 1 and rev_mark in body:
            pw_map[mn] = ('reverse', 0)
        elif n_param == 2 and spl_mark in body:
            pw_map[mn] = ('splice', 0)
        elif n_param == 2 and '%' in body and mn not in pw_map:
            pw_map[mn] = ('swap', 0)
    if len(pw_map) < 2:
        return None, f"d7-pwMapSmall({helper_name} {pw_map})"
    print(f"  [Strategy 4] pw_map: {pw_map}")

    # Step 9: extract ordered op-call sequence from dispatcher body.
    call_re = re.compile(
        r'%s\[%s\[%s\^(\d+)\]\]\(%s(?:,%s\^(\d+)|,(\d+))?\)' % (
            re.escape(helper_name), re.escape(table_var),
            re.escape(xor_var), re.escape(split_var), re.escape(xor_var))
    )
    ops = []
    for m in call_re.finditer(disp_body):
        method_xor = int(m.group(1))
        method_idx = p ^ method_xor
        method_nm  = u[method_idx] if method_idx < len(u) else None
        if not method_nm or method_nm not in pw_map:
            continue
        kind, _ = pw_map[method_nm]
        if m.group(2):
            arg = p ^ int(m.group(2))
        elif m.group(3):
            arg = int(m.group(3))
        else:
            arg = 0
        ops.append((kind, arg))
    if not ops:
        return None, f"d8-emptyOps({helper_name})"
    return ops, f"OK p={p} {helper_name} {len(ops)} ops"


def extract_dispatcher_ops(js):
    """
    Strategy 4: flat-dispatcher extraction.
    Returns (ops_list, status) or (None, error_string).
    """
    # Call-site patterns: FNAME(R1,K1, FNAME(R2,K2, SIG.??))
    # p = R1 ^ K1.  Inner call wraps the raw sig property.
    _cs_patterns = [
        # Exact: inner last arg is OBJ.s  (original / 5cabb421)
        r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)',
        # Sig property renamed (OBJ.sig, OBJ.sc, OBJ.se, ...)
        r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.\w+\s*\)\s*\)',
        # Any short inner arg (no nested parens, ≤60 chars)
        r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,[^\(\)]{1,60}\)\s*\)',
    ]

    # ── Call-site path ────────────────────────────────────────────────────────
    cs = None
    for _pat in _cs_patterns:
        cs = re.search(_pat, js)
        if cs: break

    if cs:
        disp_name = cs.group(1)
        outer_r, outer_k = int(cs.group(2)), int(cs.group(3))
        p = outer_r ^ outer_k
        print(f"  [Strategy 4] call site: {disp_name}({outer_r},{outer_k},...) -> p={p}")

        # Step 2: dispatcher function body
        fn_idx = js.rfind(f'{disp_name}=function(', 0, cs.start())
        if fn_idx < 0: fn_idx = js.find(f'{disp_name}=function(')
        if fn_idx < 0: fn_idx = js.rfind(f'var {disp_name}=function(', 0, cs.start())
        if fn_idx < 0: return None, f"d1-noFnDef({disp_name})"
        brace = js.find('{', fn_idx)
        disp_body = extract_balanced(js, brace)
        if not disp_body: return None, f"d1-noBody({disp_name})"
        print(f"  [Strategy 4] disp_body[:400]: {disp_body[:400]!r}")

        # Step 3: XOR variable name ("p" from "var p = K^R")
        xm = re.search(r'var\s+([\w$]+)\s*=\s*[\w$]+\s*\^\s*[\w$]+', disp_body)
        if not xm: return None, f"d2-noXorVar({disp_name})"
        xor_var = xm.group(1)

        # Step 4: string-table variable — any TABLE[xorVar^N] access.
        tm = re.search(r'([\w$]+)\[' + re.escape(xor_var) + r'\^\d+\]', disp_body)
        if not tm: return None, f"d3-noTableVar({disp_name}/{xor_var})"
        table_var = tm.group(1)

        # Step 6: string table (separator: "{", ";", or "|")
        u = None
        for _sep in ['{', ';', '|']:
            te = re.search(
                r'(?<![.\w])' + re.escape(table_var) + r'\s*=\s*"([^"]{300,})"\s*\.split\s*\(\s*"'
                + re.escape(_sep) + r'"\s*\)', js)
            if te:
                u = te.group(1).split(_sep)
                break
        if u is None: return None, f"d5-noTableStr({table_var})"

    else:
        # ── Table-first path: no call site found ─────────────────────────────
        print("  [Strategy 4] d0-noCallSite — trying table-first discovery...")
        found = False
        p = xor_var = table_var = disp_body = u = None
        for _sep in ['{', ';', '|']:
            te = re.search(
                r'(?<![.\w])(\w+)\s*=\s*"([^"]{200,})"\s*\.split\s*\(\s*"'
                + re.escape(_sep) + r'"\s*\)', js)
            if not te: continue
            _tv = te.group(1)
            _u  = te.group(2).split(_sep)
            if not all(x in _u for x in ('split', 'reverse', 'splice', 'join')): continue
            _res = discover_p_from_table(js, _tv, _u)
            if not _res:
                print(f"  [Strategy 4] table sep={_sep!r} found but could not discover p from XOR triples")
                continue
            p, xor_var, _k_spl, _k_rev, _k_spli = _res
            table_var = _tv; u = _u
            print(f"  [Strategy 4 table-first] table_var={table_var!r} sep={_sep!r} p={p} xorVar={xor_var!r}")

            # Find dispatcher body: function with "var xorVar = A^B"
            # AND at least 3 TABLE[xorVar^N] accesses in the body.
            disp_body = None
            for _m in re.finditer(r'var\s+' + re.escape(xor_var) + r'\s*=\s*\w+\s*\^\s*\w+', js):
                _fn_start = js.rfind('function', 0, _m.start())
                if _fn_start < 0: continue
                _brace = js.find('{', _fn_start)
                if _brace < 0: continue
                _body = extract_balanced(js, _brace)
                if not _body: continue
                _cnt = len(re.findall(
                    re.escape(table_var) + r'\[' + re.escape(xor_var) + r'\^\d+\]', _body))
                if _cnt >= 3:
                    disp_body = _body; break

            if not disp_body:
                print(f"  [Strategy 4 table-first] dispatcher body not found for xorVar={xor_var!r}")
                continue
            print(f"  [Strategy 4 table-first] dispatcher body found ({len(disp_body)} chars)")
            print(f"  [Strategy 4 table-first] disp_body[:400]: {disp_body[:400]!r}")
            found = True; break

        if not found:
            return None, "d0-noCallSite"

    # ── Shared Steps 4b-9 ────────────────────────────────────────────────────
    return _run_dispatcher_steps(js, disp_body, table_var, xor_var, p, u)


# ─────────────────────────────────────────────────────────────────────────────
# Strategies 1 + 3 (original patterns, unchanged)
# ─────────────────────────────────────────────────────────────────────────────
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
    sp = (("dq" if '.split("")' in js else "") + ("sq" if ".split('')" in js else "")
          + ("af" if "Array.from(" in js else ""))
    jn = "dq" if '.join("")' in js else ""
    print(f"  JS: {len(js):,} bytes  sp={sp} jn={jn} rev={'.reverse()' in js} spl={'.splice(0,' in js}")

    # Strategy 1: join-anchor
    print("\n  [Strategy 1: join-anchor]")
    joins = [m.start() for m in re.finditer(r'\.join\(""\)', js)]
    print(f"  .join(\"\") count: {len(joins)}")
    for join_idx in joins:
        lb_off = max(0, join_idx - 10000)
        seg = js[lb_off:join_idx]
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
            print(f"    join@{join_idx}: fn={fn_name!r} ({fd_style}) body incomplete "
                  f"split={any(t in fn_body for t in SPLIT_TOKENS)} join={any(t in fn_body for t in JOIN_TOKENS)}")
            print(f"      body: {fn_body[:200]!r}")
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
        for m in re.finditer(r'%s\.([\w$]+)\(%s(?:,(\d+))?\)' %
                              (re.escape(helper_name), re.escape(fn_param)), fn_body):
            method = m.group(1); n = int(m.group(2)) if m.group(2) else 0
            if method not in op_map: ops = None; break
            kind, _ = op_map[method]; ops.append((kind, n))
        if ops:
            print(f"\n  *** Strategy 1 SUCCESS: fn={fn_name!r} helper={helper_name!r} ***")
            print(f"    ops: {ops}")
            return ops, fn_name
    print(f"  Strategy 1 failed.")

    # Strategy 3: call-site anchor
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


# ─────────────────────────────────────────────────────────────────────────────
# Dispatcher diagnostic (called only when Strategy 4 fails)
# ─────────────────────────────────────────────────────────────────────────────
def show_dispatcher_analysis(js):
    print("\n" + "="*70)
    print("DISPATCHER PATTERN ANALYSIS")
    print("="*70)

    # A: Check all call-site variants from most specific to broadest
    _pats = [
        (r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)', 'OBJ.s'),
        (r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.\w+\s*\)\s*\)', 'OBJ.any'),
        (r'(\w+)\s*\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\s*\(\s*\d+\s*,\s*\d+\s*,[^\(\)]{1,60}\)\s*\)', 'anyArg'),
        (r'(\w+)\s*\(\s*\d+\s*,\s*\d+\s*,\s*\1\s*\(', 'nested-same-fn'),
    ]
    found_cs = False
    for _p, _label in _pats:
        cs = re.search(_p, js)
        if cs:
            snippet = js[max(0, cs.start()-40):cs.end()+40]
            print(f"Call site [{_label}]: {snippet!r}")
            found_cs = True
            break
    if not found_cs:
        print("No call site found (tried all variants).")
        for m in re.finditer(r'(\b\w{1,6})\s*\(\s*\d+\s*,\s*\d+\s*,', js):
            fn = m.group(1)
            window = js[m.start():m.start()+200]
            if re.search(r'\b' + re.escape(fn) + r'\s*\(\s*\d', window[m.end()-m.start():]):
                print(f"  Candidate nested fn: {fn!r}  context: {window[:100]!r}")
                break

    # B: String table search — no hardcoded p assumption; use discover_p_from_table
    sep_found = False
    found_table_var = None
    found_u = None
    for sep in ['{', ';', '|', '~', '^']:
        for m in re.finditer(
                r'(?<![.\w])(\w+)\s*=\s*"([^"\n]{300,})"\s*\.split\s*\(\s*"'
                + re.escape(sep) + r'"\s*\)', js):
            parts = m.group(2).split(sep)
            if len(parts) < 20: continue
            idx = {e: i for i, e in enumerate(parts)}
            if not all(x in idx for x in ('split', 'join', 'reverse', 'splice')): continue
            found_table_var = m.group(1)
            found_u = parts
            print(f"\nString table (sep={sep!r}) at @{m.start()}: var={found_table_var!r} {len(parts)} entries")
            for key in ('split', 'join', 'reverse', 'splice'):
                print(f"  u[{idx[key]}] = {key!r}")
            sep_found = True
            break
        if sep_found: break
    if not sep_found:
        print("No string table (with split/join/reverse/splice) found for any separator.")
        for m in re.finditer(r'"([^"\n]{500,})"', js):
            c = m.group(1)
            print(f"  Long string @{m.start()} ({len(c)} chars): {c[:80]!r}")
            break

    if sep_found:
        result = discover_p_from_table(js, found_table_var, found_u)
        if result:
            p_disc, xv_disc, k1, k2, k3 = result
            print(f"\n  [Table-first] p={p_disc} xorVar={xv_disc!r} split_K={k1} reverse_K={k2} splice_K={k3}")
            for m in re.finditer(r'var\s+' + re.escape(xv_disc) + r'\s*=\s*\w+\s*\^\s*\w+', js):
                ctx = js[max(0, m.start()-50):m.end()+100]
                print(f"  xorVar decl: {ctx!r}")
                break
        else:
            print("\n  [Table-first] Could not discover p from XOR triples")

    # C: Show .s / .sp sig property accesses
    print("\nSig property accesses (looking for .s .sp .sig etc.):")
    for m in re.finditer(r'[\w$]+\s*\.\s*(s|sp|sig|sc|se)\b', js):
        ctx = js[max(0, m.start()-30):m.end()+30]
        print(f"  .{m.group(1)} at @{m.start()}: {ctx!r}")
        break

    # D: signatureCipher presence
    for pat in [r'signatureCipher', r'"s"\s*:', r'\.s\b']:
        mm = re.search(pat, js)
        if mm:
            print(f"  [{pat}] found @{mm.start()}: {js[max(0,mm.start()-20):mm.end()+40]!r}")
            break


# ─────────────────────────────────────────────────────────────────────────────
# Auto-fetch signatureCipher from YouTube
# ─────────────────────────────────────────────────────────────────────────────
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
    try:
        root = json.loads(raw)
        status = root.get("playabilityStatus", {}).get("status", "?")
        if status != "OK":
            reason = root.get("playabilityStatus", {}).get("reason", "")
            return None, None, f"status={status} {reason}"
        for fmt in root.get("streamingData", {}).get("adaptiveFormats", []):
            if "audio/" not in fmt.get("mimeType", ""): continue
            cipher = fmt.get("signatureCipher") or fmt.get("cipher")
            if cipher:
                params = {}
                for part in cipher.split("&"):
                    if "=" in part:
                        k, v = part.split("=", 1)
                        params[k] = urllib.parse.unquote(v)
                return cipher, params.get("url", ""), "ok"
        return None, None, "no-cipher(directURL)"
    except Exception as e:
        return None, None, f"parse-error:{e}"

def fetch_cipher_from_youtube(video_id, sig_ts=0):
    sig_ts_frag = (f',"playbackContext":{{"contentPlaybackContext":{{"signatureTimestamp":{sig_ts}}}}}'
                   if sig_ts > 0 else "")
    clients = [
        {
            "name": "WEB_EMBEDDED_PLAYER", "id": "56", "version": "1.20250101.09.00",
            "ua": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36",
            "url": "https://www.youtube.com/youtubei/v1/player",
            "extra_context": f'"thirdParty":{{"embedUrl":"https://www.youtube.com/watch?v={video_id}"}}',
        },
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
        if not raw: continue
        cipher, base_url, msg = _extract_cipher_from_response(raw)
        if cipher:
            print(f"    [{cl['name']}] got signatureCipher OK")
            return cipher, base_url
        print(f"    [{cl['name']}] {msg}")
    return None, None

def decode_cipher_url(cipher, ops):
    params = {}
    for part in cipher.split("&"):
        if "=" in part:
            k, v = part.split("=", 1)
            params[k] = urllib.parse.unquote(v)
    base_url  = params.get("url", "")
    raw_sig   = params.get("s", "")
    sig_param = params.get("sp", "sig")
    if not base_url or not raw_sig: return None
    decoded_sig = apply_ops(raw_sig, ops)
    sep = "&" if "?" in base_url else "?"
    return f"{base_url}{sep}{sig_param}={urllib.parse.quote(decoded_sig, safe='')}"

def test_url_accessible(url):
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


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────
def find_player_url(html):
    m = re.search(r'/s/player/[0-9a-fA-F]+/[^\s"\']*base\.js', html)
    if m: return "https://www.youtube.com" + m.group(0)
    return None

# ─────────────────────────────────────────────────────────────────────────────
# N-decode extraction — handles literal "n" AND table-indexed "n" (5cabb421+)
# ─────────────────────────────────────────────────────────────────────────────
def _find_n_arr_declaration(js, arr_name):
    """Find n-decode array declaration; return (code, token) or (None, None)."""
    bracket_idx = -1
    found_token = None
    for token in [f"var {arr_name}=[", f"const {arr_name}=[", f"let {arr_name}=["]:
        idx = js.find(token)
        if idx >= 0:
            bracket_idx = idx + len(token) - 1
            found_token = token
            break
    if bracket_idx < 0:
        for token in [f",{arr_name}=[", f";{arr_name}=["]:
            idx = js.find(token)
            if idx >= 0:
                bracket_idx = idx + len(token) - 1
                found_token = token
                break
    if bracket_idx < 0:
        return None, None
    fn_code = extract_balanced(js, bracket_idx)
    return fn_code, found_token


def extract_n_decode_fn(js, table_var=None, u_entries=None, p=None):
    """
    Extract the YouTube n-parameter decode function from player JS.
    Returns (fn_code_str, arr_name, method) or (None, None, reason).

    Strategy 1: literal "n" (.get("n"), .set("n",...))
    Strategy 2: table-indexed "n" — player stores "n" in the string table u[K]
                so call site uses u[K] or u[xorVar^CONST] instead of "n"
    Strategy 3: broad fallback — find short [function(){}] arrays with decode ops
    """
    # ── Strategy 1: literal "n" ───────────────────────────────────────────────
    for pat, desc in [
        (r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\[0\]\(', "get(n)[0]"),
        (r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\(',        "get(n)()"),
        (r'\.set\("n",([a-zA-Z0-9$]{2,})\[0\]\(',                           "set(n)[0]"),
        (r'\.set\("n",([a-zA-Z0-9$]{2,})\(',                                "set(n)()"),
        (r"\.get\('n'\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\[0\]\(",   "get('n')[0]"),
        (r"\.get\('n'\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{2,})\(",         "get('n')()"),
    ]:
        m = re.search(pat, js)
        if m:
            arr_name = m.group(1)
            fn_code, token = _find_n_arr_declaration(js, arr_name)
            if fn_code:
                print(f"  n-decode [S1-{desc}] arrName={arr_name!r} via {token!r} ({len(fn_code)} chars)")
                print(f"  preview: {fn_code[:80].replace(chr(10),' ')!r}")
                return fn_code, arr_name, f"S1-{desc}"
            print(f"  n-decode [S1-{desc}] arrName={arr_name!r} but declaration missing")

    # ── Strategy 2: table-indexed "n" ────────────────────────────────────────
    if table_var and u_entries:
        n_idx = next((i for i, e in enumerate(u_entries) if e == "n"), -1)
        print(f"  n-decode [S2] table={table_var!r} len={len(u_entries)} "
              f"'n' idx={n_idx} p={p}")
        if n_idx >= 0:
            # 2a: direct index  u[n_idx]
            for pat, desc in [
                (rf'\.get\({re.escape(table_var)}\[{n_idx}\]\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{{2,}})\[0\]\(', f"get(u[{n_idx}])[0]"),
                (rf'\.get\({re.escape(table_var)}\[{n_idx}\]\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{{2,}})\(',        f"get(u[{n_idx}])()"),
                (rf'\.set\({re.escape(table_var)}\[{n_idx}\],([a-zA-Z0-9$]{{2,}})\[0\]\(',                        f"set(u[{n_idx}])[0]"),
                (rf'\.set\({re.escape(table_var)}\[{n_idx}\],([a-zA-Z0-9$]{{2,}})\(',                             f"set(u[{n_idx}])()"),
            ]:
                m = re.search(pat, js)
                if m:
                    arr_name = m.group(1)
                    fn_code, token = _find_n_arr_declaration(js, arr_name)
                    if fn_code:
                        print(f"  n-decode [S2a-{desc}] arrName={arr_name!r} ({len(fn_code)} chars)")
                        return fn_code, arr_name, f"S2a-{desc}"

            # 2b: XOR-obfuscated  u[xorVar ^ CONST]  where p^CONST == n_idx
            if p is not None:
                xor_const = p ^ n_idx
                print(f"  n-decode [S2b] trying p^{xor_const}={n_idx} patterns")
                for pat, desc in [
                    (rf'\.get\({re.escape(table_var)}\[[a-zA-Z0-9$]+\^{xor_const}\]\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{{2,}})\[0\]\(', f"get(u[x^{xor_const}])[0]"),
                    (rf'\.get\({re.escape(table_var)}\[[a-zA-Z0-9$]+\^{xor_const}\]\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]{{2,}})\(',        f"get(u[x^{xor_const}])()"),
                    (rf'\.set\({re.escape(table_var)}\[[a-zA-Z0-9$]+\^{xor_const}\],([a-zA-Z0-9$]{{2,}})\[0\]\(',                        f"set(u[x^{xor_const}])[0]"),
                ]:
                    m = re.search(pat, js)
                    if m:
                        arr_name = m.group(1)
                        fn_code, token = _find_n_arr_declaration(js, arr_name)
                        if fn_code:
                            print(f"  n-decode [S2b-{desc}] arrName={arr_name!r} ({len(fn_code)} chars)")
                            return fn_code, arr_name, f"S2b-{desc}"
        else:
            print(f"  n-decode [S2] 'n' NOT in table — showing first 30 entries:")
            print(f"    {u_entries[:30]}")

    # ── Strategy 3: broad search ──────────────────────────────────────────────
    print(f"  n-decode [S3] broad search for [function(){{}}] arrays...")
    candidates = []
    for m in re.finditer(r'(?:var|const|let|,|;)\s*([\w$]{1,5})\s*=\s*\[function\s*\(', js):
        arr_name = m.group(1)
        bracket_idx = js.find('[', m.start())
        if bracket_idx < 0: continue
        fn_code = extract_balanced(js, bracket_idx)
        if not fn_code or len(fn_code) > 8000 or len(fn_code) < 50: continue
        score = sum(1 for kw in ['charCodeAt', 'fromCharCode', 'String.fromCharCode',
                                  'split', 'join', 'length', 'Math', '%', '^', '&']
                    if kw in fn_code)
        candidates.append((score, arr_name, fn_code))
    candidates.sort(reverse=True)
    print(f"  n-decode [S3] {len(candidates)} candidates (top 5):")
    for score, name, code in candidates[:5]:
        print(f"    {name!r} score={score} len={len(code)}: {code[:70].replace(chr(10),' ')!r}")
    if candidates and candidates[0][0] >= 3:
        best_score, best_name, best_code = candidates[0]
        print(f"  n-decode [S3] picking {best_name!r} (score={best_score})")
        return best_code, best_name, f"S3-score{best_score}"

    return None, None, "all-strategies-failed"


def decode_n_with_node(fn_code, n_raw):
    """
    Execute the extracted n-decode function using Node.js.
    Returns (decoded_n, error_str) — one of them will be None.
    """
    import subprocess, tempfile, os
    try:
        subprocess.run(["node", "--version"], capture_output=True, check=True, timeout=5)
    except Exception:
        return None, "Node.js not available (install from nodejs.org)"

    js_code = (
        f"var _nDecFn = {fn_code};\n"
        f"var n = {json.dumps(n_raw)};\n"
        "try {\n"
        "  var r = (_nDecFn instanceof Array) ? _nDecFn[0](n) : _nDecFn(n);\n"
        "  process.stdout.write(String(r || ''));\n"
        "} catch(e) {\n"
        "  process.stderr.write('ERR: ' + e.toString());\n"
        "  process.exit(1);\n"
        "}\n"
    )
    with tempfile.NamedTemporaryFile(mode='w', suffix='.js', delete=False, encoding='utf-8') as f:
        f.write(js_code)
        tmp = f.name
    try:
        result = subprocess.run(["node", tmp], capture_output=True, text=True, timeout=15)
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip(), None
        err = result.stderr.strip() or f"exit {result.returncode} no output"
        return None, err
    except subprocess.TimeoutExpired:
        return None, "Node.js timed out"
    finally:
        try:
            os.unlink(tmp)
        except Exception:
            pass


def extract_n_from_url(url):
    """Extract the raw n-parameter value from a CDN URL."""
    m = re.search(r'[?&]n=([^&]+)', url)
    return urllib.parse.unquote(m.group(1)) if m else None


def replace_n_in_url(url, old_n, new_n):
    """Replace n-param value in a URL."""
    encoded_old = urllib.parse.quote(old_n, safe='')
    encoded_new = urllib.parse.quote(new_n, safe='')
    # Try both encoded and raw forms
    for old in [f"&n={encoded_old}", f"?n={encoded_old}", f"&n={old_n}", f"?n={old_n}"]:
        new = old.replace(old_n if old_n in old else encoded_old, new_n if old_n in old else encoded_new)
        if old in url:
            return url.replace(old, new, 1)
    return url

print("="*70)
print("OpenTune sig-decode tester  (v1.2.68 — Strategy 4 + n-decode test)")
print("="*70)
print(f"Using UA: {UA}\n")

# ── Optional: target a specific player hash from app diagnostics ─────────────
# e.g. if logcat shows  hint=allFail(2487210b ...)  enter  2487210b
print("Player hash override (from app logcat, e.g. 2487210b) — Enter to auto-detect:")
_hash_input = input(">> ").strip()

js = None
player_url = None

if _hash_input:
    player_url = f"https://www.youtube.com/s/player/{_hash_input}/player_ias.vflset/en_US/base.js"
    print(f"\nFetching player {_hash_input} directly ...")
    js_candidate = fetch(player_url)
    if len(js_candidate) > 100_000:
        js = js_candidate
        print(f"OK ({len(js):,} bytes)")
    else:
        print(f"  got only {len(js_candidate)} bytes — hash may be wrong or expired")
        player_url = None

if not js:
    SOURCE_URLS = [
        "https://www.youtube.com/watch?v=jNQXAC9IVRw",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://www.youtube.com",
    ]
    for page in SOURCE_URLS:
        print(f"Trying {page} &")
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
    for try_url, label in [(ias_url, "player_ias (preferred)"), (player_url, "fallback")]:
        print(f"\nFetching {label} &  {try_url}")
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

sig_ts_m = re.search(r'signatureTimestamp[=:]\s*(\d+)', js)
sig_ts = int(sig_ts_m.group(1)) if sig_ts_m else 0
if sig_ts: print(f"signatureTimestamp: {sig_ts}")

print("Running extraction &")
ops, fn_name = extract_sig_ops(js)

if not ops:
    print("\n  [Strategy 4: flat-dispatcher (YouTube 2026+)]")
    ops, hint = extract_dispatcher_ops(js)
    if ops:
        print(f"\n*** Strategy 4 SUCCESS: {hint} ***")
        print(f"    ops ({len(ops)}): {ops}")
    else:
        print(f"\n  Strategy 4 failed: {hint}")
        show_dispatcher_analysis(js)

# ── N-decode extraction test ─────────────────────────────────────────────────
print("\n" + "="*70)
print("N-DECODE EXTRACTION TEST (root cause of CDN 403 in v1.2.67 and earlier)")
print("="*70)

# Re-extract the string table variables so Strategy 2 can search for u[n_idx]
_disp_table_var = _disp_u = _disp_p = None
for _sep in ['{', ';', '|']:
    _te = re.search(
        r'(?<![.\w])(\w+)\s*=\s*"([^"]{300,})"\s*\.split\s*\(\s*"' + re.escape(_sep) + r'"\s*\)', js)
    if not _te: continue
    _tv, _u_ent = _te.group(1), _te.group(2).split(_sep)
    if all(x in _u_ent for x in ('split', 'join', 'reverse', 'splice')):
        _disp_table_var, _disp_u = _tv, _u_ent
        _pr = discover_p_from_table(js, _tv, _u_ent)
        if _pr: _disp_p = _pr[0]
        print(f"  [n-decode setup] table={_disp_table_var!r} sep={_sep!r} "
              f"len={len(_disp_u)} p={_disp_p}")
        break
if not _disp_table_var:
    print("  [n-decode setup] no string table found — Strategy 2 disabled")

n_fn_code, n_arr_name, n_method = extract_n_decode_fn(
    js, table_var=_disp_table_var, u_entries=_disp_u, p=_disp_p)
if n_fn_code:
    print(f"  ✓ n-decode function FOUND via {n_method} ({len(n_fn_code)} chars)")
else:
    print(f"  ✗ n-decode: {n_method}")
    print(f"    → YouTube may have changed the n-decode call site pattern")

# ── End-to-end cipher test ────────────────────────────────────────────────────
print("\n" + "="*70)
print("END-TO-END TEST: auto-fetch signatureCipher, sig decode, then n-decode")
print("="*70)

if not ops:
    print("Sig extraction failed — skipping cipher test.")
else:
    cipher = None
    for vid in YTMUSIC_VIDEOS:
        print(f"Fetching cipher for video {vid} ...")
        cipher, base_url = fetch_cipher_from_youtube(vid, sig_ts)
        if cipher:
            print(f"  Got signatureCipher ({len(cipher)} chars)")
            break

    if cipher:
        decoded_url = decode_cipher_url(cipher, ops)
        if not decoded_url:
            print("  Could not decode cipher URL (parse error)")
        else:
            print(f"\nSig-decoded URL (first 120): {decoded_url[:120]}...")

            # --- Test 1: URL with raw n (as the app sends when n-decode fails) ---
            n_raw = extract_n_from_url(decoded_url)
            print(f"\n[Test 1] URL with RAW n-param (n={n_raw[:20] if n_raw else 'N/A'}...)")
            print("Testing HEAD request ...")
            status1, err1 = test_url_accessible(decoded_url)
            if status1 in (200, 206):
                print(f"  HTTP {status1} — raw n already works (no n-decode needed for this video)")
            elif status1 == 403:
                print(f"  HTTP 403 — raw n rejected by CDN (n-decode IS required)")
            else:
                print(f"  HTTP {status1} err={err1}")

            # --- Test 2: URL with decoded n (using Node.js) ---
            if n_raw and n_fn_code:
                print(f"\n[Test 2] Decode n-param using Node.js ...")
                n_decoded, n_err = decode_n_with_node(n_fn_code, n_raw)
                if n_decoded and n_decoded != n_raw:
                    print(f"  n decoded: {n_raw[:20]}... → {n_decoded[:20]}...")
                    url_with_decoded_n = replace_n_in_url(decoded_url, n_raw, n_decoded)
                    print(f"  Testing URL with decoded n ...")
                    status2, err2 = test_url_accessible(url_with_decoded_n)
                    if status2 in (200, 206):
                        print(f"  HTTP {status2} — SUCCESS! Sig + n-decode both work correctly.")
                        if status1 == 403:
                            print(f"  CONFIRMED: n-decode fixes the 403. App needs n-decode to work.")
                    elif status2 == 403:
                        print(f"  HTTP 403 even with decoded n — sig may still be wrong, or video restricted.")
                    else:
                        print(f"  HTTP {status2} err={err2}")
                elif n_decoded == n_raw:
                    print(f"  n-decode returned same value — function may be a no-op for this n")
                    print(f"  n value: {n_raw[:40]}")
                else:
                    print(f"  n-decode FAILED: {n_err}")
                    print(f"  → This is what happens in the app when n-decode can't run")
            elif not n_fn_code:
                print(f"\n[Test 2] Skipped — n-decode function not extracted from player JS")
                print(f"  → App will use raw n → CDN 403 (this is the current bug being fixed in v1.2.68)")
            elif not n_raw:
                print(f"\n[Test 2] Skipped — no n-param in decoded URL")
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
        print(f"Raw sig    ({len(raw_sig)} chars): {raw_sig[:80]}...")
        print(f"Decoded    ({len(decoded)} chars): {decoded[:80]}...")
        print(f"Ops used: {ops}")
    else:
        print("Could not parse input.")
elif cipher_input:
    print("Extraction failed — cannot decode.")

print("\nDone.")
