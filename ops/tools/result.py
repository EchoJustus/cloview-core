#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
"""Record, check and pack the result of an ops job (docs/DEVELOPMENT_WORKFLOW.md §5).

Every step is run and recorded by this script, so the operator (a person, a local model
through Codex, or a scheduled task) never retypes exit codes, timings or log excerpts.

    python3 -I ops/tools/result.py new    <job-id>                      # ops/results/<job-id>/result.json
    python3 -I ops/tools/result.py step   <job-id> --title "unit tests" [--cwd DIR] [--timeout S] -- <command>
    python3 -I ops/tools/result.py finish <job-id> [--verdict pass|fail|blocked] [--note TEXT]...
    python3 -I ops/tools/result.py check  <job-id>
    python3 -I ops/tools/result.py pack   <job-id>                      # ops/results/<job-id>.zip

<job-id> may also be a path to the result folder. `step` runs the command through the
shell (cmd.exe on Windows, sh elsewhere), writes its combined output to logs/NN.log and
appends {n, title, cmd, cwd, exit, seconds, log, excerpt} to result.json. `finish` sets
the verdict (default: pass when every step exited 0, else fail). `pack` checks, adds the
SHA-256 of every file under artifacts/, and zips the folder. Standard library only.
"""
import datetime
import hashlib
import json
import os
import pathlib
import platform
import re
import subprocess
import sys
import time
import zipfile

SCHEMA = "cloview-job-result/1"
ROOT = pathlib.Path(__file__).resolve().parents[2]          # the repository
RESULTS = ROOT / "ops" / "results"
ID_RE = re.compile(r"^\d{8}-[a-z0-9]+(?:-[a-z0-9]+)*$")
SIGNALS = re.compile(r"(error|exception|fail|traceback|fatal|denied|not found|cannot|unable)", re.I)
MAX_EXCERPT = 40
MAX_NOTES = 10
VERDICTS = ("pass", "fail", "blocked")
HOME_PATH = re.compile(r"([A-Za-z]:\\Users\\|/home/|/Users/)(?!<user>)[^\\/\s\"']+")


def now():
    return datetime.datetime.now().astimezone().isoformat(timespec="seconds")


def folder_of(job):
    p = pathlib.Path(job)
    return p if (p.is_dir() or os.sep in job or "/" in job) else RESULTS / job


def redact(text):
    """Replace the user-name part of home paths: C:\\Users\\<name> and /home/<name> keep
    their prefix, and the name becomes <user>."""
    return HOME_PATH.sub(lambda m: m.group(1) + "<user>", text)


def read(folder):
    return json.loads((folder / "result.json").read_text(encoding="utf-8"))


def write(folder, data):
    (folder / "result.json").write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def git_ref():
    try:
        out = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True, timeout=20)
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def excerpt(lines, failed):
    hits = [i for i, l in enumerate(lines) if SIGNALS.search(l)]
    if hits:
        keep = sorted({j for i in hits for j in range(max(0, i - 3), min(len(lines), i + 4))})
        chosen = [lines[j] for j in keep]
    elif failed:
        chosen = lines[-MAX_EXCERPT:]
    else:
        chosen = []
    return "\n".join(chosen[:MAX_EXCERPT])


def cmd_new(job):
    folder = folder_of(job)
    if not ID_RE.match(folder.name):
        sys.exit(f"job id must look like 20261026-short-name, not {folder.name!r}")
    if (folder / "result.json").exists():
        sys.exit(f"{folder / 'result.json'} exists")
    (folder / "logs").mkdir(parents=True, exist_ok=True)
    (folder / "artifacts").mkdir(exist_ok=True)
    write(folder, {"schema": SCHEMA, "job": folder.name, "executor": os.environ.get("CLOVIEW_EXECUTOR", "unknown"),
                   "repo_ref": git_ref(), "started": now(), "finished": None,
                   "machine": {"os": platform.platform(), "python": platform.python_version()},
                   "steps": [], "artifacts": [], "verdict": None, "notes": []})
    print(folder)


def cmd_step(job, title, cwd, timeout, command):
    folder = folder_of(job)
    data = read(folder)
    n = len(data["steps"]) + 1
    log = folder / "logs" / f"{n:02d}.log"
    workdir = pathlib.Path(cwd) if cwd else ROOT
    t0 = time.monotonic()
    try:
        proc = subprocess.run(command, shell=True, cwd=workdir, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=timeout)
        code, output = proc.returncode, proc.stdout
    except subprocess.TimeoutExpired as e:
        code, output = None, (e.stdout or b"") + f"\n[result.py] timed out after {timeout} s\n".encode()
    seconds = round(time.monotonic() - t0, 1)
    text = redact(output.decode("utf-8", errors="replace"))
    log.write_text(f"$ {redact(command)}\n(in {redact(str(workdir))})\n\n{text}", encoding="utf-8")
    step = {"n": n, "title": title, "cmd": redact(command), "cwd": redact(str(workdir)), "exit": code, "seconds": seconds,
            "log": f"logs/{log.name}", "excerpt": excerpt(text.splitlines(), code != 0)}
    data["steps"].append(step)
    write(folder, data)
    print(f"step {n} ({title}): exit {code}, {seconds} s, log {step['log']}")
    return 0 if code == 0 else 1


