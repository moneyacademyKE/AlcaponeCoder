# Babashka Port: Architecture & Hardening Guide

> This document describes the production Clojure/Babashka implementation of Hermes Agent. It is the source of truth for the `clj_agents/` module structure, system design decisions, and hardening status.

---

## Why Babashka

| Concern | Python | Babashka |
|---------|--------|----------|
| Startup time | ~200ms | ~20ms (native binary) |
| Immutability | Optional | Default (persistent data structures) |
| State management | Mutable by default | System-map threading |
| Container size | 40MB+ with deps | Single 20MB static binary |
| Error model | Exception-throwing | Error-as-value (maps) |
| Concurrency | GIL-limited | `pmap` + explicit locking |

---

## Module Map

### Core Pipeline
| File | Responsibility |
|------|---------------|
| `harbor.clj` | Entry point. Reads `HARBOR_INSTRUCTION`, creates system, calls `run-conversation`, prints JSON result, always exits 0. |
| `system.clj` | `create-system`: creates the base system map and threads it through all `register-tools` calls. |
| `agent.clj` | The main `loop/recur` agent loop. Manages turns, budget, compaction triggers, pmap dispatch. |
| `registry.clj` | Pure map tool registry. `(register system tool-def)` → enriched system. `(dispatch system name args)` → result string. |
| `llm.clj` | LLM API calls. Primary → Fallback model switching on 401/402/404. Auxiliary tier for background tasks. |

### Intelligence Layer
| File | Responsibility |
|------|---------------|
| `memory.clj` | Read/write `~/.hermes/MEMORY.md` + `USER.md`. `register-tools` adds `memory` tool. Consolidation every 20 turns. |
| `skill.clj` | Skill index from `~/.hermes/skills/`. `register-tools` adds `skill_view` + `skill_manage`. |
| `compression.clj` | Context compaction. Triggered at `threshold_chars` (default 25k). 3-layer: prune → find boundaries → LLM summarize. |
| `reviewer.clj` | Background skill extraction. Runs in separate thread after N turns. |
| `prompt.clj` | System prompt assembly: SOUL.md + plan + institutional knowledge + memory + skills + project context. |
| `permissions.clj` | Dangerous command detection. Bypassed in HEADLESS mode (benchmark environments). |
| `recovery.clj` | API error classification + jittered exponential backoff. |

### Tool Handlers
| File | Tools Registered |
|------|----------------|
| `tools/terminal.clj` | `terminal` — shell command via `backend` |
| `tools/browser.clj` | `browser_navigate`, `browser_snapshot`, `browser_click`, `browser_type` |
| `tools/patch.clj` | `patch` — precise string replacement in files |
| `tools/multimedia.clj` | `vision_analyze`, `text_to_speech` |
| `tools/system_tools.clj` | `set_plan` — roadmap persistence via plan-atom |

### Infrastructure
| File | Responsibility |
|------|---------------|
| `logger.clj` | Structured JSON logging to `~/.hermes/hermes.log`. Secret masking. Conditional Harbor log. |
| `store.clj` | Session persistence via `state.json`. SQLite optional. |
| `config.clj` | Default config map + `load-config` (deep merge over `~/.hermes/config.yaml`). |
| `backend.clj` | Execution environment abstraction (local / docker / ssh). |
| `hooks.clj` | Pre/post tool call lifecycle hooks. |
| `delegation.clj` | Subagent spawning with isolated context and depth limit. |

---

## System Map Shape

```clojure
{:id             "uuid"               ; session ID
 :config         {}                   ; merged config map
 :budget         90                   ; turns remaining (plain int, NOT atom)
 :depth          0                    ; delegation depth (0 = top-level)
 :env            <backend-env>        ; execution environment handle
 :registry       {"terminal" {...}    ; tool registry (plain map, NOT atom)
                  "memory"   {...}
                  ...}                ; 13 tools total after create-system
 :hooks          {}                   ; lifecycle hooks (plain map)
 :approvals      {}                   ; user permissions (plain map)
 :cron-jobs      {}                   ; background jobs (plain map)
 :skill-stats    {}                   ; usage stats (plain map)
 :state          {:turns-since-memory 0
                  :iters-since-skill  0
                  :plan               "No plan..."} ; pure string
 :browser-process (atom nil)}        ; OS process handle (atom OK for external IO)
```

