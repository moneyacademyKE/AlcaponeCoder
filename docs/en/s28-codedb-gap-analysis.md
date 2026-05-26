# Rich Hickey Gap Analysis: Native CodeDB vs. External MCP Server

This analysis evaluates the features, architecture, and tradeoffs of the native Clojure-based CodeDB module (`clj_agents/codedb.clj`) in the Hermes harness compared to the external, Zig-based **justrach/codedb** MCP server.

---

## 1. Feature Set Difference Table

The table below lists all capabilities of `justrach/codedb` (including its CLI/MCP tools) and compares them against our native Clojure implementation.

| Feature / Capability | External Codedb MCP (`justrach/codedb`) | Native Clojure CodeDB (`clj_agents/codedb.clj`) | Gap Description |
| :--- | :--- | :--- | :--- |
| **`codedb_tree`** | Returns a relative file tree including programming languages, line counts, and symbol counts. | Returns list of sorted relative paths. Excludes ignored folders. | **Gap**: Native does not return line counts, symbol counts, or file languages in the tree output. |
| **`codedb_outline`** | Extracts all symbols (functions, structs, imports) using robust AST-based tree-sitter parsers for 10+ languages. | Extracts Clojure, Python, JS/TS symbols using lightweight regex patterns. | **Gap**: Less robust parsing (regex vs tree-sitter AST). Fewer languages supported natively. |
| **`codedb_search`** | Trigram-accelerated full-text search supporting regex, case-insensitivity, and scoped file-type searches. | Sequential substring scan (in-memory grep) across project files. Capped at 50 results. | **Gap**: Native lacks index acceleration (slower on large repos >50MB) and scoped file-type filters. |
| **`codedb_deps`** | Queries reverse dependency graph for files. Supports multi-language import tracking. | Extracts Clojure dependencies via `:require` namespace declarations. | **Gap**: Multi-language dependency analysis is Clojure-only in native implementation. |
| **`word` (Word Search)** | O(1) exact word lookup using inverted index. | Not Implemented. | **Gap**: Missing quick symbol/keyword index lookup. |
| **`hot` (Hot Files)** | Tracks recently modified files using file system watchers or git status. | Not Implemented. | **Gap**: Missing quick overview of active/frequently modified files. |
| **`snapshot`** | Generates a codebase map snapshot (`codedb.snapshot`) to the project root directory. | Not Implemented. | **Gap**: Native relies on live execution instead of persisting file snapshots. |
| **`serve` (Daemon)** | Runs as a local HTTP daemon (useful for headless clients). | Not Implemented. | **Gap**: Running as a daemon is not applicable to our embedded in-process architecture. |
| **`mcp` (JSON-RPC)** | Communicates over JSON-RPC (stdio/HTTP) conforming to Model Context Protocol. | Direct integration into the System Map/Registry in-process. | **Gap**: Native bypasses JSON-RPC/stdio to avoid inter-process communication (IPC) overhead. |
| **Remote Repository Queries** | Queries public GitHub repositories directly without cloning them locally. | Not Implemented. | **Gap**: Native only works on local workspace files. |
| **Auto-Prepended Codebase Map**| Auto-prepends sorted relative workspace layout to system prompt at turn 0. | Generates codebase map and injects it into `prompt/build-system-prompt`. | **Gap None**: Native auto-injection successfully implemented and verified. |

---

## 2. Deep Dive: Explanations, Benefits, and Trade-offs

### A. Parser Robustness: Regex vs. Tree-sitter AST
*   **Explanation**: `justrach/codedb` uses native tree-sitter bindings to construct a formal Abstract Syntax Tree (AST) of files. This enables precise symbol extraction (e.g., distinguishing a function definition from a function call or a comment). The native Clojure implementation uses regular expressions (`re-find`, `re-seq`) to match patterns like `defn` or `class`.
*   **Benefits (Regex/Native)**:
    *   Zero compiled dependency footprint: No need to compile or distribute platform-specific tree-sitter libraries (dynamic `.so` / `.dylib` files).
    *   Instant parsing speed on source files (<1ms).
*   **Trade-offs**:
    *   Fragility: Can produce false positives in comment blocks or multi-line strings.
    *   Limited language coverage: Adding new language support requires writing custom Clojure regex engines.

### B. Indexing and Search Acceleration: Trigram Database vs. Sequential Scanning
*   **Explanation**: `justrach/codedb` builds an in-memory trigram index of the repository's text. This allows O(1) or O(log N) searches across thousands of files. Our native implementation slurps each file sequentially and searches via substring checking.
*   **Benefits (Sequential/Native)**:
    *   No index invalidation bugs: Because it reads the live filesystem on demand, the results are always 100% up-to-date without needing file watchers or manual index updates.
    *   Simple, pure Clojure implementation with no external database files or memory bloat.
*   **Trade-offs**:
    *   Performance degrades linearly with workspace size: Large repositories (>1,000 files) will experience latency (>100ms) on `codedb_search` calls.

### C. Protocol/IPC: JSON-RPC over stdio vs. In-Process Clojure Calls
*   **Explanation**: Standard MCP servers communicate using JSON-RPC messages sent over stdio pipes or local HTTP requests. The native Clojure integration registers functions directly into the Hermes system map.
*   **Benefits (Direct Integration)**:
    *   No subprocess management: No risk of zombie processes, port collisions, or blocked stdio pipes.
    *   100% compatible with restricted container environments where launching subprocesses or binding to local network ports is blocked/forbidden.
    *   Zero serialization latency.
*   **Trade-offs**:
    *   Tightly coupled: The tools cannot be easily shared with other non-Clojure clients (e.g., Cursor, VS Code) without implementing an external MCP server adapter.

---

## 3. Complexity vs. Utility Matrix

We analyze the missing features to determine if adding them to the native implementation is worth the complexity.

| Feature / Gap | Complexity | Utility for Agent | Decision |
| :--- | :--- | :--- | :--- |
| **Line & Symbol counts in tree** | Low | Medium (helps agent gauge file sizes) | **Implement**: Extend `codedb_tree` output format. |
| **Word Index / Inverted Index** | Medium | Low (general grep search is usually sufficient) | **Defer**: Keep substring search for simplicity. |
| **Git Status / Hot Files** | Low | High (focuses agent attention on modified areas) | **Implement**: Add git-based active file detection. |
| **Tree-sitter Parsing** | High | High (precision) | **Defer**: Keep regex patterns unless false positives become a blocker. |
| **Remote Github Queries**| High | Low (our agent runs locally in the workspace) | **Defer**: Do not implement. |

---

## 4. Actionable Recommendations & Implementation Plan

Based on a weighted analysis of **Power vs. Speed vs. Complexity vs. Tradeoffs**, we recommend the following next steps:

1.  **Enhance `codedb_tree` with File Size Info**: Instead of full line and symbol counts which require parsing every file (slow), include file sizes directly from Java's `File` metadata.
2.  **Add `codedb_hot` (Hot Files/Git status) Tool**: Implement a native helper that queries `git status` or lists files sorted by modification time to give the agent instant visibility into active changes.
3.  **TDD Validation**: Write tests first (Red) for any added functionality, verify failure, then write minimal code to pass (Green), maintaining Rich Hickey's simplicity-first design.
