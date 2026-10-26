#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
"""Markdown summary of cloview result files, for a CI job summary or a quick look.

    python3 -I ops/tools/ci_summary.py verify/out            # every *.json in the folder
    python3 -I ops/tools/ci_summary.py verify/out >> "$GITHUB_STEP_SUMMARY"

Understands the files the test runner, the smokes, business-oracle runs (such as the
fitness app's in cloview-examples) and the Windows harnesses write (unit-test totals,
smoke checks, oracle summaries, 00-summary.json). Other JSON files are listed by name only. Standard library only; exit code 0 unless the folder
is missing, so a summary is written even when tests failed.
"""
import json
import pathlib
import sys


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as e:
        return {"_unreadable": str(e)}


def row(*cells):
    return "| " + " | ".join(str(c) for c in cells) + " |"


def main(argv):
    if len(argv) != 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    folder = pathlib.Path(argv[1])
    if not folder.is_dir():
        print(f"no such folder: {folder}", file=sys.stderr)
        return 2
    lines = ["## cloview results", ""]
    units, smokes, oracles, harness, other = [], [], [], None, []
    for p in sorted(folder.glob("*.json")):
        d = load(p)
        if not isinstance(d, dict):
            other.append(p.name)
        elif "_unreadable" in d:
            other.append(f"{p.name} (unreadable: {d['_unreadable']})")
        elif isinstance(d.get("steps"), dict) and str(d.get("suite", "")).endswith("windows-verification"):
            harness = (p.name, d)
        elif isinstance(d.get("totals"), dict):
            units.append((p.name, d))
        elif "checks_total" in d:
            smokes.append((p.name, d))
        elif str(d.get("suite", "")).endswith("-business-oracles"):
            oracles.append((p.name, d))
        elif "steps" not in d and "verdict" not in d:
            other.append(p.name)
    if harness:
        name, d = harness
        lines += [f"### Windows harness ({name})", "", row("Step", "Status"), row("---", "---")]
        for step, info in d["steps"].items():
            status = info.get("status", "?") if isinstance(info, dict) else info
            lines.append(row(step, f"**{status}**" if status != "ok" else status))
        if d.get("missing_files"):
            lines.append("")
            lines.append("Missing files: " + ", ".join(d["missing_files"]))
        lines.append("")
    if units:
        lines += ["### Unit tests", "", row("File", "Tests", "Assertions passed", "Failures", "Errors"),
                  row("---", "---:", "---:", "---:", "---:")]
        for name, d in units:
            t = d["totals"]
            lines.append(row(name, t.get("test", "?"), t.get("pass", "?"), t.get("fail", "?"), t.get("error", "?")))
            for ns in d.get("namespaces", []):
                if isinstance(ns, dict) and (ns.get("fail") or ns.get("error")):
                    lines.append(row(f"↳ {ns.get('ns')}", ns.get("test", ""), ns.get("pass", ""),
                                     ns.get("fail", ""), ns.get("error", "")))
        lines.append("")
    if smokes:
        lines += ["### GUI smokes", "", row("File", "Checks", "OK"), row("---", "---:", "---")]
        for name, d in smokes:
            lines.append(row(name, f"{d.get('checks_passed', '?')}/{d.get('checks_total', '?')}", d.get("ok")))
        lines.append("")
    if oracles:
        lines += ["### Business oracles", "", row("File", "Host", "Exact", "Total", "OK"),
                  row("---", "---", "---:", "---:", "---")]
        for name, d in oracles:
            lines.append(row(name, d.get("host", "?"), d.get("exact", "?"), d.get("total", "?"), d.get("ok")))
        lines.append("")
    if other:
        lines += ["Other files: " + ", ".join(other), ""]
    if len(lines) == 2:
        lines.append("No result files found.")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
