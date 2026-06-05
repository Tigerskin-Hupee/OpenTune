#!/usr/bin/env python3
"""
YouTube player JS sig-decode structure analyzer.
Fetches the current player JS and reports which function-definition format
the signature decoder uses (= assignment, colon property, arrow, etc.).

Run:  python3 analyze_yt_sig.py
Requires Python 3.7+, no third-party packages needed.
"""
import re, sys, urllib.request, json

UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

def fetch(url, extra_headers=None):
    headers = {"User-Agent": UA}
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, headers=headers)
    return urllib.request.urlopen(req, timeout=15).read().decode("utf-8", errors="replace")

# ── 1. Find player JS URL ────────────────────────────────────────────────────
print("Fetching youtube.com …")
html = fetch("https://www.youtube.com/")
m = re.search(r'(/s/player/[a-f0-9]+/player_ias\.vflset/[^/"]+/base\.js)', html)
if not m:
    # try /watch page
    html2 = fetch("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    m = re.search(r'(/s/player/[a-f0-9]+/player_ias\.vflset/[^/"]+/base\.js)', html2)
if not m:
    print("ERROR: Could not find player JS URL in YouTube HTML")
    sys.exit(1)

player_url = "https://www.youtube.com" + m.group(1)
print(f"Player JS URL: {player_url}")

# ── 2. Fetch player JS ───────────────────────────────────────────────────────
print("Fetching player JS …")
js = fetch(player_url)
print(f"Player JS size: {len(js):,} bytes\n")

# ── 3. Function-definition patterns (same order as InnertubeApi.kt) ──────────
FN_PATTERNS = [
    (r'([\w$]+)\s*=\s*function\(\s*([\w$]+)\s*\)\s*\{',       "style1: NAME=function(a){"),
    (r'function\s+([\w$]+)\s*\(\s*([\w$]+)\s*\)\s*\{',        "style2: function NAME(a){"),
    (r'([\w$]+)\s*=\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',          "style3: NAME=(a)=>{"),
    (r'([\w$]+)\s*=\s*([\w$]+)\s*=>\s*\{',                    "style4: NAME=a=>{"),
    # ── colon (object property) forms — not yet in InnertubeApi v1.2.56 ──────
    (r'([\w$]+)\s*:\s*function\s*\(\s*([\w$]+)\s*\)\s*\{',    "style5: NAME:function(a){  [COLON — NEW]"),
    (r'([\w$]+)\s*:\s*\(\s*([\w$]+)\s*\)\s*=>\s*\{',          "style6: NAME:(a)=>{        [COLON — NEW]"),
    (r'([\w$]+)\s*:\s*([\w$]+)\s*=>\s*\{',                    "style7: NAME:a=>{          [COLON — NEW]"),
]

split_tokens = ['.split("")', ".split('')", "Array.from(", "[..."]
join_tokens  = ['.join("")',  ".join('')"]

hits = []  # (joinIdx, matched_style, fnName, fnParam, context_snippet)

join_positions = [m2.start() for m2 in re.finditer(r'\.join\(""\)', js)]
print(f"Total .join(\"\") occurrences: {len(join_positions)}")

for joinIdx in join_positions:
    seg = js[max(0, joinIdx - 10000): joinIdx]
    has_split = any(t in seg for t in split_tokens)
    if not has_split:
        continue

    matched_style = None
    fn_name = fn_param = ""
    for pat, desc in FN_PATTERNS:
        all_matches = list(re.finditer(pat, seg))
        if all_matches:
            last = all_matches[-1]
            matched_style = desc
            fn_name  = last.group(1)
            fn_param = last.group(2)
            break

    # Show context around join
    ctx = js[max(0, joinIdx - 300): joinIdx + 20]
    hits.append((joinIdx, matched_style, fn_name, fn_param, ctx))

print(f"Occurrences with split+join (candidate sig fns): {len(hits)}\n")
print("=" * 70)

style_counts = {}
for joinIdx, style, fn_name, fn_param, ctx in hits:
    key = style or "NO_MATCH"
    style_counts[key] = style_counts.get(key, 0) + 1

print("Summary of function-definition styles found:")
for k, v in sorted(style_counts.items(), key=lambda x: -x[1]):
    print(f"  {v:3d}x  {k}")

print()

# Show details for the first 3 unmatched (if any)
no_match = [(j, s, fn, p, c) for (j, s, fn, p, c) in hits if s is None]
print(f"Unmatched (no fn-def pattern): {len(no_match)}")
for joinIdx, _, _, _, ctx in no_match[:3]:
    print(f"\n  join at offset {joinIdx}")
    print(f"  last 300 chars before join:")
    for line in ctx.split(";"):
        print(f"    {line[:120]}")

# ── 4. Helper-object arrow fn detection ─────────────────────────────────────
print("\n" + "=" * 70)
print("Helper object method styles (around .splice(0, and .reverse()):")

splice_hits = [m2.start() for m2 in re.finditer(r'\.splice\(0,\d+\)', js)]
for idx in splice_hits[:5]:
    chunk = js[max(0, idx - 300): idx + 20]
    # look for method definition just before this splice
    for pat, desc in FN_PATTERNS:
        mm = list(re.finditer(pat, chunk))
        if mm:
            print(f"  splice at {idx}: helper method style = {desc}")
            print(f"    context: {repr(chunk[max(0,mm[-1].start()-10):mm[-1].end()+50])}")
            break

# ── 5. Call-site detection ───────────────────────────────────────────────────
print("\n" + "=" * 70)
print("Call-site patterns (how the sig fn is invoked):")
cs_patterns = [
    (r'\b[\w$]+&&\([\w$]+=([a-zA-Z0-9_$]+)\((?:\d+,)?decodeURIComponent', "&&(X=FN(decodeURI...)"),
    (r'\bc&&\(c=([a-zA-Z0-9$]+)\(decodeURIComponent',                      "c&&(c=FN(decodeURI...)"),
    (r'[;,=]\s*([a-zA-Z0-9$]+)\(decodeURIComponent\([^)]+\.get\("s"\)',   "X=FN(decodeURI(x.get('s')...)"),
    (r'\.set\(["\']sig["\'],([a-zA-Z0-9$]+)\(',                             ".set('sig',FN(...)"),
    (r'b=([a-zA-Z0-9$]+)\(decodeURIComponent\(b\.get\("s"\)\)\)',          "b=FN(decodeURI(b.get('s')))"),
    # Obj.Method style (not yet in InnertubeApi v1.2.56)
    (r'\b[\w$]+&&\([\w$]+=[\w$]+\.([\w$]+)\((?:\d+,)?decodeURIComponent', "&&(X=OBJ.METHOD(decodeURI...) [NEW]"),
    (r'c&&\(c=[\w$]+\.([\w$]+)\(decodeURIComponent',                        "c&&(c=OBJ.METHOD(decodeURI...) [NEW]"),
]
for pat, desc in cs_patterns:
    mm = re.search(pat, js)
    if mm:
        ctx = js[max(0, mm.start() - 30): mm.end() + 80]
        print(f"  FOUND: {desc}")
        print(f"    fn/method name: {mm.group(1)}")
        print(f"    context: {repr(ctx)}")
        print()

print("Done.")
