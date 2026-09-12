"""Flag non-exhaustive `when` over project enums.

    python3 tools/check_exhaustive_when.py

Kotlin already guarantees this at compile time, so this is not a substitute for building.
It exists because adding an entry to an enum and forgetting a branch somewhere else is a
cheap mistake to make and an expensive one to find late — this catches it in a second,
without a Gradle run or an SDK.

Enums that share entry names (RESULTS appears in three of them here) are handled by
scoring every candidate enum per `when` block and judging only the best fit.
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java"
sources = list(ROOT.rglob("*.kt"))

# Collect enum name -> entries, for enums declared with a value list.
enums = {}
for f in sources:
    text = f.read_text()
    for m in re.finditer(r"enum class (\w+)[^{]*\{(.*?)\n\}", text, re.S):
        name, body = m.group(1), m.group(2)
        entries = []
        for line in body.splitlines():
            line = line.strip()
            em = re.match(r"^([A-Z][A-Z0-9_]*)\s*[\(,;]", line)
            if em:
                entries.append(em.group(1))
        if entries:
            enums[name] = entries

problems = []
for f in sources:
    text = f.read_text()
    for m in re.finditer(r"when \((?:this|[\w.]+)\) \{(.*?)\n(\s*)\}", text, re.S):
        block = m.group(1)
        if re.search(r"^\s*else\s*->", block, re.M):
            continue
        # Several enums share entry names (RESULTS lives in three of them), so score
        # every candidate and judge only the single best fit for this block.
        best = None
        for enum, entries in enums.items():
            used = [e for e in entries if re.search(rf"(?:^|[\s.({{|])({enum}\.)?{e}\s*(,|->)", block, re.M)]
            if len(used) < 2:
                continue
            score = (len(used), len(used) / len(entries))
            if best is None or score > best[0]:
                best = (score, enum, entries, used)
        if best is None:
            continue
        _, enum, entries, used = best
        missing = [e for e in entries if e not in used]
        if missing:
            line = text[: m.start()].count("\n") + 1
            problems.append(f"{f.relative_to(ROOT)}:{line}  when over {enum} missing {missing}")

for p in sorted(set(problems)):
    print("MISSING BRANCH:", p)
print(f"checked {len(sources)} files, {len(enums)} enums, {len(set(problems))} problems")
sys.exit(1 if problems else 0)
