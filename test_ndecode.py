#!/usr/bin/env python3
"""
Test n-decode extraction from YouTube player JS.
Mirrors the Kotlin extractNDecodeFn() logic in PoTokenWebView.kt
"""
import re
import sys
import urllib.request

KNOWN_PLAYER_ID = "5cabb421"

# ── Get player JS ──────────────────────────────────────────────────────────
def get_player_url_from_homepage():
    req = urllib.request.Request(
        "https://www.youtube.com/",
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36"}
    )
    html = urllib.request.urlopen(req, timeout=15).read().decode("utf-8", errors="replace")
    m = re.search(r'"jsUrl":\s*"(/s/player/[^"]+\.js)"', html)
    if m:
        return "https://www.youtube.com" + m.group(1)
    m = re.search(r'/s/player/([a-f0-9]{8})/', html)
    if m:
        pid = m.group(1)
        return f"https://www.youtube.com/s/player/{pid}/player_ias.vflset/en_US/base.js"
    raise RuntimeError("Cannot find player URL in YouTube homepage")

def make_player_url(player_id):
    return f"https://www.youtube.com/s/player/{player_id}/player_ias.vflset/en_US/base.js"

def get_player_js(url):
    print(f"[*] Downloading: {url}")
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36",
        "Referer": "https://www.youtube.com/",
    })
    return urllib.request.urlopen(req, timeout=30).read().decode("utf-8", errors="replace")

# ── extractBalanced ────────────────────────────────────────────────────────
def extract_balanced(js, start):
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

# ── Strategy 1 ────────────────────────────────────────────────────────────
def strategy1(js):
    patterns = [
        (r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]+)\[0\]\(', "S1a"),
        (r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]+)\(', "S1b"),
        (r'\.set\("n",([a-zA-Z0-9$]+)\[0\]\(', "S1c"),
        (r'\.set\("n",([a-zA-Z0-9$]+)\(', "S1d"),
        (r'\.set\s*\(\s*"n"\s*,\s*([a-zA-Z0-9$]+)\[0\]\(', "S1e"),
        (r'\.set\s*\(\s*"n"\s*,\s*([a-zA-Z0-9$]+)\(', "S1f"),
        (r'\.set\("n",\w+\.([\w$]+)\[0\]\(', "S1g-prop"),
        (r'\.set\("n",\w+\.([\w$]+)\(', "S1h-prop"),
        (r"\.set\('n',([a-zA-Z0-9$]+)\[0\]\(", "S1i-sq"),
        (r"\.set\('n',([a-zA-Z0-9$]+)\(", "S1j-sq"),
    ]
    arr_name = None
    for pat, name in patterns:
        m = re.search(pat, js)
        if m:
            arr_name = m.group(1)
            ctx = js[max(0, m.start()-40):m.end()+40]
            print(f"[S1] call site matched ({name}): arrName='{arr_name}'")
            print(f"     context: {repr(ctx)}")
            break

    if arr_name is None:
        print("[S1] FAIL: no call site found")
        for m in re.finditer(r'\.set\(."n"', js):
            print(f"     .set(.n. at {m.start()}: {repr(js[max(0,m.start()-20):m.end()+60])}")
        return None

    # Array declaration
    for prefix in [f"var {arr_name}=[", f"const {arr_name}=[", f"let {arr_name}=[",
                   f",{arr_name}=[", f";{arr_name}=["]:
        idx = js.find(prefix)
        if idx >= 0:
            fn = extract_balanced(js, idx + len(prefix) - 1)
            if fn:
                print(f"[S1-arr] FOUND '{prefix}': {len(fn)}b")
                return fn

    # Function definition
    for prefix in [f"var {arr_name}=function(", f"const {arr_name}=function(",
                   f"let {arr_name}=function(", f";{arr_name}=function(",
                   f",{arr_name}=function("]:
        idx = js.find(prefix)
        if idx >= 0:
            kw = idx + prefix.index("function(")
            ps = kw + len("function(")
            pe = js.find(")", ps)
            bi = js.find("{", pe) if pe >= 0 else -1
            if bi >= 0:
                body = extract_balanced(js, bi)
                if body:
                    params = js[ps:pe]
                    iife = f"(function(){{var {arr_name}=function({params}){body};return [{arr_name}]}})()"
                    print(f"[S1-fn] FOUND '{prefix}': params=({params}), {len(iife)}b")
                    return iife

    print(f"[S1] FAIL: arrName='{arr_name}' found but no declaration")
    for m in re.finditer(re.escape(arr_name) + r'\s*=', js):
        ctx = js[max(0, m.start()-10):min(len(js), m.end()+80)]
        print(f"     {arr_name}= at {m.start()}: {repr(ctx)}")
    return None