**Key invariant**: The system map is purely immutable data (following Rich Hickey's "Simple Made Easy" principles). Tools cannot mutate the system using `swap!` or `reset!`. Instead, if a tool needs to change the system state, it returns an explicit payload: `{:result string :system-update (fn [sys] ...)}`. The `agent.clj` loop handles concurrency by running tools via `pmap` and then sequentially reducing their update functions into the next epoch's system map.

---

## Tool Registration Pattern

Every tool module MUST follow this pattern exactly:

```clojure
;; Pure registration — returns enriched system
(defn register-tools [system]
  (registry/register system {:name "my_tool"
                              :handler (fn [system args] ...)
                              :schema {...}}))

;; Legacy alias — for compatibility (both now do the same thing)
(defn register-tools! [system] (register-tools system))
```

`system/create-system` threads all registrations:

```clojure
(-> base
    (memory/register-tools)       ; adds "memory"
    (skill/register-tools)        ; adds "skill_view", "skill_manage"
    (delegation/register-tools)   ; adds "delegate_task"
    (tools.terminal/register-tools)  ; adds "terminal"
    (tools.browser/register-tools)   ; adds 4 browser tools
    (tools.system-tools/register-tools) ; adds "set_plan"
    (tools.patch/register-tools)     ; adds "patch"
    (tools.multimedia/register-tools)) ; adds "vision_analyze", "text_to_speech"
```

**Do not** add `(register-tools! system)` calls that discard the return value. The return value IS the enriched system.

---

## Model Tiers

```clojure
{:models {:primary   "minimax/minimax-m2.5:free"
          :fallback  "poolside/laguna-m.1:free"
          :auxiliary "minimax/minimax-m2.5:free"}}
```

- **Primary**: Used for all main reasoning turns.
- **Fallback**: Auto-activated after 3 consecutive 401/402/404 errors on primary. Resets on success.
- **Auxiliary**: Used for context compaction summaries and memory consolidation. Keeps primary budget intact.

---

## Context Compaction

Triggered in `agent.clj` when `(compression/count-chars messages)` exceeds `threshold_chars` (default: 25,000):

```
Layer 1: Prune old tool outputs (keep last N)
Layer 2: Find head/tail boundaries
          head = first N messages (protect task context)
          tail = last K chars of messages (protect recent context)
Layer 3: LLM summarization of middle
          result: [head-messages | summary-message | tail-messages]
```

Config: `[:compression :threshold_chars]` — must match exactly (no `:threshold_tokens`).

---

## Error Recovery Chain

```
API call fails
  ├─ 429 Rate Limit    → jittered backoff, retry (up to 3x)
  ├─ 401/402/404       → strike counter += 1
  │    └─ strikes >= 3 → switch :active-model-key to :fallback
  ├─ 500/503           → retry with backoff
  └─ Other exception   → log, return {:status :error ...} (never throw)
```

The agent loop NEVER calls `(System/exit)` directly. Only `harbor.clj` exits the process.

---

## Testing

```sh
# Run all tests
cd xharness && bb --classpath clj_agents -e "
(require '[clojure.test :as t])
(load-file \"tests/agent_error_test.clj\")  ; error recovery cases
(load-file \"tests/agent_test.clj\")        ; core loop logic
(load-file \"tests/registry_test.clj\")     ; tool registration/dispatch
(load-file \"tests/s02_test.clj\")          ; system map registry API
(load-file \"tests/s04_test.clj\")          ; prompt builder
(load-file \"tests/s05_test.clj\")          ; compression layers
(load-file \"tests/s06_test.clj\")          ; error recovery classification
(t/run-tests 'agent-error-test 'agent-test 'registry-test
             's02-test 's04-test 's05-test 's06-test)
"
# Target: 8 tests, 42 assertions, 0 failures, 0 errors
```

---

## Known Gotchas

| Symptom | Root Cause | Fix |
|---------|-----------|-----|
| `ClassCastException: Atom cannot be cast to Associative` | `:registry` initialized as atom | Change to `{:registry {}}` in `system.clj` |
| `NullPointerException` in set_plan | Trying to dereference nil plan atom | Use `(:plan-atom system)` then `(reset! plan-atom ...)` |
| All tools unregistered (registry empty) | `register-tools!` return value discarded | Must thread: `(-> system (a/register-tools) (b/register-tools))` |
| Config has no effect | Key name mismatch (tokens vs chars) | `grep -r "key-name" clj_agents/` to find all sites |
| Log file missing | Using relative `./hermes.log` | Always use `~/.hermes/hermes.log` |
| Harbor exits non-zero | Exception escaping `harbor.clj` top-level | Wrap entire run in `(try ... (catch Exception e ...))` |
