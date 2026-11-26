#!/usr/bin/env python3
"""Records the output of the regions of a documentation example that need a real IBM i (gateway kickoff 09), for the manual
ibmi workflow only: docs:examples (scripts/docs/run_examples.py) never runs a region marked "# requires: ibmi".

It runs the example's steps.sh once, as run_examples.py does (a clean folder, the tools of runner.json from a local stand-in
of their releases, each install command replaced by its local script), but with the regions that need IBM i kept, except
those named with --skip, whose work the workflow has already done (the setup tool compiled the programs; the secrets are
already in the environment). It then writes expected/<region>.out for each region that needs IBM i.

The values of the system never reach a recording: IBMI_HOST, IBMI_USER, IBMI_LIBRARY and IBMI_PASSWORD, in any case, are
written <your-system>, <your-user>, <your-library> and <your-password>, as the page writes them; then every recording is
scanned again, and a value still found, or an address that is not the loopback one, fails the run without printing it.

Usage: record_ibmi_example.py <example> [--skip region,region]
Needs the same as run_examples.py (the gateway's archive built, SLAY400_CORE_ARCHIVE set), and the four IBMI_ variables.
Exit code 0: recorded. 1: a problem, one per line, never a value of the system.
"""
import argparse
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts" / "docs"))
import run_examples as run  # noqa: E402  (scripts/docs, Apache-2.0, copied unchanged from slay400-core)

PLACEHOLDERS = [("IBMI_PASSWORD", "<your-password>"), ("IBMI_HOST", "<your-system>"), ("IBMI_LIBRARY", "<your-library>"),
                ("IBMI_USER", "<your-user>")]
IPV4 = re.compile(r"(?<![0-9.])(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9.])")
TIMEOUT_SECONDS = 300


def redact(text: str, values: dict[str, str]) -> str:
    """The text with every value of the system, in any case, replaced by its placeholder; longest value first."""
    for name, placeholder in sorted(PLACEHOLDERS, key=lambda p: -len(values.get(p[0], ""))):
        value = values.get(name, "")
        if value:
            text = re.sub(re.escape(value), placeholder, text, flags=re.IGNORECASE)
    return text


def leaks(text: str, values: dict[str, str]) -> list[str]:
    """What a recording must not hold, by kind, never by value: a value of the system, or an address that is not 127.0.0.1."""
    found = []
    for name, _ in PLACEHOLDERS:
        value = values.get(name, "")
        if value and value.lower() in text.lower():
            found.append(f"the value of {name}")
    if any(address != "127.0.0.1" for address in IPV4.findall(text)):
        found.append("an IP address")
    return found


def script_for(steps_sh: Path, tools, skip: set[str]) -> tuple[str, list[str]]:
    """The script to run: every region but those skipped, IBM i ones included, each followed by a sentinel; each tool's
    install command replaced by its local script, as run_examples.py does. "set +e" before the first region, so that a
    region whose command fails on purpose (a refused sign-on) does not end the run."""
    out_lines, order, current, body = [], [], None, []
    preamble_done = False
    for line in steps_sh.read_text(encoding="utf-8").splitlines():
        start = re.match(r"^#\s*region:\s*(\S+)\s*$", line)
        if start:
            if not preamble_done:
                out_lines.append("set +e")
                preamble_done = True
            current, body = start.group(1), []
            continue
        if re.match(r"^#\s*endregion\s*$", line):
            if current not in skip:
                for body_line in body:
                    for tool in tools:
                        if body_line == tool.install_command:
                            body_line = "sh " + shlex.quote(str(run.REPO / tool.local_script))
                            break
                    out_lines.append(body_line)
                out_lines.append(f"echo '{run._SENTINEL.format(current)}'")
                order.append(current)
            current = None
            continue
        if current is not None:
            body.append(line)
        else:
            out_lines.append(line)
    return "\n".join(out_lines) + "\n", order


def record(example: str, skip: set[str], values: dict[str, str]) -> list[str]:
    example_dir = run.EXAMPLES / example
    steps_sh = example_dir / "steps.sh"
    if not steps_sh.is_file():
        return [f"docs/examples/{example}: no steps.sh"]
    regions = run._snippets.regions_in(steps_sh.read_text(encoding="utf-8"))
    ibmi_regions = [r for r in regions if run.requires_ibmi(regions[r]) and r not in skip]
    try:
        tools = run.load_tools()
        archives = {tool.name: run.resolve_archive(tool) for tool in tools}
    except run.RunnerConfigError as e:
        return list(e.args[0])
    tool_versions = [(tool, archives[tool.name][1]) for tool in tools]
    with tempfile.TemporaryDirectory() as root_dir, tempfile.TemporaryDirectory() as work_dir:
        root, work = Path(root_dir), Path(work_dir)
        env = dict(os.environ)
        for tool in tools:
            standin = root / tool.name
            standin.mkdir()
            run.build_standin(standin, tool, *archives[tool.name])
            env[tool.releases_url_env] = f"file://{standin}"
            env[tool.releases_api_env] = f"file://{standin}/api/latest"
        if (example_dir / "input").is_dir():
            shutil.copytree(example_dir / "input", work, dirs_exist_ok=True)
        home = work / "_home"
        home.mkdir()
        env["HOME"] = str(home)
        script, order = script_for(steps_sh, tools, skip)
        script_file = work / ".recorded.sh"
        script_file.write_text(script, encoding="utf-8")
        try:
            result = subprocess.run(["sh", str(script_file)], cwd=work, env=env, capture_output=True, text=True,
                                    timeout=TIMEOUT_SECONDS)
            combined = result.stdout + result.stderr
        except subprocess.TimeoutExpired:
            return [f"docs/examples/{example}: did not finish within {TIMEOUT_SECONDS} seconds"]
        parts = run._SENTINEL_SPLIT.split(combined)
        chunks, names = parts[0::2], parts[1::2]
        if names != order:
            return [f"docs/examples/{example}: expected the regions {order}, the run reached {names} (output not shown)"]
        captured = dict(zip(order, chunks))
        problems = []
        recordings = {}
        for region in ibmi_regions:
            text = redact(run.normalize(captured[region], tool_versions, str(work)), values)
            found = leaks(text, values)
            if found:
                problems.append(f"docs/examples/{example}#{region}: the recording still holds {', '.join(found)}; nothing written")
            recordings[region] = text
        if problems:
            return problems
        (example_dir / "expected").mkdir(exist_ok=True)
        for region, text in recordings.items():
            (example_dir / "expected" / f"{region}.out").write_text(text, encoding="utf-8")
    return []


def main(argv):
    parser = argparse.ArgumentParser(description="Records the output of an example's regions that need IBM i.")
    parser.add_argument("example")
    parser.add_argument("--skip", default="", help="regions not to run, comma-separated")
    args = parser.parse_args(argv)
    values = {name: os.environ.get(name, "") for name, _ in PLACEHOLDERS}
    missing = [name for name, value in values.items() if not value]
    if missing:
        print("FAIL: not set: " + ", ".join(missing))
        return 1
    problems = record(args.example, {r for r in args.skip.split(",") if r}, values)
    for problem in problems:
        print("FAIL: " + problem)
    if problems:
        return 1
    print(f"OK: docs/examples/{args.example}: the regions that need IBM i were recorded")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
