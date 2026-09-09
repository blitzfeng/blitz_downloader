# AGENTS.md

This file provides guidance to AI coding agents (Antigravity and other LLM assistants) working in this repository.

## Project Guidelines

For the comprehensive architecture guide, code style, database design, API signing, and test instructions, please refer to [CLAUDE.md](file:///D:/workplace/BlitzDownloader/CLAUDE.md).

## OpenSpec Workflows

This project follows OpenSpec for spec-driven development.
All OpenSpec workflows and skills are configured in:
- `.agents/workflows/`: Antigravity slash commands (`/opsx-propose`, `/opsx-apply`, `/opsx-continue`, etc.)
- `.agents/skills/`: Skills corresponding to OpenSpec workflows
- `openspec/specs/`: Canonical specifications
- `openspec/changes/`: Active and archived change proposals

### Common OpenSpec Commands
- `openspec doctor`: Verify root and relationship health
- `openspec list` / `openspec list --specs`: List changes and specifications
- `openspec validate --all`: Validate all active changes and specs
- `openspec status --all`: View completion status across active changes
