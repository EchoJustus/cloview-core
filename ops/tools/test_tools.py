# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
"""Tests of ops/tools (standard library only):

    python3 -I -m unittest discover -s ops/tools -p "test_*.py"
"""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
RESULT = HERE / "result.py"
SUMMARY = HERE / "ci_summary.py"


def run(*args, cwd=None):
    return subprocess.run([sys.executable, "-I", *map(str, args)], capture_output=True, text=True, cwd=cwd)


class ResultTool(unittest.TestCase):
    def test_record_check_pack(self):
        with tempfile.TemporaryDirectory() as tmp:
            job = pathlib.Path(tmp) / "20261026-self-test"
            self.assertEqual(0, run(RESULT, "new", job).returncode)
            self.assertNotEqual(0, run(RESULT, "new", job).returncode, "never overwrites a record")
            ok = run(RESULT, "step", job, "--title", "greet", "--", "echo hello from /home/jane/x")
            self.assertEqual(0, ok.returncode, ok.stderr)
            bad = run(RESULT, "step", job, "--title", "fail", "--", "echo context && echo Error: no such thing && exit 3")
            self.assertEqual(1, bad.returncode)
            self.assertEqual(1, run(RESULT, "check", job).returncode, "unfinished records fail the check")
            (job / "artifacts" / "out.json").write_text('{"a": 1}', encoding="utf-8")
            home = "C:\\Users\\"                     # + a user name, as on a Windows machine
            self.assertEqual(0, run(RESULT, "finish", job, "--note", "seen under " + home + "bob\\app").returncode)
            packed = run(RESULT, "pack", job)
            self.assertEqual(0, packed.returncode, packed.stdout + packed.stderr)
            data = json.loads((job / "result.json").read_text(encoding="utf-8"))
            self.assertEqual("cloview-job-result/1", data["schema"])
            self.assertEqual("fail", data["verdict"], "a failing step makes the default verdict fail")
            self.assertEqual([0, 3], [s["exit"] for s in data["steps"]])
            self.assertIn("Error: no such thing", data["steps"][1]["excerpt"])
            self.assertEqual("", data["steps"][0]["excerpt"])
            self.assertIn("/home/<user>/x", data["steps"][0]["cmd"])
            self.assertEqual(["seen under " + home + "<user>\\app"], data["notes"])
            self.assertIn("/home/<user>/x", (job / "logs" / "01.log").read_text(encoding="utf-8"))
            self.assertEqual(["artifacts/out.json"], [a["path"] for a in data["artifacts"]])
            names = zipfile.ZipFile(job.parent / "20261026-self-test.zip").namelist()
            self.assertIn("20261026-self-test/result.json", names)
            self.assertIn("20261026-self-test/logs/02.log", names)
            (job / "artifacts" / "out.json").write_text("tampered", encoding="utf-8")
            check = run(RESULT, "check", job)
            self.assertEqual(1, check.returncode)
            self.assertIn("sha256 does not match", check.stdout)

    def test_ids_and_usage(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertNotEqual(0, run(RESULT, "new", pathlib.Path(tmp) / "Bad_Name").returncode)
            self.assertEqual(2, run(RESULT).returncode)


class Summary(unittest.TestCase):
    def test_summary_of_result_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "unit.json").write_text(json.dumps({"suite": "unit-tests", "totals": {"test": 3, "pass": 9, "fail": 1, "error": 0},
                                                     "namespaces": [{"ns": "a-test", "test": 3, "pass": 9, "fail": 1, "error": 0}]}))
            (d / "smoke.json").write_text(json.dumps({"suite": "s", "checks_total": 5, "checks_passed": 5, "ok": True}))
            (d / "oracles.json").write_text(json.dumps({"suite": "fitness-business-oracles", "host": "jvm", "exact": 37,
                                                        "total": 37, "ok": True}))
            (d / "broken.json").write_text("{not json")
            (d / "00-summary.json").write_text(json.dumps({"suite": "cloview-examples-windows-verification",
                                                           "steps": {"03-clr": {"status": "failed"}}}))
            out = run(SUMMARY, d)
            self.assertEqual(0, out.returncode)
            self.assertIn("| unit.json | 3 | 9 | 1 | 0 |", out.stdout)
            self.assertIn("↳ a-test", out.stdout)
            self.assertIn("| smoke.json | 5/5 | True |", out.stdout)
            self.assertIn("### Business oracles", out.stdout)
            self.assertIn("| oracles.json | jvm | 37 | 37 | True |", out.stdout)
            self.assertIn("### Windows harness (00-summary.json)", out.stdout)
            self.assertIn("| 03-clr | **failed** |", out.stdout)
            self.assertIn("broken.json (unreadable", out.stdout)
            self.assertEqual(2, run(SUMMARY, d / "missing").returncode)

    def test_utf8_on_any_stream(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            (d / "unit.json").write_text(json.dumps({"suite": "unit-tests", "totals": {"test": 1, "pass": 0, "fail": 1, "error": 0},
                                                     "namespaces": [{"ns": "a-test", "test": 1, "pass": 0, "fail": 1, "error": 0}]}))
            # cp1252, what Python on Windows uses for redirected stdout, cannot encode the arrow of a failing namespace
            # (without -I, so that PYTHONIOENCODING applies)
            out = subprocess.run([sys.executable, str(SUMMARY), str(d)], capture_output=True,
                                 env={**os.environ, "PYTHONIOENCODING": "cp1252"})
            self.assertEqual(0, out.returncode, out.stderr.decode("utf-8", "replace"))
            self.assertIn("\N{DOWNWARDS ARROW WITH TIP RIGHTWARDS} a-test", out.stdout.decode("utf-8"))

    def test_require_ok(self):
        def harness(d, steps, **extra):
            (d / "00-summary.json").write_text(json.dumps({"suite": "cloview-windows-verification", "steps": steps, **extra}))

        with tempfile.TemporaryDirectory() as tmp:
            d = pathlib.Path(tmp)
            self.assertEqual(1, run(SUMMARY, "--require-ok", d).returncode, "no harness summary: not passed")
            harness(d, {"01-env-check": {"status": "ok"}, "02-jvm-unit-tests": {"status": "ok"}})
            out = run(SUMMARY, "--require-ok", d)
            self.assertEqual(0, out.returncode)
            self.assertIn("**Passed (--require-ok):**", out.stdout)
            harness(d, {"01-env-check": {"status": "ok"}, "02-jvm-unit-tests": {"status": "failed"},
                        "06-flutter-compile-and-test": {"status": "skipped"}})
            out = run(SUMMARY, "--require-ok", d)
            self.assertEqual(1, out.returncode, "a failed or skipped step fails")
            self.assertIn("| 02-jvm-unit-tests | **failed** |", out.stdout, "the summary is written all the same")
            self.assertIn("02-jvm-unit-tests: failed", out.stdout)
            self.assertIn("06-flutter-compile-and-test: skipped", out.stderr)
            self.assertEqual(0, run(SUMMARY, d).returncode, "without the option, failures are only shown")
            harness(d, {"01-env-check": {"status": "ok"}}, aborted="exception: stopped")
            self.assertEqual(1, run(SUMMARY, "--require-ok", d).returncode, "an aborted run fails")
            harness(d, {})
            self.assertEqual(1, run(SUMMARY, "--require-ok", d).returncode, "a summary without steps fails")
            self.assertEqual(2, run(SUMMARY, "--require-ok").returncode)


if __name__ == "__main__":
    unittest.main()
