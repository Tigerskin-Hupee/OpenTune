#!/usr/bin/env python3
"""
YouTube n-param decode extraction tester.

Usage:
  python3 test_ndecode.py                  # download player 5cabb421 automatically
  python3 test_ndecode.py base.js          # use a local player JS file
  python3 test_ndecode.py <player_id>      # e.g. python3 test_ndecode.py 5cabb421

Output tells you:
  - What .set("n",...) / .get("n") patterns exist in the JS
  - Which extraction strategy succeeds or fails, and why
  - Whether the extracted IIFE actually transforms n correctly (needs node)
"""

import re, sys, textwrap, urllib.request, subprocess, shutil

KNOWN_PLAYER_ID = "5cabb421"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36"

# ─── Download ─────────────────────────────────────────────────────────────────

def player_url(pid):
    return f"https://www.youtube.com/s/player/{pid}/player_ias.vflset/en_US/base.js"

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Referer": "https://www.youtube.com/"})
    return urllib.request.urlopen(req, timeout=30).read().decode("utf-8", errors="replace")

def find_player_id_from_homepage():
    print("[*] Fetching YouTube homepage to find current player ID...")
    html = fetch("https://www.youtube.com/")
    m = re.search(r'/s/player/([a-f0-9]{8})/', html)
    if m:
        return m.group(1)
    raise RuntimeError("Player ID not found in homepage HTML")

def load_js(arg=None):
    if arg and arg.endswith(".js"):
        print(f"[*] Loading local file: {arg}")
        with open(arg, encoding="utf-8", errors="replace") as f:
            return f.read(), arg
    pid = arg or KNOWN_PLAYER_ID
    url = player_url(pid)
    try:
        print(f"[*] Downloading player {pid}...")
        js = fetch(url)
        print(f"[*] Got {len(js):,} bytes")
        return js, url
    except Exception as e:
        print(f"[!] Direct download failed ({e}), trying via homepage...")
        pid = find_player_id_from_homepage()
        url = player_url(pid)
        js = fetch(url)
        print(f"[*] Got player {pid}, {len(js):,} bytes")
        return js, url

# ─── Helpers ──────────────────────────────────────────────────────────────────

def sep(title=""):
    print()
    if title:
        print(f"{'─'*4} {title} {'─'*(60-len(title))}")
    else:
        print("─" * 66)

def ctx(js, pos, before=60, after=80):
    """Return context string around position."""
    s = max(0, pos - before)
    e = min(len(js), pos + after)
    snippet = js[s:e].replace("\n", "↵").replace("\r", "")
    if s > 0:
        snippet = "…" + snippet
    if e < len(js):
        snippet = snippet + "…"
    return snippet

def extract_balanced(js, start):
    """Extract bracket-balanced content starting at start (must be [, {, or ()."""
    opens = {'[': ']', '{': '}', '(': ')'}
    ch = js[start]
    if ch not in opens:
        return None
    close = opens[ch]
    depth = 0
    i = start
    in_str = None
    escape = False
    while i < len(js):
        c = js[i]
        if escape:
            escape = False
        elif c == '\\' and in_str:
            escape = True
        elif in_str:
            if c == in_str:
                in_str = None
        elif c in ('"', "'", '`'):
            in_str = c
        elif c == ch:
            depth += 1
        elif c == close:
            depth -= 1
            if depth == 0:
                return js[start:i+1]
        i += 1
    return None

# ─── Phase 1: Show what's near "n" in the JS ──────────────────────────────────

def phase1_survey(js):
    sep("PHASE 1 — Survey: what .set/.get(\"n\") patterns exist?")

    hits = list(re.finditer(r'\.(?:set|get)\s*\(\s*["\']n["\']', js))
    print(f"Found {len(hits)} occurrences of .set/.get(\"n\") total")

    print()
    print("All occurrences with context (±80 chars):")
    for i, m in enumerate(hits, 1):
        print(f"\n  [{i}] pos={m.start()}")
        print(f"      {ctx(js, m.start(), 60, 100)}")
        if i >= 20:
            print(f"  ... ({len(hits)-20} more, showing first 20 only)")
            break

# ─── Phase 2: Strategy 1 — literal .set("n", FN(...)) ────────────────────────

