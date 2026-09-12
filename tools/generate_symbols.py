#!/usr/bin/env python3
"""Generate app/src/main/assets/symbols.tsv from a Kite Connect instruments dump.

    python3 tools/generate_symbols.py instruments.csv

Get the dump with:

    curl -s https://api.kite.trade/instruments > instruments.csv

The output is the tab-separated format read by SymbolTable.kt:

    SYMBOL<TAB>Company Name<TAB>alias one|alias two

Only NSE equity rows are kept. Everything else in that file — futures, options,
currency, commodity, indices — either shares a symbol with the underlying or is not
something a news headline names.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

OUT = Path("app/src/main/assets/symbols.tsv")

# Corporate-form words the lexicon already strips at match time. Removing them here as
# well keeps the file readable and the aliases meaningful.
SUFFIXES = {
    "ltd", "limited", "ltd.", "inc", "corp", "corporation", "co", "company",
    "pvt", "private", "plc", "the",
}

# Single words that are real tickers somewhere but far more often ordinary English or
# market jargon. The lexicon has its own blocklist; this one keeps them out of the file
# entirely so the asset does not fight it.
SKIP_ALIASES = {
    "india", "indian", "bank", "power", "steel", "gas", "oil", "auto", "motor",
    "motors", "cement", "port", "ports", "tata", "birla", "adani", "reliance",
    "group", "share", "shares", "stock", "index", "nifty", "sensex", "market",
    "gold", "coal", "metal", "metals", "finance", "capital", "industries",
    "enterprises", "technologies", "services", "products", "international",
    "global", "energy", "infra", "infrastructure", "life", "health", "food",
}

SERIES_SUFFIX = re.compile(r"-(BE|BZ|SM|ST|IV|GS|RR|N\d+)$")


def clean_name(name: str) -> str:
    words = [w for w in re.split(r"\s+", name.strip()) if w]
    while words and words[-1].lower().strip(".") in SUFFIXES:
        words.pop()
    return " ".join(words)


def aliases_for(symbol: str, name: str) -> list[str]:
    out: list[str] = []
    words = name.split()
    # "Tata Consultancy Services" -> "Tata Consultancy": the leading two words are how
    # headlines usually shorten a long legal name.
    if len(words) > 2:
        short = " ".join(words[:2])
        if short.lower() not in SKIP_ALIASES and short.lower() != name.lower():
            out.append(short)
    # A one-word name is only useful as an alias if it is distinctive.
    if len(words) == 1 and words[0].lower() in SKIP_ALIASES:
        return []
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    source = Path(argv[1])
    if not source.exists():
        print(f"no such file: {source}", file=sys.stderr)
        return 1

    seen: dict[str, tuple[str, list[str]]] = {}
    with source.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row.get("exchange") != "NSE":
                continue
            if row.get("instrument_type") != "EQ":
                continue
            if row.get("segment") != "NSE":
                continue

            symbol = (row.get("tradingsymbol") or "").strip().upper()
            if not symbol or SERIES_SUFFIX.search(symbol):
                continue

            name = clean_name(row.get("name") or "")
            if not name:
                continue
            seen.setdefault(symbol, (name, aliases_for(symbol, name)))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as handle:
        handle.write(f"# generated from {source.name} — {len(seen)} NSE equity symbols\n")
        handle.write("# SYMBOL<TAB>Name<TAB>alias|alias\n")
        for symbol in sorted(seen):
            name, aliases = seen[symbol]
            line = f"{symbol}\t{name}"
            if aliases:
                line += "\t" + "|".join(aliases)
            handle.write(line + "\n")

    print(f"wrote {OUT} with {len(seen)} symbols")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
