# Architectural Decision Records (ADR) Log

This document records the architectural decisions made during the evolution of the Hermes Agent Clojure/Babashka Port (`xharness`).

---

## ADR 1: Unified Value-Based System Map Threading
- **Status**: Accepted
- **Context**: Dynamic tool registration using global mutable atoms caused runtime exceptions (e.g., `ClassCastException: Atom cannot be cast to Associative`) and non-deterministic concurrency behavior during parallel tool execution (`pmap`).
- **Decision**: Thread an immutable `system` map purely through all tool registration and dispatch lifecycles. Ensure the registry is a plain map. Use specs (`clojure.spec.alpha`) to enforce structure at startup.
- **Consequences**: Deterministic execution, perfect test reproducibility, and clean subagent context isolation.

---

## ADR 2: In-Context Neuro-Symbolic RL (Taste Feedback Loop)
- **Status**: Accepted
- **Context**: The agent needs to dynamically adapt to style preferences and idiomatic code constraints, but dynamic weight tuning (PPO/DPO) is resource-intensive.
- **Decision**: Store style preferences (idioms/anti-patterns) in a local `taste.json` file. Update it asynchronously at the end of each trajectory using an auxiliary model grading code delta rewards, and inject it as prompts.
- **Consequences**: Lightweight reinforcement learning approximation with zero weight-update latency.

---

## ADR 3: Interpreted Clojure/Babashka Only Architecture
- **Status**: Accepted (Current Workspace)
- **Context**: The Erlang BEAM implementation (`ayncoder` / `hermes_beam`) provides process isolation but introduces compilation complexity, Erlang toolchain dependency, and cross-runtime socket serialization overhead.
- **Decision**: Maintain a pure Clojure/Babashka runtime (`xharness`) for developer onboarding simplicity and fast startup (~20ms). Rely on the pure system map and in-process thread pools (`pmap`) for concurrent tasks, while keeping the architecture aligned with Rich Hickey's "Simple Made Easy" philosophy.
- **Consequences**: Low operational footprint, zero-compilation startup, but lacks the native supervisor process isolation of Erlang/OTP.

---

## ADR 4: Transition to SQLite EAV Datoms Log for state storage
- **Status**: Implemented (July 2026)
- **Context**: Storing session history, cron jobs, and skill stats in multiple flat JSON files (`state.json`, `jobs.json`, `stats.json`) leads to race conditions under parallel tool execution and risk of file corruption or drift.
- **Decision**: Transition state storage to a unified append-only SQLite database (`~/.hermes/state.db`) using the Entity-Attribute-Value (EAV) Datoms model. Use the standard system `sqlite3` CLI tool to run queries and format results as JSON dynamically, keeping the runtime dependency-free.
- **Consequences**: Consolidates state files, ensures ACID transactions, and aligns the Clojure/Babashka port's data model with the primary Erlang BEAM runtime.

