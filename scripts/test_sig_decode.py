#!/usr/bin/env python3
"""
Sig-decode logic test — no dependencies, no build required.
Tests the same algorithm as InnertubeApi.extractSigOps / applySigOps.
Run: python3 scripts/test_sig_decode.py
"""
import re, urllib.parse, sys

# ── Algorithm (mirrors InnertubeApi.kt) ──────────────────────────────────────

def extract_balanced(js, start):
    close = {'{':'}', '[':']', '(':')'}[js[start]]
    depth, in_str, str_ch, i = 0, False, '', start
    while i < len(js):
        c = js[i]
        if in_str:
            if c == str_ch and (i == 0 or js[i-1] != '\\'): in_str = False
        else:
            if c in '"\'`': in_str, str_ch = True, c
            elif c in '{[(':  depth += 1
            elif c in '}])':
                depth -= 1
                if depth == 0: return js[start:i+1]
        i += 1
    return None

def extract_sig_ops(js):
    patterns = [
        r'[;,=]\s*([a-zA-Z0-9$]{2,})\(decodeURIComponent\([^)]+\.get\("s"\)',
        r'c&&\(c=([a-zA-Z0-9$]{2,})\(decodeURIComponent',
        r'a\.set\("sig"\s*,\s*([a-zA-Z0-9$]{2,})\(',
        r'\.sig\s*\|\|\s*([a-zA-Z0-9$]{2,})\(',
        r'b=([a-zA-Z0-9$]{2,})\(decodeURIComponent\(b\.get\("s"\)',
        r'\.set\("sig"\s*,([a-zA-Z0-9$]{2,})\(',
    ]
    fn_name = None
    for p in patterns:
        m = re.search(p, js)
        if m: fn_name = m.group(1); break
    if not fn_name:
        return None, "sig fn name not found by any pattern"

    print(f"  sig fn name: {fn_name}")
    fn_idx = js.find(f"function {fn_name}(")
    if fn_idx < 0: fn_idx = js.find(f"{fn_name}=function(")
    if fn_idx < 0:
        return None, f"fn '{fn_name}' body not found (neither 'function {fn_name}(' nor '{fn_name}=function(')"
    body_start = js.find("{", fn_idx)
    fn_body = extract_balanced(js, body_start)
    if not fn_body:
        return None, "fn body extraction failed (unbalanced braces?)"
    print(f"  fn body: {fn_body[:120]}")

    m = re.search(r'([a-zA-Z0-9$]{2,})\.[a-zA-Z0-9$]+\(a', fn_body)
    if not m:
        return None, f"helper name not found in fn body: {fn_body[:200]}"
    helper = m.group(1)
    print(f"  helper: {helper}")

    hi = js.find(f"var {helper}={{")
    if hi < 0:
        hi = js.find(f",{helper}={{")
        if hi >= 0: hi += 1
    if hi < 0:
        return None, f"helper object '{helper}' not found in JS"
    ob_start = js.find("{", hi)
    helper_body = extract_balanced(js, ob_start)
    if not helper_body:
        return None, "helper body extraction failed"
    print(f"  helper body: {helper_body[:200]}")

    op_map = {}
    for mm in re.finditer(r'([a-zA-Z0-9$]+)\s*:\s*function\s*\(\w+\)\s*\{[^}]*\.reverse\(\)', helper_body):
        op_map[mm.group(1)] = ('reverse',)
    for mm in re.finditer(r'([a-zA-Z0-9$]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.splice\(0,', helper_body):
        op_map[mm.group(1)] = ('splice',)
    for mm in re.finditer(r'([a-zA-Z0-9$]+)\s*:\s*function\s*\(\w+,\w+\)\s*\{[^}]*\.length[^}]*\}', helper_body):
        op_map[mm.group(1)] = ('swap',)
    print(f"  op_map: {op_map}")

    if not op_map:
        return None, f"op_map empty — helper body:\n{helper_body[:400]}"

    ops = []
    for mm in re.finditer(re.escape(helper) + r'\.([a-zA-Z0-9$]+)\(a(?:,(\d+))?\)', fn_body):
        method, n = mm.group(1), int(mm.group(2)) if mm.group(2) else 0
        op = op_map.get(method)
        if op is None:
            return None, f"unknown method '{method}' — not in op_map {list(op_map.keys())}"
        ops.append((op[0], n))
    return (ops if ops else None), ("" if ops else "ops list empty")

def apply_sig_ops(sig, ops):
    a = list(sig)
    for op, n in ops:
        if op == 'reverse': a.reverse()
        elif op == 'splice': del a[:n]
        elif op == 'swap':
            if a:
                idx = n % len(a)
                a[0], a[idx] = a[idx], a[0]
    return ''.join(a)

# ── Test data ─────────────────────────────────────────────────────────────────

JS_A = ('styleA',
    'var Vo={Gy:function(a,b){a.splice(0,b)},uW:function(a){a.reverse()},yw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};'
    'function Wo(a){a=a.split("");Vo.Gy(a,2);Vo.yw(a,40);Vo.uW(a);Vo.yw(a,6);return a.join("")}'
    ';c&&(c=Wo(decodeURIComponent(c.get("s"))));signatureTimestamp=19950')