S1_PATTERNS = [
    # (regex, label, captures_via_property)
    (r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]+)\[0\]\(',  "get-n&&arr[0](",    False),
    (r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]+)\(',       "get-n&&fn(",         False),
    (r'\.set\("n",([a-zA-Z0-9$]+)\[0\]\(',                          'set-n,arr[0](',      False),
    (r'\.set\("n",([a-zA-Z0-9$]+)\(',                               'set-n,fn(',          False),
    (r'\.set\s*\(\s*"n"\s*,\s*([a-zA-Z0-9$]+)\[0\]\(',             'set-n (spaces)',     False),
    (r'\.set\s*\(\s*"n"\s*,\s*([a-zA-Z0-9$]+)\(',                  'set-n (spaces,fn)',  False),
    (r'\.set\("n",\w+\.([\w$]+)\[0\]\(',                            'set-n,obj.prop[0](', True),
    (r'\.set\("n",\w+\.([\w$]+)\(',                                 'set-n,obj.prop(',    True),
    (r"\.set\('n',([a-zA-Z0-9$]+)\[0\]\(",                          "set-'n',arr[0](",   False),
    (r"\.set\('n',([a-zA-Z0-9$]+)\(",                               "set-'n',fn(",        False),
    (r"\.set\('n',\w+\.([\w$]+)\[0\]\(",                            "set-'n',obj.prop[0](", True),
    (r"\.set\('n',\w+\.([\w$]+)\(",                                 "set-'n',obj.prop(",  True),
]

def strategy1(js):
    sep("PHASE 2 — Strategy 1: literal n call site")

    # Test all patterns
    arr_name = None
    matched_label = None
    for pat, label, is_prop in S1_PATTERNS:
        m = re.search(pat, js)
        if m:
            name = m.group(1)
            print(f"  ✓ Pattern '{label}' matched: arrName='{name}'")
            print(f"    context: {ctx(js, m.start(), 50, 90)}")
            if arr_name is None:
                arr_name = name
                matched_label = label
        else:
            print(f"  ✗ Pattern '{label}': no match")

    if arr_name is None:
        print("\n[S1] FAIL — no call site found by any pattern")
        return None

    print(f"\n[S1] Using arrName='{arr_name}' from '{matched_label}'")

    # Search for declaration
    print(f"\n  Searching for declaration of '{arr_name}':")

    # Array form
    for prefix in [f"var {arr_name}=[", f"const {arr_name}=[", f"let {arr_name}=[",
                   f",{arr_name}=[", f";{arr_name}=["]:
        idx = js.find(prefix)
        if idx >= 0:
            fn = extract_balanced(js, idx + len(prefix) - 1)
            if fn:
                print(f"  ✓ Found array declaration '{prefix}' at pos={idx}, {len(fn)}b")
                print(f"    first 120 chars: {fn[:120]}")
                return fn
            else:
                print(f"  ~ Found '{prefix}' at pos={idx} but extract_balanced failed")

    # Function form
    for prefix in [f"var {arr_name}=function(", f"const {arr_name}=function(",
                   f"let {arr_name}=function(", f";{arr_name}=function(",
                   f",{arr_name}=function("]:
        idx = js.find(prefix)
        if idx >= 0:
            kw = idx + prefix.index("function(")
            ps = kw + len("function(")
            pe = js.find(")", ps)
            bi = js.find("{", pe) if pe >= 0 else -1
            body = extract_balanced(js, bi) if bi >= 0 else None
            if body:
                params = js[ps:pe] if pe >= 0 else "?"
                iife = f"(function(){{var {arr_name}=function({params}){body};return [{arr_name}]}})()"
                print(f"  ✓ Found function declaration '{prefix}' at pos={idx}")
                print(f"    params=({params}), body={len(body)}b, iife={len(iife)}b")
                return iife
            else:
                print(f"  ~ Found '{prefix}' at pos={idx} but body extraction failed")

    # Show all assignments to this name
    print(f"\n  No declaration found. All assignments to '{arr_name}':")
    for m in re.finditer(r'(?<![.\w])' + re.escape(arr_name) + r'\s*=', js):
        print(f"    pos={m.start()}: {ctx(js, m.start(), 5, 100)}")

    print(f"\n[S1] FAIL — arrName='{arr_name}' found but no declaration")
    return None

# ─── Phase 3: Strategy 2 — dispatcher-based ───────────────────────────────────

