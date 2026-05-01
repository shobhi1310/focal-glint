#!/usr/bin/env python3
"""
Test suite for Focal's hosted LLM inference endpoint.
Tests connection, basic generation, classification tool calling, and extraction tool calling.

Usage:
    python3 scripts/test_cloud_inference.py <endpoint_url> [model_name]

Examples:
    python3 scripts/test_cloud_inference.py https://abc123.ngrok-free.app
    python3 scripts/test_cloud_inference.py http://localhost:8080 gemma-4-finetuned
"""

import sys
import json
import time
import requests

# ─── Config ───────────────────────────────────────────────────────────────────

ENDPOINT = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
MODEL = sys.argv[2] if len(sys.argv) > 2 else "gemma-4-finetuned"
CHAT_URL = f"{ENDPOINT}/v1/chat/completions"
TIMEOUT = 60

passed = 0
failed = 0
results = []


def report(name, ok, detail=""):
    global passed, failed
    status = "PASS" if ok else "FAIL"
    if ok:
        passed += 1
    else:
        failed += 1
    results.append((name, status, detail))
    icon = "✓" if ok else "✗"
    print(f"  {icon} {name}" + (f" — {detail}" if detail else ""))


# ─── Test 1: Health / Connectivity ────────────────────────────────────────────

print(f"\n{'='*60}")
print(f"Focal Cloud Inference Test Suite")
print(f"Endpoint: {ENDPOINT}")
print(f"Model:    {MODEL}")
print(f"{'='*60}\n")

print("1. Connectivity")
try:
    # llama.cpp serves a simple page at root
    r = requests.get(ENDPOINT, timeout=10)
    report("Server reachable", r.status_code < 500, f"HTTP {r.status_code}")
except requests.ConnectionError as e:
    report("Server reachable", False, f"Connection refused: {e}")
except Exception as e:
    report("Server reachable", False, str(e))

# Check /v1/models if available (OpenAI compat)
try:
    r = requests.get(f"{ENDPOINT}/v1/models", timeout=10)
    if r.status_code == 200:
        models = r.json()
        model_ids = [m.get("id", "") for m in models.get("data", [])]
        report("Model endpoint", True, f"Models: {model_ids}")
    else:
        report("Model endpoint", True, f"HTTP {r.status_code} (may not be implemented)")
except Exception as e:
    report("Model endpoint", True, f"Not available ({e})")


# ─── Test 2: Basic Generation (no tools) ─────────────────────────────────────

print("\n2. Basic Generation")
try:
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "user", "content": "Say hello in exactly 3 words."}
        ],
        "max_tokens": 50,
        "temperature": 0.1
    }
    start = time.time()
    r = requests.post(CHAT_URL, json=payload, timeout=TIMEOUT)
    elapsed = time.time() - start

    report("Chat completions endpoint", r.status_code == 200, f"HTTP {r.status_code}, {elapsed:.1f}s")

    if r.status_code == 200:
        data = r.json()
        choices = data.get("choices", [])
        report("Response has choices", len(choices) > 0, f"{len(choices)} choices")

        if choices:
            msg = choices[0].get("message", {})
            content = msg.get("content", "")
            report("Response has content", len(content) > 0, f"'{content[:100]}'")
    else:
        print(f"    Response body: {r.text[:300]}")

except Exception as e:
    report("Chat completions endpoint", False, str(e))


# ─── Test 3: Classification Tool Calling ──────────────────────────────────────

print("\n3. Classification Tool Calling")

CLASSIFY_SYSTEM = (
    "You are a notification triage assistant. Your job is to decide whether each notification "
    "meaningfully adds value to the user's day or just demands their attention without giving "
    "anything back. For every [index] in the list, call classifyNotification exactly once with "
    "that same index. Mark it 'matters' if a thoughtful person would want to know about it now "
    "— something asks for their attention, response, awareness, or money. Mark it 'noise' if it "
    "exists to pull the user into an app, sell them something, surface algorithmic content, or "
    "repeat what they already know. Use a short snake_case reason. Output tool calls only — no prose."
)

CLASSIFY_PROMPT = """[1] Package: com.whatsapp · App: WhatsApp · Title: Mom · Content: Are you coming for dinner tonight? Call me when free.
[2] Package: com.instagram.android · App: Instagram · Title: Instagram · Content: user123 and 5 others liked your photo
[3] Package: com.google.android.gm · App: Gmail · Title: Your Amazon order shipped · Content: Your order #123-456 has shipped and will arrive by Thursday."""