JS_B = ('styleB',
    'var Xp={Ra:function(a,b){a.splice(0,b)},Qa:function(a){a.reverse()},Pa:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c}};'
    'Zp=function(a){a=a.split("");Xp.Qa(a);Xp.Ra(a,3);Xp.Pa(a,17);return a.join("")}'
    ';b=Zp(decodeURIComponent(b.get("s")));signatureTimestamp=19851')

JS_C = ('styleC',
    'var Bp={zz:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},yy:function(a){a.reverse()},xx:function(a,b){a.splice(0,b)}};'
    'function Cp(a){a=a.split("");Bp.xx(a,1);Bp.zz(a,55);Bp.yy(a);return a.join("")}'
    ';a.set("sig",Cp(decodeURIComponent(a.get("s"))));signatureTimestamp=20100')

# ── Test runner ───────────────────────────────────────────────────────────────

passed = failed = 0
def ok(t): global passed; print(f"  PASS: {t}"); passed += 1
def fail(t, detail=""): global failed; print(f"  FAIL: {t}"); detail and print(f"        {detail}"); failed += 1

# signatureTimestamp
print("\n══ signatureTimestamp extraction ══")
for name, js in [JS_A, JS_B, JS_C]:
    m = re.search(r'signatureTimestamp[=:]\s*(\d+)', js)
    ts = int(m.group(1)) if m else 0
    (ok if ts > 0 else fail)(f"sigTs from {name} = {ts}")

# Style A — standard function NAME(...)
print("\n══ Style A — standard function declaration ══")
ops_a, err = extract_sig_ops(JS_A[1])
if not ops_a:
    fail("extractSigOps(styleA)", err)
else:
    ok(f"ops extracted: {ops_a}")
    expected = [('splice',2),('swap',40),('reverse',0),('swap',6)]
    if ops_a == expected: ok(f"ops match expected {expected}")
    else: fail(f"ops match expected {expected}", f"got: {ops_a}")
    sig = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwx"
    decoded = apply_sig_ops(sig, ops_a)
    step1 = sig[2:]
    step2 = list(step1); idx=40%len(step2); step2[0],step2[idx]=step2[idx],step2[0]; step2="".join(step2)
    step3 = step2[::-1]
    step4 = list(step3); idx=6%len(step4); step4[0],step4[idx]=step4[idx],step4[0]; step4="".join(step4)
    if decoded == step4: ok(f"apply result correct: {decoded[:20]}...")
    else: fail(f"apply result correct", f"got {decoded[:20]} expected {step4[:20]}")

# Style B — minified NAME=function(...)
print("\n══ Style B — minified NAME=function assignment ══")
ops_b, err = extract_sig_ops(JS_B[1])
if not ops_b:
    fail("extractSigOps(styleB) — NAME=function form not handled", err or
         "Code must search for 'NAME=function(' as fallback after 'function NAME(' fails")
else:
    ok(f"ops extracted: {ops_b}")
    expected = [('reverse',0),('splice',3),('swap',17)]
    if ops_b == expected: ok(f"ops match expected {expected}")
    else: fail(f"ops match expected {expected}", f"got: {ops_b}")

# Style C — .set("sig",...) call site
print("\n══ Style C — .set('sig',...) call site ══")
ops_c, err = extract_sig_ops(JS_C[1])
if not ops_c:
    fail("extractSigOps(styleC)", err)
else:
    ok(f"ops extracted: {ops_c}")
    expected = [('splice',1),('swap',55),('reverse',0)]
    if ops_c == expected: ok(f"ops match expected {expected}")
    else: fail(f"ops match expected {expected}", f"got: {ops_c}")

# applySigOps unit tests
print("\n══ applySigOps correctness ══")
for desc, sig, ops, expect in [
    ("Reverse ABCDE → EDCBA", "ABCDE", [('reverse',0)], "EDCBA"),
    ("Splice(3) ABCDEFGH → DEFGH", "ABCDEFGH", [('splice',3)], "DEFGH"),
    ("Swap(2) ABCDE → CBADE", "ABCDE", [('swap',2)], "CBADE"),
    ("Swap(7) mod5=2 on ABCDE → CBADE", "ABCDE", [('swap',7)], "CBADE"),
    ("Empty sig survives all ops", "", [('reverse',0),('splice',3),('swap',10)], ""),
]:
    r = apply_sig_ops(sig, ops)
    if r == expect: ok(desc)
    else: fail(desc, f"got '{r}' expected '{expect}'")

# end-to-end style B
if ops_b:
    print("\n══ End-to-end style B ══")
    sig = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmn"
    decoded = apply_sig_ops(sig, ops_b)
    s1 = sig[::-1]; s2 = s1[3:]; s3=list(s2); idx=17%len(s3); s3[0],s3[idx]=s3[idx],s3[0]; s3="".join(s3)
    if decoded == s3: ok(f"end-to-end style B: {decoded[:20]}...")
    else: fail("end-to-end style B", f"got {decoded[:20]} expected {s3[:20]}")

print(f"\n══ SUMMARY ══")
print(f"  PASS: {passed}   FAIL: {failed}")
if failed == 0:
    print("  All logic tests passed — sig decode algorithm is correct for all tested JS styles")
else:
    print("  Tests FAILED — see FAIL lines above for root cause")
sys.exit(1 if failed else 0)
