# slay400

Gateway runtime for slay400, Community Edition. Licence: Business Source License 1.1 with an Additional Use Grant.

Formerly `glow400`. The product name is slay400 (decision 84); see "Rename in progress" in the shared `CLAUDE.md` if your local folders still carry the old name.

## Context

Shared rules, decisions, specs and kickoff briefs live in the `slay400-docs` repository. Clone it next to this repository (`../slay400-docs`) and read, in this order:

1. `../slay400-docs/CLAUDE.md`
2. `../slay400-docs/decisions/decision-log.md`
3. `../slay400-docs/specs/gateway-design.md`
4. The current brief in `../slay400-docs/kickoff/`

If `../slay400-docs` is not available, stop and ask for it.

## Keep the context current

`slay400-docs` changes while you work. Pull it when the session starts, before each new task, before opening or updating a pull request, and at least once an hour during long work. Follow the section "Keeping this repository current" in `slay400-docs/CLAUDE.md`, and put `Context: slay400-docs@<short commit hash>` in every pull request description.

This file is a copy of `slay400-docs/repo-templates/slay400/CLAUDE.md`. When the template changes, update this copy in your next pull request.

## Repository-specific rules

- The runtime interprets `descriptor.json` from a bundle. It never loads generated classes and never re-parses overlay files to decide behaviour.
- Encoding and decoding come from the `pcml-codec` module of `slay400-core`; do not re-implement them here.
- All JT400 usage sits behind the call-strategy interface (PCML-driven, raw, mock). Nothing above that interface imports JT400 types.
- Tenant and environment are part of every log line, metric and cache key.
- Community Edition limits (one tenant; one credential pair per tenant and environment) are enforced in one place, not scattered.
- Enterprise features (authentication and claims, cache, async, secret-store connectors, governance) are extension points here and implementations elsewhere. Do not implement them in this repository.
- No credentials in configuration examples; use environment variable placeholders.
