# Learnings: Hermes Agent Babashka Port

## Architecture
- **Simple Made Easy**: Babashka's focus on simple data structures (maps, vectors) and atoms makes it ideal for implementing complex agent logic without the overhead of heavy frameworks.
- **Protocol-Based Backends**: Using Clojure protocols for terminal backends (Chapter 14) allowed for seamless switching between local and remote execution.
- **Dynamic Scoping**: `binding` is a powerful tool for injecting configuration and session state into nested function calls without passing them explicitly everywhere.

## Implementation Details
- **JSON-RPC for MCP**: Implementing MCP required careful handling of JSON-RPC over stdio, which is easily managed with `babashka.process` and `cheshire`.
- **Async Background Tasks**: `future` and `agent` (Clojure's agent, not the AI agent) are useful for background review and scheduled tasks. Note: in Babashka, `future` is often preferred for one-off background work.
- **TUI and CLI**: ANSI escape codes provide a simple way to implement status lines in the terminal without full TUI libraries.

## Gotchas
- **State Persistence**: Always ensure atoms are used for transient state (like conversation counters) and SQLite/JSON for persistent state.
- **JSON Parsing**: In tests, remember to parse the JSON result of tool calls before asserting on content.
- **CORS and Security**: When exposing web APIs (Chapter 19), always restrict origins and use constant-time comparison for tokens.

## Rich Hickey Gap Analysis Results
- **Simplicity vs Complexity**: The port reduced complexity by replacing object-oriented patterns with data-driven transformations.
- **Completeness**: Achieved full parity with the Python implementation across all 27 chapters.