CLASSIFY_TOOL = {
    "type": "function",
    "function": {
        "name": "classifyNotification",
        "description": "Classify a notification from the batch by its index",
        "parameters": {
            "type": "object",
            "properties": {
                "index": {"type": "integer", "description": "1-based index of the notification"},
                "category": {"type": "string", "enum": ["matters", "noise"], "description": "Classification result"},
                "reason": {"type": "string", "description": "Short reason for classification"}
            },
            "required": ["index", "category", "reason"]
        }
    }
}

try:
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": CLASSIFY_SYSTEM},
            {"role": "user", "content": CLASSIFY_PROMPT}
        ],
        "tools": [CLASSIFY_TOOL],
        "tool_choice": "auto",
        "max_tokens": 512,
        "temperature": 0.1
    }
    start = time.time()
    r = requests.post(CHAT_URL, json=payload, timeout=TIMEOUT)
    elapsed = time.time() - start

    report("Tool calling request", r.status_code == 200, f"HTTP {r.status_code}, {elapsed:.1f}s")

    if r.status_code == 200:
        data = r.json()
        msg = data.get("choices", [{}])[0].get("message", {})
        tool_calls = msg.get("tool_calls", [])
        report("Tool calls returned", len(tool_calls) > 0, f"{len(tool_calls)} calls")

        # Parse each tool call
        classified = {}
        for tc in tool_calls:
            fn = tc.get("function", {})
            if fn.get("name") == "classifyNotification":
                try:
                    args = json.loads(fn.get("arguments", "{}"))
                    idx = args.get("index")
                    cat = args.get("category")
                    reason = args.get("reason", "")
                    classified[idx] = (cat, reason)
                    print(f"    [{idx}] {cat} — {reason}")
                except json.JSONDecodeError:
                    print(f"    Failed to parse: {fn.get('arguments', '')[:100]}")

        report("All 3 notifications classified", len(classified) == 3, f"Got {len(classified)}/3")
        report("Mom WhatsApp = matters", classified.get(1, (None,))[0] == "matters",
               f"Got: {classified.get(1, ('missing',))[0]}")
        report("Instagram like = noise", classified.get(2, (None,))[0] == "noise",
               f"Got: {classified.get(2, ('missing',))[0]}")
        report("Amazon shipped = matters", classified.get(3, (None,))[0] == "matters",
               f"Got: {classified.get(3, ('missing',))[0]}")
    else:
        print(f"    Response: {r.text[:300]}")

except Exception as e:
    report("Tool calling request", False, str(e))


# ─── Test 4: Extraction Tool Calling (Finance + Logistics) ────────────────────

print("\n4. Extraction Tool Calling")

EXTRACT_SYSTEM = CLASSIFY_SYSTEM + (
    "\n\nAfter classifying each notification, if it is 'matters', also call the appropriate "
    "extraction tool(s) for it. A notification can match multiple extraction tools "
    "(e.g., a food delivery payment is both finance and logistics). "
    "Available extraction categories: finance, logistics."
)

EXTRACT_PROMPT = """[1] Package: com.hdfc.bank · App: HDFC Bank · Title: Transaction Alert · Content: Rs 425 debited from A/c XX1234 for Swiggy order. Avl bal Rs 12500.
[2] Package: in.swiggy.android · App: Swiggy · Title: Order Update · Content: Your food order from Biryani Blues is out for delivery. Arriving in 8 minutes."""

EXTRACT_FINANCE_TOOL = {
    "type": "function",
    "function": {
        "name": "extractFinance",
        "description": "Extract financial transaction data from a notification about money, payments, or banking",
        "parameters": {
            "type": "object",
            "properties": {
                "index": {"type": "integer", "description": "1-based index of the notification"},
                "amount": {"type": "number", "description": "Transaction amount"},
                "merchant": {"type": "string", "description": "Merchant or payee name"},
                "category": {"type": "string", "enum": ["food", "transport", "shopping", "bills", "transfer", "other"]},
                "direction": {"type": "string", "enum": ["debit", "credit"]}
            },
            "required": ["index", "amount", "merchant", "category", "direction"]
        }
    }
}

