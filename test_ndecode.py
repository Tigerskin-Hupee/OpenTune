#!/usr/bin/env python3
"""
Test n-decode extraction from YouTube player JS.
Mirrors the Kotlin extractNDecodeFn() logic in PoTokenWebView.kt
"""
import re
import sys
import json
import urllib.request

# ── Step 1: Get player URL from YouTube homepage ───────────────────────────
def get_player_url():
    req = urllib.request.Request(
        "https://www.youtube.com/",
        headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0 Safari/537.36"}
    )
    html = urllib.request.urlopen(req, timeout=15).read().decode("utf-8", errors="replace")
    m = re.search(r'"jsUrl":\s*"(/s/player/[^"]+\.js)"', html)
    if not m:
        m = re.search(r'player\\?/([a-f0-9]{8})/player_ias', html)
        if m:
            return f"https://www.youtube.com/s/player/{m.group(1)}/player_ias.vflset/en_US/base.js"
        raise RuntimeError("Cannot find player URL in YouTube homepage")
    return "https://www.youtube.com" + m.group(1)

# ── Step 2: Download player JS ─────────────────────────────────────────────
def get_player_js(url):
    print(f"[*] Downloading: {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    return urllib.request.urlopen(req, timeout=30).read().decode("utf-8", errors="replace")

# ── Step 3: extractBalanced ────────────────────────────────────────────────
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

# ── Step 4: Strategy 1 ────────────────────────────────────────────────────
def strategy1(js):
    patterns = [
        r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]+)\[0\]\(',
        r'\.get\("n"\)\)&&\([a-zA-Z0-9$._]+=([a-zA-Z0-9$]+)\(',
        r'\.set\("n",([a-zA-Z0-9$]+)\[0\]\(',
        r'\.set\("n",([a-zA-Z0-9$]+)\(',
        r'\.set\s*\(\s*"n"\s*,\s*([a-zA-Z0-9$]+)\[0\]\(',
        r'\.set\s*\(\s*"n"\s*,\s*([a-zA-Z0-9$]+)\(',
    ]
    arr_name = None
    for pat in patterns:
        m = re.search(pat, js)
        if m:
            arr_name = m.group(1)
            print(f"[S1] call site matched: {pat[:40]}... → arrName='{arr_name}'")
            print(f"     context: ...{js[max(0,m.start()-30):m.end()+30]}...")
            break

    if arr_name is None:
        print("[S1] FAIL: no call site found")
        return None

    # Search array declaration
    for prefix in [f"var {arr_name}=[", f"const {arr_name}=[", f"let {arr_name}=[",
                   f",{arr_name}=[", f";{arr_name}=["]:
        idx = js.find(prefix)
        if idx >= 0:
            bracket_idx = idx + len(prefix) - 1
            fn = extract_balanced(js, bracket_idx)
            if fn:
                print(f"[S1-arr] FOUND array declaration: '{prefix}' → {len(fn)}b")
                return fn

    # Search function definition
    for prefix in [f"var {arr_name}=function(", f"const {arr_name}=function(",
                   f"let {arr_name}=function(", f";{arr_name}=function(",
                   f",{arr_name}=function("]:
        idx = js.find(prefix)
        if idx >= 0:
            fn_kw_pos = idx + prefix.index("function(")
            param_start = fn_kw_pos + len("function(")
            param_end = js.find(")", param_start)
            brace_idx = js.find("{", param_end) if param_end >= 0 else -1
            if brace_idx >= 0:
                body = extract_balanced(js, brace_idx)
                if body:
                    params = js[param_start:param_end]
                    iife = f"(function(){{var {arr_name}=function({params}){body};return [{arr_name}]}})()"
                    print(f"[S1-fn] FOUND function definition: '{prefix}' params=({params}) → {len(iife)}b")
                    return iife

    print(f"[S1] FAIL: arrName='{arr_name}' found but no declaration")
    return None