def cmd_finish(job, verdict, notes):
    folder = folder_of(job)
    data = read(folder)
    if verdict is None:
        verdict = "pass" if data["steps"] and all(s["exit"] == 0 for s in data["steps"]) else "fail"
    data["verdict"] = verdict
    data["notes"] = (data.get("notes") or []) + [redact(x) for x in notes]
    data["finished"] = now()
    write(folder, data)
    print(f"verdict: {verdict}")


def problems_of(folder):
    out = []
    try:
        d = read(folder)
    except (OSError, ValueError) as e:
        return [f"result.json unreadable: {e}"]
    for k in ("schema", "job", "executor", "repo_ref", "started", "finished", "machine", "steps", "artifacts",
              "verdict", "notes"):
        if k not in d:
            out.append(f"missing key {k}")
    if d.get("schema") != SCHEMA:
        out.append(f"schema must be {SCHEMA}")
    if d.get("job") != folder.name:
        out.append(f"job {d.get('job')!r} differs from the folder name {folder.name!r}")
    if d.get("verdict") not in VERDICTS:
        out.append(f"verdict must be one of {VERDICTS} (run `finish`)")
    if not d.get("finished"):
        out.append("finished is not set (run `finish`)")
    if not d.get("steps"):
        out.append("no steps recorded")
    for s in d.get("steps") or []:
        if not (folder / s.get("log", "")).is_file():
            out.append(f"step {s.get('n')}: log {s.get('log')} missing")
        if len((s.get("excerpt") or "").splitlines()) > MAX_EXCERPT:
            out.append(f"step {s.get('n')}: excerpt longer than {MAX_EXCERPT} lines")
    if len(d.get("notes") or []) > MAX_NOTES:
        out.append(f"more than {MAX_NOTES} notes")
    for a in d.get("artifacts") or []:
        p = folder / a.get("path", "")
        if not p.is_file():
            out.append(f"artifact {a.get('path')} missing")
        elif a.get("sha256") and a["sha256"] != hashlib.sha256(p.read_bytes()).hexdigest():
            out.append(f"artifact {a.get('path')}: sha256 does not match")
    if any(HOME_PATH.search(x) for x in strings(d)):
        out.append("result.json contains a home path with a user name (C:\\Users\\<name>, /home/<name>)")
    return out


def strings(x):
    if isinstance(x, str):
        yield x
    elif isinstance(x, dict):
        for v in x.values():
            yield from strings(v)
    elif isinstance(x, list):
        for v in x:
            yield from strings(v)


def cmd_check(job):
    folder = folder_of(job)
    problems = problems_of(folder)
    for p in problems:
        print("problem:", p)
    print("ok" if not problems else f"{len(problems)} problem(s)")
    return 0 if not problems else 1


def cmd_pack(job):
    folder = folder_of(job)
    data = read(folder)
    arts = sorted(p for p in (folder / "artifacts").rglob("*") if p.is_file()) if (folder / "artifacts").is_dir() else []
    data["artifacts"] = [{"path": p.relative_to(folder).as_posix(), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                         for p in arts]
    write(folder, data)
    problems = problems_of(folder)
    if problems:
        for p in problems:
            print("problem:", p)
        return 1
    zpath = folder.parent / f"{folder.name}.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(folder.rglob("*")):
            if p.is_file():
                z.write(p, f"{folder.name}/{p.relative_to(folder).as_posix()}")
    print(zpath)
    return 0


def main(argv):
    if len(argv) < 3 or argv[1] not in ("new", "step", "finish", "check", "pack"):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    sub, job, rest = argv[1], argv[2], argv[3:]
    if sub == "new":
        return cmd_new(job) or 0
    if sub == "step":
        if "--" not in rest:
            sys.exit("step needs -- before the command")
        i = rest.index("--")
        opts, command = rest[:i], " ".join(rest[i + 1:])
        title, cwd, timeout = None, None, 1800
        while opts:
            k = opts.pop(0)
            if k == "--title":
                title = opts.pop(0)
            elif k == "--cwd":
                cwd = opts.pop(0)
            elif k == "--timeout":
                timeout = int(opts.pop(0))
            else:
                sys.exit(f"unknown option {k}")
        if not title or not command:
            sys.exit("step needs --title and a command")
        return cmd_step(job, title, cwd, timeout, command)
    if sub == "finish":
        verdict, notes = None, []
        while rest:
            k = rest.pop(0)
            if k == "--verdict":
                verdict = rest.pop(0)
                if verdict not in VERDICTS:
                    sys.exit(f"--verdict must be one of {VERDICTS}")
            elif k == "--note":
                notes.append(rest.pop(0))
            else:
                sys.exit(f"unknown option {k}")
        return cmd_finish(job, verdict, notes) or 0
    if sub == "check":
        return cmd_check(job)
    return cmd_pack(job)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
