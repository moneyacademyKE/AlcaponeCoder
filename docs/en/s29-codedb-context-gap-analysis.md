# Rich Hickey Gap Analysis: Native `codedb_context` Composer

This analysis evaluates the features, architecture, and tradeoffs of implementing a native Clojure equivalent of the **codedb_context** fused MCP tool in the Hermes harness.

---

## 1. Feature Set Difference Table

| Feature / Capability | External Codedb Context (`codedb_context`) | Native Clojure Context Tool (To Be Implemented) | Gap Description |
| :--- | :--- | :--- | :--- |
| **Unified Request (Fusion)** | Fuses search + outline + symbols + callers in a single call. | Requires multiple sequential tool calls (`codedb_search`, `codedb_outline`, etc.). | **Gap**: Agent must make multiple roundtrips to construct contextual state. |
| **Natural Language Parsing** | Extracts keywords and targets from user instructions. | No built-in NLP parser in the tool registry. | **Gap**: Harness lacks query keyword extraction. |
| **Scored File Ranking** | Ranks files using a scoring heuristic (+5 for symbol defs, -3 for tests, -2 for docs). | Linear search matching without heuristic ranking. | **Gap**: No smart ranking or category-based boosting/penalties. |
| **Windowed Snippet Extraction** | Extracts matched lines with ±2 lines of context, merging overlapping windows. | Search returns only the matched line. Outlines return metadata without code. | **Gap**: Agent does not get surrounding code context for matches. |
| **Callers Resolution** | Resolution of caller locations for defined symbols. | Bypassed; requires custom AST mapping or grep-based callers. | **Gap**: Reference/caller tracking is not automated in search. |

---

## 2. Explanation of Feature Differences, Benefits & Trade-offs

### A. Unified Single-Turn Context vs. Sequential Exploration
*   **Explanation**: Standard agent workflows involve the agent first running `codedb_search` for a keyword, then calling `codedb_outline` on relevant files, and finally reading files using a file reader. `codedb_context` compresses this into a single tool call, returning all ranked snippets and symbol callers in one prompt block.
*   **Benefits (Fused Tool)**:
    *   **Turn Efficiency**: Saves up to 4 reasoning turns and ~57% of tool overhead.
    *   **Context Density**: Delivers high-density code snippets immediately.
*   **Trade-offs**:
    *   **Context Bloat**: May return irrelevant snippets if the scoring heuristic fails, consuming context window tokens.
    *   **Latency**: The single call must perform keyword extraction, search, sorting, and caller resolution synchronously.

### B. Heuristic Scoring (Boosts & Penalties)
*   **Explanation**: To prevent test suites or doc files from cluttering the results, `codedb_context` applies negative weights to test files and docs, while boosting files containing symbol definitions (e.g., matching a function/class name).
*   **Benefits**:
    *   Saves token budget by filtering out boilerplate test files and docs.
    *   Directs agent focus to implementation code first.
*   **Trade-offs**:
    *   If the agent is specifically trying to fix a test, the penalty might push test files down the list (requires query analysis or user override).

---

## 3. Complexity vs. Utility Analysis

| Component | Complexity | Utility for Agent | Decision |
| :--- | :--- | :--- | :--- |
| **Keyword Extractor** | Low | High (extracts query search terms) | **Implement**: Simple regex filter removing common stopwords. |
| **File Ranker & Scorer** | Medium | Very High (prioritizes implementation files) | **Implement**: Heuristic-based scorer with test/doc penalties and symbol definition boosts. |
| **Contextual Snippet Merger** | Medium | High (provides immediate surrounding code context) | **Implement**: Merge overlapping window line intervals. |
| **Caller Chain Resolver** | Medium | Medium (shows usages) | **Implement**: Grep-based reference tracker. |

---

## 4. Actionable Recommendation

**Recommendation**: Natively implement `codedb_context` in [codedb.clj](file:///Users/moe/Desktop/harness/clj_agents/codedb.clj) and register it in the system map. 

### Implementation Action Plan:
1.  **Red Phase**: Define test assertions in `tests/codedb_test.clj` validating keyword extraction, scored file ranking, context merging, and tool response structure. Verify tests fail.
2.  **Green Phase**: Write the Clojure implementation in `clj_agents/codedb.clj`.
3.  **Refactor**: Ensure clean Clojure idioms, robust path splitting, and zero external JVM dependencies.
4.  **Certify**: Verify all tests pass, update documentation (`learnings.md` and `patterns.md`), and stage/commit to git.
