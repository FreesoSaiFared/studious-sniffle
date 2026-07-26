# Anything Analyzer Native

A separate, non-Electron reimplementation of the capture, storage, and proxy foundation of `Mouseww/anything-analyzer`.

The Rust workspace contains:

- `aa_protocol`: shared capture records and operation identities.
- `aa_store`: thread-safe SQLite persistence and JSON export.
- `aa_proxy`: cross-platform HTTP forward proxy and HTTPS CONNECT tunnel recorder.
- `aa_cli`: database/session control and an executable self-test.

GitHub Actions compiles and tests the implementation on Linux, Windows, and macOS.

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace --all-targets
cargo build --workspace --release
```

Example:

```bash
cargo run -p aa_cli -- init --db aa.db
cargo run -p aa_cli -- create-session --db aa.db --id demo --name Demo --target-url https://example.com
cargo run -p aa_proxy -- --db aa.db --session demo --listen 127.0.0.1:8899
```
