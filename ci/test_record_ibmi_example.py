#!/usr/bin/env python3
"""Tests of record_ibmi_example.py, and the check of the committed recordings (gateway kickoff 09): an output recorded from
the real IBM i holds no address other than 127.0.0.1, and none of the placeholders' values. The values here are invented;
the run of the workflow checks the real ones, which only it knows, before it writes anything."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import record_ibmi_example as rec  # noqa: E402

VALUES = {"IBMI_HOST": "box.example.invalid", "IBMI_USER": "SOMEONE", "IBMI_LIBRARY": "APPLIB", "IBMI_PASSWORD": "PW-MARKER"}


class RedactTest(unittest.TestCase):

    def test_every_value_in_any_case_becomes_its_placeholder(self):
        text = "signed on to BOX.EXAMPLE.INVALID as someone, library applib, password PW-MARKER"
        self.assertEqual("signed on to <your-system> as <your-user>, library <your-library>, password <your-password>",
                         rec.redact(text, VALUES))

    def test_a_redacted_text_has_no_leak(self):
        self.assertEqual([], rec.leaks(rec.redact("host box.example.invalid user SOMEONE", VALUES), VALUES))

    def test_a_value_left_is_reported_by_its_name_never_by_its_value(self):
        found = rec.leaks("user someone", VALUES)
        self.assertEqual(["the value of IBMI_USER"], found)
        self.assertNotIn("someone", " ".join(found).lower())

    def test_an_address_other_than_the_loopback_one_is_a_leak(self):
        self.assertEqual(["an IP address"], rec.leaks("connected to 192.0.2.10", VALUES))
        self.assertEqual([], rec.leaks("listening on 127.0.0.1:8080", VALUES))

    def test_a_version_is_not_an_address(self):
        self.assertEqual([], rec.leaks("jt400 21.0.7, gateway 0.1.0", VALUES))


class ScriptTest(unittest.TestCase):

    def test_skipped_regions_are_left_out_and_the_others_kept_with_ibmi_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            steps = Path(tmp) / "steps.sh"
            steps.write_text("#!/bin/sh\nset -eu\n\n# region: a\necho a\n# endregion\n\n# region: b\n# requires: ibmi\necho b\n"
                             "# endregion\n\n# region: c\n# requires: ibmi\necho c\n# endregion\n", encoding="utf-8")
            script, order = rec.script_for(steps, [], {"b"})
        self.assertEqual(["a", "c"], order)
        self.assertNotIn("echo b", script)
        self.assertIn("echo c", script)
        self.assertLess(script.index("set -eu"), script.index("set +e"))
        self.assertLess(script.index("set +e"), script.index("echo a"))


class RunRegionsTest(unittest.TestCase):

    def test_standard_error_stays_in_the_region_that_wrote_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            steps = Path(tmp) / "steps.sh"
            steps.write_text("#!/bin/sh\nset -eu\n\n# region: refused\necho out-1\necho err-1 >&2\nfalse\n# endregion\n\n"
                             "# region: next\necho out-2\n# endregion\n", encoding="utf-8")
            script, order = rec.script_for(steps, [], set())
            script_file = Path(tmp) / "run.sh"
            script_file.write_text(script, encoding="utf-8")
            captured, names = rec.run_regions(script_file, Path(tmp), {"PATH": "/usr/bin:/bin"})
        self.assertEqual(order, names)
        self.assertEqual("out-1\nerr-1", captured["refused"].strip())
        self.assertEqual("out-2", captured["next"].strip())


class CommittedRecordingsTest(unittest.TestCase):
    """The recordings in the repository of regions that need IBM i."""

    def test_no_recording_of_a_region_that_needs_ibmi_holds_an_address(self):
        examples = Path(__file__).resolve().parent.parent / "docs" / "examples"
        for steps in examples.glob("*/steps.sh"):
            regions = rec.run._snippets.regions_in(steps.read_text(encoding="utf-8"))
            for region, lines in regions.items():
                recording = steps.parent / "expected" / f"{region}.out"
                if rec.run.requires_ibmi(lines) and recording.is_file():
                    self.assertEqual([], rec.leaks(recording.read_text(encoding="utf-8"), {}), str(recording))


if __name__ == "__main__":
    unittest.main()
