```markdown
# studious-sniffle Development Patterns

> Auto-generated skill from repository analysis

## Overview

This skill teaches the core development patterns and workflows used in the `studious-sniffle` Rust codebase. It covers file organization, code style, crate management, and typical workflows for adding and implementing Rust crates. The repository does not use a specific framework and follows clear conventions for file naming, imports, and exports. Common commands are provided to streamline development tasks.

## Coding Conventions

### File Naming

- **PascalCase** is used for file names.
  - Example: `MyModule.rs`, `AnythingAnalyzer.rs`

### Import Style

- **Relative imports** are preferred.
  - Example:
    ```rust
    mod utils;
    use crate::utils::parse_input;
    ```

### Export Style

- **Named exports** are used to expose specific items.
  - Example:
    ```rust
    pub fn analyze() { /* ... */ }
    pub struct Analyzer { /* ... */ }
    ```

### Example Module

```rust
// File: AnythingAnalyzer.rs

pub struct AnythingAnalyzer {
    // fields
}

impl AnythingAnalyzer {
    pub fn new() -> Self {
        // ...
    }
}
```

## Workflows

### Add New Rust Crate
**Trigger:** When introducing a new logical component or module as a separate Rust crate  
**Command:** `/new-crate`

1. Create a new directory under `anything-analyzer-native/crates/` for the new crate.
2. Add a `Cargo.toml` manifest file in the new crate directory.

**Example:**

```bash
mkdir anything-analyzer-native/crates/my_new_crate
cd anything-analyzer-native/crates/my_new_crate
cargo init --lib
```

The `Cargo.toml` file will look like:

```toml
[package]
name = "my_new_crate"
version = "0.1.0"
edition = "2021"
```

---

### Implement Rust Crate Main or Lib
**Trigger:** When adding the initial implementation for a new or existing Rust crate  
**Command:** `/implement-crate`

1. Create or update `src/lib.rs` (for libraries) or `src/main.rs` (for binaries) in the crate directory.
2. Implement the core logic or entrypoint for the crate.

**Example:**

```rust
// src/lib.rs

pub fn greet(name: &str) -> String {
    format!("Hello, {}!", name)
}
```

Or for a binary:

```rust
// src/main.rs

fn main() {
    println!("Hello, world!");
}
```

---

## Testing Patterns

- **Test files** follow the pattern `*.test.*`.
- The testing framework is **unknown**, but Rust's built-in test framework is likely.
- Example test file: `Analyzer.test.rs`

**Example Test:**

```rust
// Analyzer.test.rs

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_greet() {
        assert_eq!(greet("Alice"), "Hello, Alice!");
    }
}
```

## Commands

| Command         | Purpose                                                        |
|-----------------|----------------------------------------------------------------|
| /new-crate      | Create a new Rust crate in the workspace                       |
| /implement-crate| Add or update the main source file for a crate (lib or binary) |

```