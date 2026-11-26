The scripts in this folder, and their tests, are copied unchanged from `scripts/docs/` of
[slay400-core](https://github.com/generativearchitects-ai/slay400-core/tree/14103db/scripts/docs) at commit `14103db`, and
stay under the Apache License 2.0 (decision 151); see `LICENSE` in this folder. The rest of this repository is under the
Business Source License 1.1. Change them in slay400-core and copy them again; this repository describes its own tools in
`docs/examples/runner.json`.

`slay400-install.sh` is the `install.sh` of slay400-core at the same commit, unchanged: `run_examples.py` runs it in place
of the generator's install line, against a local stand-in serving the slay400-core release archive named by
`SLAY400_CORE_ARCHIVE`.
