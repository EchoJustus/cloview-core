#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
"""Markdown summary of cloview result files, for a CI job summary or a quick look.

    python3 -I ops/tools/ci_summary.py verify/out            # every *.json in the folder
    python3 -I ops/tools/ci_summary.py verify/out >> "$GITHUB_STEP_SUMMARY"
    python3 -I ops/tools/ci_summary.py --require-ok verify/out >> "$GITHUB_STEP_SUMMARY"

Understands the files the test runner, the smokes, business-oracle runs (such as the
fitness app's in cloview-examples) and the Windows harnesses write (unit-test totals,
smoke checks, oracle summaries, 00-summary.json). Other JSON files are listed by name
only. Standard library only.

The summary is written as UTF-8, whatever encoding Python would pick for stdout (cp1252
when it is redirected on Windows). Exit code 0 unless the folder is missing (2), so a
summary is written even when tests failed. With --require-ok (the verdict CI asks for),
exit code 1 unless the folder holds a Windows harness summary whose steps are all "ok"
and whose run was not aborted: in CI a skipped step fails too, while the harness run
locally keeps reporting skips without failing.
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


def step_status(info):
    return info.get("status", "?") if isinstance(info, dict) else info


def not_ok(harness):
    """Why a harness summary fails --require-ok; empty when every step is ok."""
    if not harness:
        return ["no Windows harness summary (00-summary.json)"]
    _, d = harness
    problems = [f"{step}: {step_status(info)}" for step, info in d["steps"].items() if step_status(info) != "ok"]
    if not d["steps"]:
        problems.append("no steps recorded")
    if d.get("aborted"):
        problems.append(f"aborted: {d['aborted']}")
    return problems


def main(argv):
    args = argv[1:]
    require_ok = "--require-ok" in args
    args = [a for a in args if a != "--require-ok"]
    if len(args) != 1:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    folder = pathlib.Path(args[0])
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
            status = step_status(info)
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
    problems = not_ok(harness) if require_ok else []
    if require_ok:
        if lines[-1]:
            lines.append("")
        lines.append("**Not passed (--require-ok):** " + "; ".join(problems) if problems
                     else "**Passed (--require-ok):** every harness step is ok.")
    print("\n".join(lines))
    if problems:
        print("ci_summary.py --require-ok: not passed: " + "; ".join(problems), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")   # redirected on Windows, stdout is cp1252: no arrows
    sys.exit(main(sys.argv))
