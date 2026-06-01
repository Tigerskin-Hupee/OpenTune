#!/usr/bin/env bash
# Probe script: directly verifies each step of OpenTune's audio stream resolution pipeline.
# No build required — uses curl + python3 (available everywhere).
# Usage: bash scripts/probe_playback.sh [videoId]

set -euo pipefail
VIDEO="${1:-jNQXAC9IVRw}"   # default: YouTube's first video — always public
UA_BROWSER="Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
UA_TV="Mozilla/5.0 (PlayStation; PlayStation 4/12.02) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Safari/537.36"
UA_IOS="com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"

PASS=0; FAIL=0

pass() { echo "  PASS: $1"; ((PASS++)) || true; }
fail() { echo "  FAIL: $1"; ((FAIL++)) || true; }
section() { echo; echo "══ $1 ══"; }

# ──────────────────────────────────────────────────────────────────
section "STEP 1 — Player JS: can we find the script path?"
# ──────────────────────────────────────────────────────────────────
HOME_HTML=$(curl -s -L --max-time 15 -A "$UA_BROWSER" "https://www.youtube.com" 2>/dev/null)
SCRIPT_PATH=$(echo "$HOME_HTML" | grep -oP '/s/player/[a-f0-9]+/player_ias\.vflset[^"'"'"' ]*base\.js' | head -1)

if [ -z "$SCRIPT_PATH" ]; then
  echo "  Homepage gave no script path — trying watch page fallback..."
  HOME_HTML=$(curl -s -L --max-time 15 -A "$UA_BROWSER" "https://www.youtube.com/watch?v=$VIDEO" 2>/dev/null)
  SCRIPT_PATH=$(echo "$HOME_HTML" | grep -oP '/s/player/[a-f0-9]+/player_ias\.vflset[^"'"'"' ]*base\.js' | head -1)
fi

if [ -z "$SCRIPT_PATH" ]; then
  fail "script path not found in homepage OR watch page — ensurePlayerJsData() will always fail"
  JS=""
