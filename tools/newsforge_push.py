#!/usr/bin/env python3
"""Push a structured quote from TickerForge to NewsForge over ntfy.

Drop this next to `notify.py` and call it wherever you already push an alert. NewsForge
recognises any message whose JSON body carries `"nf": 1` and renders it as a quote panel
instead of a line of text; everything else on the topic still shows as prose.

    from newsforge_push import push_quote
    push_quote("BEL", snapshot, levels=levels, confluence=conf)

Only `symbol` is required. Send whatever you happen to have — the panel shows what
arrives and omits the rest rather than drawing placeholders.
"""

from __future__ import annotations

import json
import time
import urllib.request

NTFY_SERVER = "https://ntfy.sh"
NTFY_TOPIC = "your-topic-here"
NTFY_TOKEN = ""  # only for a private or self-hosted server

SCHEMA_VERSION = 1


def build_payload(
    symbol: str,
    *,
    ltp: float | None = None,
    prev_close: float | None = None,
    vwap: float | None = None,
    day: dict | None = None,
    volume: int | None = None,
    rel_volume: float | None = None,
    levels: dict | None = None,
    confluence: dict | None = None,
    note: str | None = None,
) -> dict:
    """Assemble the payload, dropping anything that is None.

    Omitting a field is meaningful: NewsForge hides what it did not receive. Sending
    nulls or zeroes instead would render as real prices.
    """
    body = {
        "nf": SCHEMA_VERSION,
        "kind": "quote",
        "symbol": symbol.upper(),
        # Seconds, matching ntfy's own timestamps. NewsForge marks a quote stale after
        # five minutes, so send this rather than letting it fall back to receipt time.
        "ts": int(time.time()),
        "ltp": ltp,
        "prev_close": prev_close,
        "vwap": vwap,
        "volume": volume,
        "rel_volume": rel_volume,
        "note": note,
    }
    if day:
        # Keys: o, h, l, c
        body["day"] = {k: v for k, v in day.items() if v is not None}
    if levels:
        # Any names you like; pdh, pdl, pdc, cpr_tc, cpr_p, cpr_bc, r1, s1 are ordered
        # sensibly in the UI, anything else is appended.
        body["levels"] = {k: v for k, v in levels.items() if v is not None}
    if confluence:
        # Keys: score (0-100), verdict (free text)
        body["confluence"] = {k: v for k, v in confluence.items() if v is not None}
    return {k: v for k, v in body.items() if v is not None}


def push_quote(symbol: str, **fields) -> None:
    payload = build_payload(symbol, **fields)
    request = urllib.request.Request(
        f"{NTFY_SERVER.rstrip('/')}/{NTFY_TOPIC}",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            # The title is what the ntfy app shows; NewsForge ignores it for payloads and
            # renders the panel instead. Keeping it readable means one push serves both.
            "Title": f"{symbol.upper()} quote",
            "Tags": "chart",
            **({"Authorization": f"Bearer {NTFY_TOKEN}"} if NTFY_TOKEN else {}),
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        response.read()


if __name__ == "__main__":
    push_quote(
        "BEL",
        ltp=312.4,
        prev_close=306.8,
        vwap=309.8,
        day={"o": 307.0, "h": 314.9, "l": 306.1},
        rel_volume=3.2,
        levels={"pdh": 310.2, "pdl": 301.5, "cpr_tc": 309.1, "cpr_bc": 305.4},
        confluence={"score": 72, "verdict": "bullish"},
        note="Above PDH on 3x volume",
    )
    print("pushed")