def strategy2(js):
    sep("PHASE 3 — Strategy 2: dispatcher-based (2026+ players)")

    # Find sig call site (helps identify dispatcher function name)
    sig_patterns = [
        (r'(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)',
         "S2a: DISP(K,R, DISP(K2,R2, X.s)) — same dispatcher nested"),
        (r'(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\w+\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)',
         "S2b: DISP(K,R, DISP2(K2,R2, X.s)) — different dispatchers"),
        (r'(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\w+\.s\s*\)',
         "S2c: DISP(K,R, X.s) — single call"),
    ]

    sig_match = None
    for pat, label in sig_patterns:
        m = re.search(pat, js)
        if m:
            sig_match = (m.group(1), int(m.group(2)), int(m.group(3)), label)
            print(f"  ✓ Sig call pattern: {label}")
            print(f"    dispatcher='{m.group(1)}' K={m.group(2)} R={m.group(3)}")
            print(f"    context: {ctx(js, m.start(), 40, 80)}")
            break
        else:
            print(f"  ✗ {label}: no match")

    if sig_match is None:
        print("\n[S2] FAIL — sig call site not found")
        print("\n  Dispatcher-like calls in JS (fn(int,int,...)):")
        seen = set()
        count = 0
        for m in re.finditer(r'(\b[a-zA-Z_$]\w*)\(\s*\d+\s*,\s*\d+\s*,', js):
            fn = m.group(1)
            if fn in ('function','if','for','while','switch','return','var','let','const','new','typeof'):
                continue
            if fn not in seen:
                seen.add(fn)
                print(f"    {fn}( at pos={m.start()}: {ctx(js, m.start(), 0, 80)}")
                count += 1
                if count >= 15:
                    print("    ... (showing first 15 only)")
                    break
        return None

    disp_name, sig_k, sig_r, _ = sig_match

    # Find n-decode call
    print(f"\n  Looking for n-decode call: {disp_name}(K,R, {disp_name}(K2,R2, x))")
    print(f"  (excluding sig call K={sig_k},R={sig_r})")
    n_pat = re.compile(
        r'\b' + re.escape(disp_name) +
        r'\(\s*(\d+)\s*,\s*(\d+)\s*,\s*' + re.escape(disp_name) +
        r'\(\s*(\d+)\s*,\s*(\d+)\s*,\s*[\w$]+\s*\)\s*\)')
    n_cm = None
    all_calls = list(n_pat.finditer(js))
    print(f"  Found {len(all_calls)} nested {disp_name}(...,{disp_name}(...)) calls:")
    for m in all_calls:
        k, r = int(m.group(1)), int(m.group(2))
        is_sig = (k == sig_k and r == sig_r)
        mark = " ← SIG (skip)" if is_sig else " ← USE THIS"
        print(f"    {disp_name}({k},{r},{disp_name}({m.group(3)},{m.group(4)},x)){mark}")
        if not is_sig and n_cm is None:
            n_cm = m

    if n_cm is None:
        print(f"\n[S2] FAIL — n-decode call not found for dispatcher={disp_name}")
        print(f"\n  All calls to {disp_name}(:")
        for m in re.finditer(r'\b' + re.escape(disp_name) + r'\(', js):
            print(f"    pos={m.start()}: {ctx(js, m.start(), 0, 90)}")
        return None

    ok, or_ = n_cm.group(1), n_cm.group(2)
    ik, ir  = n_cm.group(3), n_cm.group(4)
    print(f"\n  ✓ n-decode call: {disp_name}({ok},{or_},{disp_name}({ik},{ir},x))")

    # Dispatcher function body
    disp_fn_idx = js.rfind(f"{disp_name}=function(", 0, n_cm.start())
    if disp_fn_idx < 0:
        disp_fn_idx = js.find(f"{disp_name}=function(")
    if disp_fn_idx < 0:
        print(f"\n[S2] FAIL — dispatcher function definition not found")
        return None
    pm = re.search(re.escape(disp_name) + r'=function\(([^)]*)\)', js[disp_fn_idx:disp_fn_idx+200])
    disp_params = pm.group(1).strip() if pm else "K,R,x"
    bi = js.find("{", disp_fn_idx)
    disp_body = extract_balanced(js, bi)
    if not disp_body:
        print(f"\n[S2] FAIL — dispatcher body extraction failed")
        return None
    print(f"  ✓ Dispatcher body: {len(disp_body)}b, params=({disp_params})")

    # U-table
    table_var = table_code = None
    print("\n  Searching for u-table (split string with split/join/reverse/splice):")
    for sep_char in ["{", ";", "|", ",", "."]:
        pat = re.compile(
            r'(?<![.\w])(\w+)\s*=\s*"([^"]{200,})"\.split\s*\(\s*"' +
            re.escape(sep_char) + r'"\s*\)')
        for tm in pat.finditer(js):
            entries = tm.group(2).split(sep_char)
            has_methods = all(k in entries for k in ["split", "join", "reverse", "splice"])
            print(f"    candidate '{tm.group(1)}' sep='{sep_char}' entries={len(entries)} has_methods={has_methods}")
            if has_methods:
                table_var = tm.group(1)
                table_code = f'var {table_var}="{tm.group(2)}".split("{sep_char}");'
                print(f"    ✓ Using this as u-table")
                break
        if table_var:
            break
    if not table_var:
        print("  ✗ u-table not found (dispatcher may fail at runtime)")

    # Helper object
    helper_code = None
    if table_var:
        hm = re.search(r'([\w$]+)\[' + re.escape(table_var) + r'\[', disp_body)
        h_name = hm.group(1) if hm and hm.group(1) != disp_name and len(hm.group(1)) <= 10 else None
        if h_name:
            print(f"\n  Looking for helper object '{h_name}':")
            for search in [f"var {h_name}=", f";{h_name}=", f",{h_name}="]:
                idx = js.rfind(search, 0, disp_fn_idx)
                if idx < 0:
                    idx = js.find(search)
                if idx >= 0:
                    b2 = js.find("{", idx)
                    body = extract_balanced(js, b2) if b2 >= 0 else None
                    if body:
                        helper_code = f"var {h_name}={body};"
                        print(f"  ✓ Helper '{h_name}': {len(body)}b")
                        break
            if not helper_code:
                print(f"  ✗ Helper '{h_name}' body not found")
        else:
            print(f"\n  No helper name found in dispatcher body")

    # Build IIFE
    deps = "\n".join(filter(None, [table_code, helper_code,
                                   f"function {disp_name}({disp_params}){disp_body}"]))
    iife = (f"(function(){{{deps}\n"
            f"return [function(x){{return {disp_name}({ok},{or_},"
            f"{disp_name}({ik},{ir},x))}}]}})()")
    print(f"\n  ✓ IIFE synthesized: {len(iife)}b")
    return iife