else
  pass "script path found: $SCRIPT_PATH"
  JS_URL="https://www.youtube.com$SCRIPT_PATH"
  JS=$(curl -s -L --max-time 20 -A "$UA_BROWSER" "$JS_URL" 2>/dev/null)
  JS_LEN=${#JS}
  if [ "$JS_LEN" -lt 10000 ]; then
    fail "player JS fetched but suspiciously short (${JS_LEN} bytes) — may be a bot-check page"
    JS=""
  else
    pass "player JS fetched: ${JS_LEN} bytes"
  fi
fi

# ──────────────────────────────────────────────────────────────────
section "STEP 2 — Player JS: extract signatureTimestamp + sig fn name"
# ──────────────────────────────────────────────────────────────────
if [ -z "$JS" ]; then
  fail "skipped (player JS not available)"
  SIG_TS=0
  SIG_FN=""
else
  SIG_TS=$(echo "$JS" | grep -oP 'signatureTimestamp[=:]\s*\K\d+' | head -1)
  if [ -z "$SIG_TS" ] || [ "$SIG_TS" -eq 0 ]; then
    fail "signatureTimestamp not found → cachedSigTs=0 → playbackContext empty for all web clients"
    SIG_TS=0
  else
    pass "signatureTimestamp = $SIG_TS"
  fi

  # Try all sig fn name patterns
  SIG_FN=$(echo "$JS" | grep -oP '[;,=]\s*\K[a-zA-Z0-9$]{2,}(?=\(decodeURIComponent\([^)]+\.get\("s"\))' | head -1)
  [ -z "$SIG_FN" ] && SIG_FN=$(echo "$JS" | grep -oP 'c&&\(c=\K[a-zA-Z0-9$]{2,}(?=\(decodeURIComponent)' | head -1)
  [ -z "$SIG_FN" ] && SIG_FN=$(echo "$JS" | grep -oP 'a\.set\("sig"\s*,\s*\K[a-zA-Z0-9$]{2,}(?=\()' | head -1)
  [ -z "$SIG_FN" ] && SIG_FN=$(echo "$JS" | grep -oP '\.sig\s*\|\|\s*\K[a-zA-Z0-9$]{2,}(?=\()' | head -1)
  [ -z "$SIG_FN" ] && SIG_FN=$(echo "$JS" | grep -oP 'b=\K[a-zA-Z0-9$]{2,}(?=\(decodeURIComponent\(b\.get\("s"\))' | head -1)
  [ -z "$SIG_FN" ] && SIG_FN=$(echo "$JS" | grep -oP '\.set\("sig"\s*,\K[a-zA-Z0-9$]{2,}(?=\()' | head -1)

  if [ -z "$SIG_FN" ]; then
    fail "sig decode function name not found → extractSigOps() returns null → decodeCipherUrl() always null"
  else
    pass "sig decode fn name = '$SIG_FN'"

    # Check both function declaration forms
    if echo "$JS" | grep -q "function ${SIG_FN}("; then
      pass "fn body form: function ${SIG_FN}( [standard declaration]"
    elif echo "$JS" | grep -q "${SIG_FN}=function("; then
      pass "fn body form: ${SIG_FN}=function( [minified assignment]"
    else
      fail "fn '$SIG_FN' body not found under either declaration form — extractSigOps() will return null"
    fi
  fi
fi

# ──────────────────────────────────────────────────────────────────
section "STEP 3 — TVHTML5_SEP: does it return OK + signatureCipher?"
# ──────────────────────────────────────────────────────────────────
PLAYBACK_CTX=""
[ "$SIG_TS" -gt 0 ] && PLAYBACK_CTX=",\"playbackContext\":{\"contentPlaybackContext\":{\"signatureTimestamp\":$SIG_TS}}"

SEP_BODY=$(cat <<EOF
{"videoId":"$VIDEO","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"TVHTML5_SIMPLY_EMBEDDED_PLAYER","clientVersion":"2.0","hl":"en","gl":"US","timeZone":"UTC","utcOffsetMinutes":0},"thirdParty":{"embedUrl":"https://www.youtube.com/watch?v=$VIDEO"}}$PLAYBACK_CTX}
EOF
)

SEP_RESP=$(curl -s --max-time 20 \
  -X POST "https://www.youtube.com/youtubei/v1/player" \
  -H "Content-Type: application/json" \
  -H "User-Agent: $UA_TV" \
  -H "X-YouTube-Client-Name: 85" \
  -H "X-YouTube-Client-Version: 2.0" \
  -H "Origin: https://www.youtube.com" \
  -H "Referer: https://www.youtube.com/watch?v=$VIDEO" \
  -H "Cookie: SOCS=CAI=" \
  -d "$SEP_BODY" 2>/dev/null)

SEP_STATUS=$(echo "$SEP_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('playabilityStatus',{}).get('status','?'))" 2>/dev/null || echo "parse_error")
SEP_REASON=$(echo "$SEP_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('playabilityStatus',{}).get('reason',''))" 2>/dev/null || echo "")
SEP_CIPHER=$(echo "$SEP_RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin)
fmts=d.get('streamingData',{}).get('adaptiveFormats',[])
audio=[f for f in fmts if f.get('mimeType','').startswith('audio/')]
cipher=[f for f in audio if 'signatureCipher' in f or 'cipher' in f]
direct=[f for f in audio if 'url' in f and f['url']]
print(f'audio={len(audio)} cipher={len(cipher)} direct={len(direct)}')
" 2>/dev/null || echo "parse_error")
SEP_RAW_CIPHER=$(echo "$SEP_RESP" | python3 -c "
import sys,json
d=json.load(sys.stdin)
fmts=d.get('streamingData',{}).get('adaptiveFormats',[])
for f in fmts:
  if f.get('mimeType','').startswith('audio/') and 'signatureCipher' in f:
    print(f['signatureCipher']); break
  if f.get('mimeType','').startswith('audio/') and 'cipher' in f:
    print(f['cipher']); break
" 2>/dev/null || echo "")

echo "  TVHTML5_SEP status=$SEP_STATUS reason='$SEP_REASON' $SEP_CIPHER sigTs=$SIG_TS"

if [ "$SEP_STATUS" = "OK" ]; then
  pass "TVHTML5_SEP returned OK"
else
  fail "TVHTML5_SEP returned '$SEP_STATUS' — TVHTML5_SEP also blocked, need a different client"
fi

# ──────────────────────────────────────────────────────────────────
section "STEP 4 — Cipher decode: does Python reproduce the sig decode ops?"
# ──────────────────────────────────────────────────────────────────
if [ -z "$SEP_RAW_CIPHER" ] || [ -z "$SIG_FN" ] || [ -z "$JS" ]; then
  fail "skipped (no cipher, no sig fn, or no player JS)"
else
  DECODED_URL=$(python3 - "$JS" "$SEP_RAW_CIPHER" "$SIG_FN" <<'PYEOF'
import sys, re, urllib.parse

js = sys.argv[1]
raw_cipher = sys.argv[2]
fn_name = sys.argv[3]

# Parse cipher params
params = dict(urllib.parse.parse_qsl(raw_cipher))
base_url = params.get('url', '')
raw_sig  = params.get('s', '')
sig_param = params.get('sp', 'sig')

if not base_url or not raw_sig:
    print("ERROR: missing url or s in cipher")
    sys.exit(1)

# Find function body
fn_idx = js.find(f"function {fn_name}(")
if fn_idx < 0:
    fn_idx = js.find(f"{fn_name}=function(")
if fn_idx < 0:
    print(f"ERROR: fn '{fn_name}' not found in JS")
    sys.exit(1)

brace_start = js.find("{", fn_idx)
depth = 0; i = brace_start; fn_body = ""
while i < len(js):
    c = js[i]
    if c == '{': depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0:
            fn_body = js[brace_start:i+1]
            break
    i += 1
if not fn_body:
    print("ERROR: fn body not extracted")
    sys.exit(1)

# Find helper object name
m = re.search(r'([a-zA-Z0-9$]{2,})\.[a-zA-Z0-9$]+\(a', fn_body)
if not m:
    print("ERROR: helper name not found in fn body")
    sys.exit(1)
helper = m.group(1)

# Find helper object
hi = js.find(f"var {helper}={{")
if hi < 0:
    hi = js.find(f",{helper}={{")
    if hi >= 0: hi += 1
if hi < 0:
    print(f"ERROR: helper '{helper}' object not found")
    sys.exit(1)
ob_start = js.find("{", hi)
depth = 0; i = ob_start; helper_body = ""
while i < len(js):
    c = js[i]
    if c == '{': depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0:
            helper_body = js[ob_start:i+1]
            break
    i += 1
if not helper_body:
    print("ERROR: helper body not extracted")
    sys.exit(1)

# Build op map
op_map = {}
for mm in re.finditer(r'([a-zA-Z0-9$]+)\s*:\s*function\s*\(\w+\)\s*\{[^}]*\.reverse\(\)', helper_body):
    op_map[mm.group(1)] = ('reverse',)
for mm in re.finditer(r'([a-zA-Z0-9$]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.splice\(0,', helper_body):
    op_map[mm.group(1)] = ('splice',)
for mm in re.finditer(r'([a-zA-Z0-9$]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^;]*=\w\[0\][^}]*=\w\[0\]', helper_body):
    op_map[mm.group(1)] = ('swap',)

if not op_map:
    print(f"ERROR: op_map empty — helper body:\n{helper_body[:500]}")
    sys.exit(1)

# Apply ops
a = list(raw_sig)
for mm in re.finditer(re.escape(helper) + r'\.([a-zA-Z0-9$]+)\(a(?:,(\d+))?\)', fn_body):
    method = mm.group(1)
    n = int(mm.group(2)) if mm.group(2) else 0
    op = op_map.get(method)
    if op is None:
        print(f"ERROR: unknown method '{method}' — not in op_map {list(op_map.keys())}")
        sys.exit(1)
    if op[0] == 'reverse':
        a.reverse()
    elif op[0] == 'splice':
        del a[:n]
    elif op[0] == 'swap':
        idx = n % len(a)
        a[0], a[idx] = a[idx], a[0]

decoded_sig = ''.join(a)
sep = '&' if '?' in base_url else '?'
final_url = f"{base_url}{sep}{sig_param}={urllib.parse.quote(decoded_sig)}"
print(final_url)
PYEOF
2>/dev/null)

  if [[ "$DECODED_URL" == ERROR* ]] || [ -z "$DECODED_URL" ]; then
    fail "cipher decode failed: $DECODED_URL"
  elif [[ "$DECODED_URL" == https://* ]]; then
    pass "cipher decoded to valid URL: ${DECODED_URL:0:80}..."
    FINAL_CDN_URL="${DECODED_URL//&alr=yes/}"

    # ──────────────────────────────────────────────────────────────────
    section "STEP 5 — Does the decoded CDN URL actually stream (not 403)?"
    # ──────────────────────────────────────────────────────────────────
    CDN_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
      -A "$UA_BROWSER" \
      -H "Range: bytes=0-1" \
      "$FINAL_CDN_URL" 2>/dev/null)
    echo "  CDN response: HTTP $CDN_CODE"
    if [ "$CDN_CODE" = "200" ] || [ "$CDN_CODE" = "206" ]; then
      pass "CDN returned $CDN_CODE — URL is streamable"
    elif [ "$CDN_CODE" = "403" ]; then
      fail "CDN returned 403 — correctly decoded URL is still blocked (CDN-level restriction)"
    else
      fail "CDN returned unexpected code $CDN_CODE"
    fi
  else
    fail "unexpected decode output: $DECODED_URL"
  fi
fi

# ──────────────────────────────────────────────────────────────────
section "BONUS — WEB_REMIX without PoToken: what status?"
# ──────────────────────────────────────────────────────────────────
REMIX_BODY=$(cat <<EOF
{"videoId":"$VIDEO","contentCheckOk":true,"racyCheckOk":true,"context":{"client":{"clientName":"WEB_REMIX","clientVersion":"1.20260213.01.00","hl":"en","gl":"US","timeZone":"UTC","utcOffsetMinutes":0}}}
EOF
)
REMIX_RESP=$(curl -s --max-time 20 \
  -X POST "https://music.youtube.com/youtubei/v1/player" \
  -H "Content-Type: application/json" \
  -H "User-Agent: $UA_BROWSER" \
  -H "X-YouTube-Client-Name: 67" \
  -H "X-YouTube-Client-Version: 1.20260213.01.00" \
  -H "Origin: https://music.youtube.com" \
  -H "Referer: https://music.youtube.com/watch?v=$VIDEO" \
  -H "Cookie: SOCS=CAI=" \
  -d "$REMIX_BODY" 2>/dev/null)

REMIX_STATUS=$(echo "$REMIX_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('playabilityStatus',{}).get('status','?'))" 2>/dev/null || echo "parse_error")
REMIX_REASON=$(echo "$REMIX_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('playabilityStatus',{}).get('reason',''))" 2>/dev/null || echo "")
echo "  WEB_REMIX (no PoToken) status=$REMIX_STATUS reason='$REMIX_REASON'"
if [ "$REMIX_STATUS" = "OK" ]; then
  echo "  → If WEB_REMIX works WITHOUT PoToken here but UNPLAYABLE in-app, the PoToken itself is the problem"
elif [ "$REMIX_STATUS" = "UNPLAYABLE" ]; then
  echo "  → WEB_REMIX UNPLAYABLE even without PoToken = this video or client-version is blocked regardless"
fi

# ──────────────────────────────────────────────────────────────────
section "SUMMARY"
# ──────────────────────────────────────────────────────────────────
echo
echo "  Video: $VIDEO"
echo "  PASS: $PASS   FAIL: $FAIL"
echo
if [ "$FAIL" -eq 0 ]; then
  echo "  All checks passed — TVHTML5_SEP pipeline is working."
  echo "  v1.2.31 (IOS moved to last) should fix playback."
else
  echo "  First failing step indicates the root cause:"
  echo "  STEP 1 fail → ensurePlayerJsData() always silently fails"
  echo "  STEP 2a fail → signatureTimestamp not extracted (sigTs=0)"
  echo "  STEP 2b fail → sig fn name regex needs new pattern"
  echo "  STEP 3 fail → TVHTML5_SEP blocked, need different client"
  echo "  STEP 4 fail → sigOps extraction or applySigOps logic broken"
  echo "  STEP 5 fail → CDN blocks decoded URLs at network level"
fi