EXTRACT_LOGISTICS_TOOL = {
    "type": "function",
    "function": {
        "name": "extractLogistics",
        "description": "Extract delivery or logistics data from a notification about orders, shipments, or tracking",
        "parameters": {
            "type": "object",
            "properties": {
                "index": {"type": "integer", "description": "1-based index of the notification"},
                "item": {"type": "string", "description": "Item or order description"},
                "merchant": {"type": "string", "description": "Merchant or delivery service"},
                "status": {"type": "string", "enum": ["ordered", "shipped", "out_for_delivery", "delivered", "cancelled"]},
                "etaMinutes": {"type": "integer", "description": "ETA in minutes, -1 if unknown"}
            },
            "required": ["index", "item", "merchant", "status", "etaMinutes"]
        }
    }
}

try:
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": EXTRACT_SYSTEM},
            {"role": "user", "content": EXTRACT_PROMPT}
        ],
        "tools": [CLASSIFY_TOOL, EXTRACT_FINANCE_TOOL, EXTRACT_LOGISTICS_TOOL],
        "tool_choice": "auto",
        "max_tokens": 1024,
        "temperature": 0.1
    }
    start = time.time()
    r = requests.post(CHAT_URL, json=payload, timeout=TIMEOUT)
    elapsed = time.time() - start

    report("Multi-tool request", r.status_code == 200, f"HTTP {r.status_code}, {elapsed:.1f}s")

    if r.status_code == 200:
        data = r.json()
        msg = data.get("choices", [{}])[0].get("message", {})
        tool_calls = msg.get("tool_calls", [])
        report("Tool calls returned", len(tool_calls) > 0, f"{len(tool_calls)} calls")

        classify_calls = []
        finance_calls = []
        logistics_calls = []

        for tc in tool_calls:
            fn = tc.get("function", {})
            name = fn.get("name", "")
            try:
                args = json.loads(fn.get("arguments", "{}"))
            except json.JSONDecodeError:
                args = {}

            if name == "classifyNotification":
                classify_calls.append(args)
                print(f"    classify [{args.get('index')}] → {args.get('category')} ({args.get('reason', '')})")
            elif name == "extractFinance":
                finance_calls.append(args)
                print(f"    finance  [{args.get('index')}] → ₹{args.get('amount')} {args.get('merchant')} ({args.get('direction')})")
            elif name == "extractLogistics":
                logistics_calls.append(args)
                print(f"    logistics [{args.get('index')}] → {args.get('merchant')} {args.get('status')} ETA={args.get('etaMinutes')}min")

        report("Classifications made", len(classify_calls) == 2, f"{len(classify_calls)}/2")
        report("Finance extraction", len(finance_calls) >= 1, f"{len(finance_calls)} calls")
        report("Logistics extraction", len(logistics_calls) >= 1, f"{len(logistics_calls)} calls")

        # Validate finance data
        if finance_calls:
            fc = finance_calls[0]
            report("Finance amount correct", fc.get("amount") == 425 or fc.get("amount") == 425.0,
                   f"Got: {fc.get('amount')}")
            report("Finance merchant correct", "swiggy" in fc.get("merchant", "").lower(),
                   f"Got: {fc.get('merchant')}")

        # Validate logistics data
        if logistics_calls:
            lc = logistics_calls[0]
            report("Logistics status correct", lc.get("status") == "out_for_delivery",
                   f"Got: {lc.get('status')}")
            report("Logistics ETA present", lc.get("etaMinutes") is not None,
                   f"Got: {lc.get('etaMinutes')}")
    else:
        print(f"    Response: {r.text[:300]}")

except Exception as e:
    report("Multi-tool request", False, str(e))


# ─── Test 5: X-Focal-Consent Header ──────────────────────────────────────────

print("\n5. Consent Header")
try:
    payload = {
        "model": MODEL,
        "messages": [{"role": "user", "content": "Say ok."}],
        "max_tokens": 10
    }
    r = requests.post(CHAT_URL, json=payload, timeout=TIMEOUT,
                      headers={"X-Focal-Consent": "true"})
    report("Request with consent header", r.status_code == 200, f"HTTP {r.status_code}")

    r2 = requests.post(CHAT_URL, json=payload, timeout=TIMEOUT,
                       headers={"X-Focal-Consent": "false"})
    report("Request without consent", r2.status_code == 200, f"HTTP {r2.status_code}")
except Exception as e:
    report("Consent header", False, str(e))


# ─── Summary ──────────────────────────────────────────────────────────────────

print(f"\n{'='*60}")
print(f"Results: {passed} passed, {failed} failed, {passed+failed} total")
print(f"{'='*60}")
for name, status, detail in results:
    icon = "✓" if status == "PASS" else "✗"
    print(f"  {icon} [{status}] {name}" + (f" — {detail}" if detail else ""))
print()

sys.exit(0 if failed == 0 else 1)