# ── Strategy 2 ────────────────────────────────────────────────────────────
def strategy2(js):
    sig_patterns = [
        (r'(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)', "S2a-same-nested"),
        (r'(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\w+\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)', "S2b-diff-nested"),
        (r'(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\w+\.s\s*\)', "S2c-single"),
    ]
    sig_match = None
    for pat, name in sig_patterns:
        m = re.search(pat, js)
        if m:
            disp_name = m.group(1)
            sig_k = int(m.group(2)); sig_r = int(m.group(3))
            ctx = js[max(0, m.start()-20):m.end()+20]
            print(f"[S2] sig call ({name}): {disp_name}({sig_k},{sig_r},...)")
            print(f"     context: {repr(ctx)}")
            sig_match = (disp_name, sig_k, sig_r)
            break

    if sig_match is None:
        print("[S2] FAIL: sig call site not found")
        # Dump dispatcher-like calls for debugging
        seen = set()
        for m in re.finditer(r'(\b\w+)\(\s*\d+\s*,\s*\d+\s*,', js):
            fn = m.group(1)
            if fn not in ('function','if','for','while','switch','return','var','let','const') and fn not in seen:
                seen.add(fn)
                ctx = js[m.start():min(len(js), m.start()+80)]
                print(f"     dispatcher-like: {repr(ctx)}")
        return None

    disp_name, sig_k, sig_r = sig_match

    n_pat = re.compile(r'\b' + re.escape(disp_name) +
        r'\(\s*(\d+)\s*,\s*(\d+)\s*,\s*' + re.escape(disp_name) +
        r'\(\s*(\d+)\s*,\s*(\d+)\s*,\s*[\w$]+\s*\)\s*\)')
    n_cm = None
    for m in n_pat.finditer(js):
        if not (int(m.group(1)) == sig_k and int(m.group(2)) == sig_r):
            n_cm = m; break

    if n_cm is None:
        print(f"[S2] FAIL: n-decode call not found for dispatcher={disp_name}")
        for m in re.finditer(r'\b' + re.escape(disp_name) + r'\(', js):
            print(f"     {disp_name}( at {m.start()}: {repr(js[m.start():m.start()+80])}")
        return None

    ok, or_, ik, ir = n_cm.group(1), n_cm.group(2), n_cm.group(3), n_cm.group(4)
    print(f"[S2] n-decode call: {disp_name}({ok},{or_},{disp_name}({ik},{ir},x))")

    disp_fn_idx = js.rfind(f"{disp_name}=function(", 0, n_cm.start())
    if disp_fn_idx < 0: disp_fn_idx = js.find(f"{disp_name}=function(")
    if disp_fn_idx < 0:
        print(f"[S2] FAIL: dispatcher fn def not found"); return None
    pm = re.search(re.escape(disp_name) + r'=function\(([^)]*)\)', js[disp_fn_idx:disp_fn_idx+200])
    disp_params = pm.group(1).strip() if pm else "K,R,x"
    bi = js.find("{", disp_fn_idx)
    disp_body = extract_balanced(js, bi)
    if not disp_body:
        print("[S2] FAIL: dispatcher body not found"); return None
    print(f"[S2] dispatcher: {disp_name}({disp_params}) body={len(disp_body)}b")

    table_var = table_code = None
    for sep in ["{", ";", "|"]:
        pat = re.compile(r'(?<![.\w])(\w+)\s*=\s*"([^"]{300,})"\.split\s*\(\s*"' + re.escape(sep) + r'"\s*\)')
        for tm in pat.finditer(js):
            entries = tm.group(2).split(sep)
            if all(k in entries for k in ["split", "join", "reverse", "splice"]):
                table_var = tm.group(1)
                table_code = f'var {table_var}="{tm.group(2)}".split("{sep}");'
                print(f"[S2] u-table: {table_var} ({len(entries)} entries)")
                break
        if table_var: break
    if not table_var:
        print("[S2] WARNING: u-table not found")

    helper_code = None
    if table_var:
        hm = re.search(r'([\w$]+)\[' + re.escape(table_var) + r'\[', disp_body)
        h_name = hm.group(1) if hm and hm.group(1) != disp_name and len(hm.group(1)) <= 10 else None
        if h_name:
            for search in [f"var {h_name}=", f";{h_name}=", f",{h_name}="]:
                idx = js.rfind(search, 0, disp_fn_idx)
                if idx < 0: idx = js.find(search)
                if idx >= 0:
                    b2 = js.find("{", idx)
                    body = extract_balanced(js, b2) if b2 >= 0 else None
                    if body:
                        helper_code = f"var {h_name}={body};"
                        print(f"[S2] helper: {h_name} body={len(body)}b")
                        break

    deps = "\n".join(filter(None, [table_code, helper_code,
                                    f"function {disp_name}({disp_params}){disp_body}"]))
    iife = (f"(function(){{{deps}\nreturn [function(x){{return "
            f"{disp_name}({ok},{or_},{disp_name}({ik},{ir},x))}}]}})()")
    print(f"[S2] IIFE synthesized: {len(iife)}b")
    return iife