# ─── Phase 4: Test the extracted IIFE ─────────────────────────────────────────

def test_iife(iife):
    sep("PHASE 4 — Runtime test (requires node)")
    if not shutil.which("node"):
        print("[!] 'node' not found in PATH — skipping runtime test")
        print("    Install Node.js to verify the IIFE actually decodes n")
        return

    # Use a realistic-looking n-value
    test_values = [
        "dQw4w9WgXcQ_abc123",
        "abcdefghijklmnop",
        "AAAAAAAAAAAAAAAA",
    ]
    for test_n in test_values:
        script = textwrap.dedent(f"""
            try {{
                var fn = {iife};
                var callable = (fn instanceof Array) ? fn[0] : fn;
                var result = callable('{test_n}');
                console.log('input :', '{test_n}');
                console.log('output:', result);
                console.log('changed:', result !== '{test_n}');
            }} catch(e) {{
                console.log('ERROR:', e.message);
                console.log(e.stack && e.stack.split('\\n')[1]);
            }}
        """)
        r = subprocess.run(["node", "-e", script], capture_output=True, text=True, timeout=10)
        print(r.stdout.strip())
        if r.stderr:
            print("stderr:", r.stderr.strip()[:300])
        print()

# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else None
    try:
        js, source = load_js(arg)
    except Exception as e:
        print(f"[FATAL] Could not load player JS: {e}")
        sys.exit(1)

    print(f"Source : {source}")
    print(f"Size   : {len(js):,} bytes")

    # Phase 1: survey what's there
    phase1_survey(js)

    # Phase 2: Strategy 1
    iife = strategy1(js)

    # Phase 3: Strategy 2 (only if S1 failed)
    if iife is None:
        iife = strategy2(js)

    # Phase 4: runtime test
    sep("SUMMARY")
    if iife:
        print(f"✓ Extraction SUCCESS ({len(iife)}b)")
        print(f"  First 200 chars: {iife[:200]}")
        test_iife(iife)
    else:
        print("✗ Extraction FAILED — both strategies returned nothing")
        print("  See Phase 1 output above for actual .set(\"n\") patterns in this JS.")
        print("  Paste this output to Claude to design the right extraction pattern.")

if __name__ == "__main__":
    main()
