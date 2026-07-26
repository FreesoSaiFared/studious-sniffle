---
name: implement-rust-crate-main-or-lib
description: Workflow command scaffold for implement-rust-crate-main-or-lib in studious-sniffle.
allowed_tools: ["Bash", "Read", "Write", "Grep", "Glob"]
---

# /implement-rust-crate-main-or-lib

Use this workflow when working on **implement-rust-crate-main-or-lib** in `studious-sniffle`.

## Goal

Implements the main source file for a Rust crate, either as a library or binary.

## Common Files

- `anything-analyzer-native/crates/*/src/lib.rs`
- `anything-analyzer-native/crates/*/src/main.rs`

## Suggested Sequence

1. Understand the current state and failure mode before editing.
2. Make the smallest coherent change that satisfies the workflow goal.
3. Run the most relevant verification for touched files.
4. Summarize what changed and what still needs review.

## Typical Commit Signals

- Create or update src/lib.rs or src/main.rs in the crate directory.
- Implement the core logic or entrypoint for the crate.

## Notes

- Treat this as a scaffold, not a hard-coded script.
- Update the command if the workflow evolves materially.