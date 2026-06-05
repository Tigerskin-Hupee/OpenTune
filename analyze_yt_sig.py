#!/usr/bin/env python3
"""
YouTube player JS sig-decode structure analyzer.
Fetches the current player JS and reports which function-definition format
the signature decoder uses (= assignment, colon property, arrow, etc.).

Run:  py analyze_yt_sig.py   (Windows)
      python3 analyze_yt_sig.py  (Mac/Linux)
Requires Python 3.7+, no third-party packages needed.
"""
import re, sys, urllib.request, urllib.error

UA_DESKTOP = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/125.0.0.0 Safari/537.36"
)
UA_MOBILE = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/125.0.0.0 Mobile Safari/537.36"
)

def fetch(url, ua=UA_DESKTOP):
    req = urllib.request.Request(url, headers={
        "User-Agent": ua,
        "Accept-Language": "en-US,en;q=0.9",
        "Accept": "text/html,application/xhtml+xml,*/*",
    })
    try:
        return urllib.request.urlopen(req, timeout=20).read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code} for {url}")
        return ""
    except Exception as e:
        print(f"  Error fetching {url}: {e}")
        return ""

PLAYER_PAT = re.compile(
    r'(?:https://www\.youtube\.com)?'
    r'(/s/player/[a-f0-9]+/player_ias\.vflset/[^/"\'\\]+/base\.js)'
)

def find_player_url(html):
    m = PLAYER_PAT.search(html)
    return ("https://www.youtube.com" + m.group(1)) if m else None

# ── 1. Find player JS URL ─────────────────────────────────────────────────────
player_url = None
attempts = [
    ("https://www.youtube.com/", UA_DESKTOP),
    ("https://www.youtube.com/watch?v=dQw4w9WgXcQ", UA_DESKTOP),
    ("https://www.youtube.com/watch?v=dQw4w9WgXcQ", UA_MOBILE),
    ("https://m.youtube.com/watch?v=dQw4w9WgXcQ", UA_MOBILE),
]
for url, ua in attempts:
    print(f"Trying {url} …")
    html = fetch(url, ua)
    player_url = find_player_url(html)
    if player_url:
        print(f"Found player URL: {player_url}")
        break
    # also search ytInitialPlayerResponse / ytcfg
    m2 = re.search(r'"PLAYER_JS_URL"\s*:\s*"(/s/player/[^"]+base\.js)"', html)
    if m2:
        player_url = "https://www.youtube.com" + m2.group(1)
        print(f"Found player URL (ytcfg): {player_url}")
        break

if not player_url:
    print("\nCould not auto-detect player JS URL.")
    print("Please open https://www.youtube.com/watch?v=dQw4w9WgXcQ in Chrome,")
    print('open DevTools → Network tab → filter "base.js" → copy the URL here.')
    player_url = input("Paste player JS URL: ").strip()
    if not player_url:
        sys.exit(1)

# ── 2. Fetch player JS ────────────────────────────────────────────────────────
print("\nFetching player JS (may take a few seconds) …")
js = fetch(player_url)
if len(js) < 10000:
    print(f"ERROR: player JS too short ({len(js)} bytes) — fetch failed.")
    sys.exit(1)
print(f"Player JS size: {len(js):,} bytes\n")

# ── 3. Function-definition patterns ──────────────────────────────────────────
FN_PATTERNS = [
    (r'([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{',       "style1: NAME=function(a){"),
    (r'function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{',        "style2: function NAME(a){"),
    (r'([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',          "style3: NAME=(a)=>{"),
    (r'([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{',                    "style4: NAME=a=>{"),
    (r'([\w$]+)\s*:\s*function\s*\(\s*([\w$]+)\s*\)\s*\{',    "style5: NAME:function(a){  [COLON]"),
    (r'([\w$]+)\s*:\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',          "style6: NAME:(a)=>{        [COLON]"),
    (r'([\w$]+)\s*:\s*([\w$]+)\s*=>\s*\{',                    "style7: NAME:a=>{          [COLON]"),
]

split_tokens = ['.split("")', ".split('')", "Array.from(", "[..."]
join_tokens  = ['.join("")',  ".join('')"]

print("=" * 70)
print("PART 1: Sig-decode function definition style")
print("=" * 70)

hits = []
join_positions = [m.start() for m in re.finditer(r'\.join\(""\)', js)]
print(f"Total .join(\"\") occurrences: {len(join_positions)}")