# ── Test with Node.js ──────────────────────────────────────────────────────
def test_iife(iife):
    import subprocess, shutil
    if not shutil.which("node"):
        print("[test] node not available")
        return
    test_n = "dQw4w9WgXcQ_abc123"
    script = f"""
try {{
  var fn = {iife};
  var result = (fn instanceof Array) ? fn[0]('{test_n}') : fn('{test_n}');
  console.log('result:', result, '| changed:', result !== '{test_n}');
}} catch(e) {{ console.log('ERROR:', e.message); }}
"""
    try:
        r = subprocess.run(["node", "-e", script], capture_output=True, text=True, timeout=10)
        print(f"[test] {r.stdout.strip()}")
        if r.stderr: print(f"[test] stderr: {r.stderr.strip()[:200]}")
    except Exception as e:
        print(f"[test] {e}")

# ── Main ───────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    if len(sys.argv) > 1:
        print(f"[*] Using local file: {sys.argv[1]}")
        with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
            js = f.read()
    else:
        try:
            url = make_player_url(KNOWN_PLAYER_ID)
            js = get_player_js(url)
        except Exception as e:
            print(f"[*] Known player ID failed ({e}), trying homepage...")
            try:
                url = get_player_url_from_homepage()
                js = get_player_js(url)
            except Exception as e2:
                print(f"[FAIL] Cannot download player JS: {e2}")
                sys.exit(1)

    print(f"[*] Player JS: {len(js)} bytes")
    print()

    print("=== Strategy 1 ===")
    iife = strategy1(js)
    if iife:
        print(f"\n[S1] SUCCESS ({len(iife)}b)")
        test_iife(iife)
    else:
        print()
        print("=== Strategy 2 ===")
        iife = strategy2(js)
        if iife:
            print(f"\n[S2] SUCCESS ({len(iife)}b)")
            test_iife(iife)
        else:
            print("\n[FAIL] Both strategies failed")