# ── Step 5: Strategy 2 ────────────────────────────────────────────────────
def strategy2(js):
    sig_pat = re.compile(r'(\w+)\(\s*(\d+)\s*,\s*(\d+)\s*,\s*\1\(\s*\d+\s*,\s*\d+\s*,\s*\w+\.s\s*\)\s*\)')
    sig_m = sig_pat.search(js)
    if not sig_m:
        print("[S2] FAIL: sig call site not found")
        return None
    disp_name = sig_m.group(1)
    sig_k = int(sig_m.group(2)); sig_r = int(sig_m.group(3))
    print(f"[S2] sig call: {disp_name}({sig_k},{sig_r},{disp_name}(...))")

    n_pat = re.compile(r'\b' + re.escape(disp_name) + r'\(\s*(\d+)\s*,\s*(\d+)\s*,\s*' + re.escape(disp_name) + r'\(\s*(\d+)\s*,\s*(\d+)\s*,\s*[\w$]+\s*\)\s*\)')
    n_cm = None
    for m in n_pat.finditer(js):
        k, r = int(m.group(1)), int(m.group(2))
        if not (k == sig_k and r == sig_r):
            n_cm = m; break
    if n_cm is None:
        print(f"[S2] FAIL: n-decode call site not found for dispatcher={disp_name}")
        return None
    print(f"[S2] n-decode call: {disp_name}({n_cm.group(1)},{n_cm.group(2)},{disp_name}({n_cm.group(3)},{n_cm.group(4)},x))")

    # Dispatcher function
    disp_fn_idx = js.rfind(f"{disp_name}=function(", 0, n_cm.start())
    if disp_fn_idx < 0:
        disp_fn_idx = js.find(f"{disp_name}=function(")
    if disp_fn_idx < 0:
        print(f"[S2] FAIL: dispatcher fn def not found")
        return None
    disp_params_m = re.search(re.escape(disp_name) + r'=function\(([^)]*)\)', js[disp_fn_idx:disp_fn_idx+200])
    disp_params = disp_params_m.group(1).strip() if disp_params_m else "K,R,x"
    brace_pos = js.find("{", disp_fn_idx)
    disp_body = extract_balanced(js, brace_pos)
    if not disp_body:
        print("[S2] FAIL: dispatcher body not found")
        return None
    print(f"[S2] dispatcher: {disp_name}({disp_params}) body={len(disp_body)}b")

    # U-table
    table_var = table_code = None
    for sep in ["{", ";", "|"]:
        pat = re.compile(r'(?<![.\w])(\w+)\s*=\s*"([^"]{300,})"\.split\s*\(\s*"' + re.escape(sep) + r'"\s*\)')
        for tm in pat.finditer(js):
            entries = tm.group(2).split(sep)
            if all(k in entries for k in ["split", "join", "reverse", "splice"]):
                table_var = tm.group(1)
                table_code = f'var {table_var}="{tm.group(2)}".split("{sep}");'
                print(f"[S2] u-table: {table_var} ({len(entries)} entries, sep='{sep}')")
                break
        if table_var:
            break
    if not table_var:
        print("[S2] WARNING: u-table not found")

    # Helper
    helper_code = None
    if table_var:
        hm = re.search(r'([\w$]+)\[' + re.escape(table_var) + r'\[', disp_body)
        h_name = hm.group(1) if hm and hm.group(1) != disp_name and len(hm.group(1)) <= 10 else None
        if h_name:
            for search in [f"var {h_name}=", f";{h_name}=", f",{h_name}="]:
                idx = js.rfind(search, 0, disp_fn_idx)
                if idx < 0:
                    idx = js.find(search)
                if idx >= 0:
                    brace = js.find("{", idx)
                    body = extract_balanced(js, brace) if brace >= 0 else None
                    if body:
                        helper_code = f"var {h_name}={body};"
                        print(f"[S2] helper: {h_name} body={len(body)}b")
                        break

    outer_k, outer_r = n_cm.group(1), n_cm.group(2)
    inner_k, inner_r = n_cm.group(3), n_cm.group(4)
    deps = "\n".join(filter(None, [table_code, helper_code, f"function {disp_name}({disp_params}){disp_body}"]))
    iife = f"(function(){{{deps}\nreturn [function(x){{return {disp_name}({outer_k},{outer_r},{disp_name}({inner_k},{inner_r},x))}}]}})()"
    print(f"[S2] IIFE synthesized: {len(iife)}b")
    return iife

# ── Step 6: Test with Node.js if available ─────────────────────────────────
def test_iife(iife, test_n="abc123"):
    import subprocess, shutil
    if not shutil.which("node"):
        print("[test] node not available, skipping runtime test")
        return
    script = f"""
var fn = {iife};
var result = (fn instanceof Array) ? fn[0]('{test_n}') : fn('{test_n}');
console.log('result:', result);
console.log('changed:', result !== '{test_n}');
"""
    try:
        r = subprocess.run(["node", "-e", script], capture_output=True, text=True, timeout=10)
        print(f"[test] node output: {r.stdout.strip()}")
        if r.stderr:
            print(f"[test] node stderr: {r.stderr.strip()[:300]}")
    except Exception as e:
        print(f"[test] node error: {e}")

# ── Main ───────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    if len(sys.argv) > 1:
        # Local file provided
        js_path = sys.argv[1]
        print(f"[*] Using local file: {js_path}")
        with open(js_path, encoding="utf-8", errors="replace") as f:
            js = f.read()
        player_url = js_path
    else:
        player_url = get_player_url()
        js = get_player_js(player_url)

    print(f"[*] Player JS: {len(js)} bytes")
    print()

    print("=== Strategy 1 ===")
    iife = strategy1(js)
    if iife:
        print(f"[S1] SUCCESS: {len(iife)}b — first 200 chars: {iife[:200]}")
        test_iife(iife)
    else:
        print("=== Strategy 2 ===")
        iife = strategy2(js)
        if iife:
            print(f"[S2] SUCCESS: {len(iife)}b")
            test_iife(iife)
        else:
            print("[FAIL] Both strategies failed")
