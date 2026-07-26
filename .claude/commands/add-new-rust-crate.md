---
name: add-new-rust-crate
description: Workflow command scaffold for add-new-rust-crate in studious-sniffle.
allowed_tools: ["Bash", "Read", "Write", "Grep", "Glob"]
---

# /add-new-rust-crate

Use this workflow when working on **add-new-rust-crate** in `studious-sniffle`.

## Goal

Adds a new Rust crate to the workspace by creating a Cargo.toml manifest file in the appropriate directory.

## Common Files

- `anything-analyzer-native/crates/*/Cargo.toml`

## Suggested Sequence

1. Understand the current state and failure mode before editing.
2. Make the smallest coherent change that satisfies the workflow goal.
3. Run the most relevant verification for touched files.
4. Summarize what changed and what still needs review.

## Typical Commit Signals

- Create a new directory under anything-analyzer-native/crates/ for the crate.
- Add a Cargo.toml manifest file in the new crate directory.

## Notes

- Treat this as a scaffold, not a hard-coded script.
- Update the command if the workflow evolves materially.