for joinIdx in join_positions:
    seg = js[max(0, joinIdx - 10000): joinIdx]
    if not any(t in seg for t in split_tokens):
        continue
    matched_style = fn_name = fn_param = ""
    ctx = js[max(0, joinIdx - 400): joinIdx + 30]
    for pat, desc in FN_PATTERNS:
        all_m = list(re.finditer(pat, seg))
        if all_m:
            last = all_m[-1]
            matched_style = desc
            fn_name = last.group(1)
            fn_param = last.group(2)
            break
    hits.append((joinIdx, matched_style or "NO_MATCH", fn_name, fn_param, ctx))

print(f"Occurrences with split+join (candidate sig fns): {len(hits)}\n")

style_counts = {}
for _, style, _, _, _ in hits:
    style_counts[style] = style_counts.get(style, 0) + 1
print("Summary of function-definition styles found near .join(\"\"):")
for k, v in sorted(style_counts.items(), key=lambda x: -x[1]):
    marker = " <<<" if "COLON" in k else ("  *** BUG" if k == "NO_MATCH" else "")
    print(f"  {v:3d}x  {k}{marker}")

no_match = [(j, s, fn, p, c) for (j, s, fn, p, c) in hits if s == "NO_MATCH"]
if no_match:
    print(f"\nUnmatched joins ({len(no_match)}) — showing first 2:")
    for joinIdx, _, _, _, ctx in no_match[:2]:
        print(f"\n  join at offset {joinIdx}")
        lines = ctx.replace(";", ";\n").split("\n")
        for ln in lines[-10:]:
            print(f"    {ln[:120]}")

# ── 4. Call-site patterns ─────────────────────────────────────────────────────
print("\n" + "=" * 70)
print("PART 2: How the sig function is called (call-site patterns)")
print("=" * 70)
cs_patterns = [
    (r'\b[\w$]+&&\([\w$]+=([a-zA-Z0-9_$]+)\((?:\d+,)?decodeURIComponent',  "&&(X=FN(decodeURI...)"),
    (r'\bc&&\(c=([a-zA-Z0-9$]+)\(decodeURIComponent',                        "c&&(c=FN(decodeURI...)"),
    (r'[;,=]\s*([a-zA-Z0-9$]+)\(decodeURIComponent\([^)]+\.get\("s"\)',      "X=FN(decodeURI(x.get('s')...)"),
    (r'\.set\(["\']sig["\'],([a-zA-Z0-9$]+)\(',                              ".set('sig',FN(...)"),
    (r'b=([a-zA-Z0-9$]+)\(decodeURIComponent\(b\.get\("s"\)\)\)',            "b=FN(decodeURI(b.get('s')))"),
    (r'\b[\w$]+&&\([\w$]+=([\w$]+\.[\w$]+)\((?:\d+,)?decodeURIComponent',   "&&(X=OBJ.METHOD(decodeURI...) [COLON CALL]"),
    (r'c&&\(c=([\w$]+\.[\w$]+)\(decodeURIComponent',                         "c&&(c=OBJ.METHOD(decodeURI...) [COLON CALL]"),
    (r'[;({,\s=]([a-zA-Z0-9$]{2,})\s*\(\s*decodeURIComponent\(',            "generic FN(decodeURI(...)"),
]
found_any = False
for pat, desc in cs_patterns:
    mm = re.search(pat, js)
    if mm:
        ctx = js[max(0, mm.start() - 40): mm.end() + 100]
        print(f"  FOUND: {desc}")
        print(f"    captured name: {mm.group(1)}")
        print(f"    context: ...{repr(ctx[-160:])}...")
        print()
        found_any = True
if not found_any:
    print("  No call-site patterns matched!")

# ── 5. Helper object method styles ───────────────────────────────────────────
print("=" * 70)
print("PART 3: Helper object method styles (near .splice and .reverse)")
print("=" * 70)
for anchor, label in [(".splice(0,", "splice"), (".reverse()", "reverse")]:
    positions = [m.start() for m in re.finditer(re.escape(anchor), js)]
    print(f"\n{label} occurrences: {len(positions)} — checking first 5:")
    for idx in positions[:5]:
        chunk = js[max(0, idx - 300): idx + 20]
        for pat, desc in FN_PATTERNS:
            mm_list = list(re.finditer(pat, chunk))
            if mm_list:
                last_mm = mm_list[-1]
                snippet = chunk[max(0, last_mm.start() - 5): last_mm.end() + 60]
                print(f"  offset {idx}: {desc}")
                print(f"    {repr(snippet)}")
                break

print("\nDone.")
