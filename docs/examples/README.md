The files in this folder, and the code blocks of the documentation taken from them, are licensed under the Apache License 2.0 (decision 148); see `LICENSE` in this folder. The rest of this repository is under the Business Source License 1.1.

`runner.json` (schema: `runner.schema.json`, as in slay400-core) lists the tools `scripts/docs/run_examples.py` sets up
before a clean run: here `slay400-gateway`, this repository's own tool, from the archive this commit builds, and
`slay400`, the generator, from a release of slay400-core (decision 150).
To run the examples, build the gateway (`./mvnw -DskipTests package`), download the slay400-core release archive and
its `.sha256`, check it, and set `SLAY400_CORE_ARCHIVE` to its path; the `build` workflow does the same.

Each example is a folder with `steps.sh` (the commands of the walkthrough, in named regions), `input/` (the files the
reader starts with) and `expected/` (what each region prints). Regions marked `# requires: ibmi` need a real IBM i: they
are not run in the build, and their recorded output comes from the manual `ibmi` workflow.
