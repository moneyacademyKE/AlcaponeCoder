[English](./README.md) | [中文](./README-zh.md)

# Hermes Agent — Babashka Port (xharness)

**A production-grade autonomous AI agent implemented in Clojure/Babashka**, designed to run autonomously on Terminal-Bench 2.0 and similar benchmark environments. Built on Rich Hickey's "Simple Made Easy" principles: pure functional core, explicit system maps, and data-as-truth.

---

## What This Is

This is not a tutorial repo. It is a **working agent system** that:

- Runs autonomously inside Docker containers (Harbor/Terminal-Bench benchmark environments)
- Uses Babashka (`bb`) as the Clojure runtime — no JVM startup overhead, native static binary
- Calls any OpenAI-compatible LLM API (OpenRouter, Anthropic, local Ollama, etc.)
- Manages long-term memory, skills, and context compaction across multi-turn tasks
- Recovers from API errors, rate limits, and context overflows without crashing
- Exits `0` in all cases — `NonZeroAgentExitCodeError` is treated as a fatal bug class

---

## Architecture

```text
harbor.clj          ← Entry point. Terminal guard. Always exits 0.
  └─ system.clj     ← System map creation. Threads all tool registrations.
       └─ agent.clj ← Core loop: LLM call → tool dispatch → recur
            ├─ llm.clj          Primary / fallback / auxiliary model tiers
            ├─ registry.clj     Pure map tool registry (no global atoms)
            ├─ compression.clj  Proactive context compaction at 25k chars
            ├─ recovery.clj     Error classification + jittered backoff
            ├─ memory.clj       ~/.hermes/MEMORY.md + USER.md persistence
            ├─ prompt.clj       System prompt assembly (SOUL.md, plan, memory, skills)
            ├─ skill.clj        Skill index + create/edit/delete
            ├─ store.clj        Session persistence (state.json)
            ├─ logger.clj       Structured JSON logging → ~/.hermes/hermes.log
            ├─ permissions.clj  Dangerous command detection + headless bypass
            ├─ hooks.clj        Lifecycle hooks (pre/post tool call)
            ├─ reviewer.clj     Background skill extraction thread
            └─ tools/
                 ├─ terminal.clj      Shell command execution via backend
                 ├─ browser.clj       Playwright daemon (4 tools)
                 ├─ patch.clj         Precise file patching
                 ├─ multimedia.clj    Vision + TTS stubs
                 └─ system_tools.clj  set_plan roadmap tool
```

**13 tools registered on startup.** All registration is pure — no global atoms.

---

## Key Design Principles

### 1. Pure System Map (Rich Hickey Certified)

All state flows through an explicit `system` map. No global mutable atoms for tools or config.

```clojure
;; system/create-system threads through all registrations
(-> base
    (memory/register-tools)
    (tools.terminal/register-tools)
    ...)  ;; each fn returns enriched system map
```

### 2. Error-as-Value (No Throws)

Every layer returns `{:status :ok :data ...}` or `{:status :error :reason ...}`. `harbor.clj` wraps the entire run in `try/catch` and **always exits 0**.

### 3. Parallel Tool Dispatch

Independent tool calls within a single turn are dispatched with `pmap`. Shared resources (Browser daemon) are guarded with `(locking p ...)`.

### 4. Model Tiering

| Tier | Key | Default | Purpose |
|------|-----|---------|---------|
| Primary | `:primary` | `minimax/minimax-m2.5:free` | Main reasoning |
| Fallback | `:fallback` | `poolside/laguna-m.1:free` | Auto-switch on 401/402/404 |
| Auxiliary | `:auxiliary` | same as primary | Compaction + memory consolidation |

Switches to fallback after 3 consecutive rate limits on primary.

### 5. Proactive Context Compaction

Triggers at 25,000 chars. Protects first message + 10,000-char tail. Summarizes the middle using the auxiliary model.

---

## Quick Start

### Prerequisites

- [Babashka](https://github.com/babashka/babashka) installed (`brew install borkdude/brew/babashka`)
- An OpenRouter API key (or any OpenAI-compatible endpoint)

### Configure

```sh
cp .env.example ~/.hermes/.env
# Edit ~/.hermes/.env and set OPENROUTER_API_KEY=sk-...
```

### Run locally

```sh
cd xharness
HARBOR_INSTRUCTION="List all files in /tmp" \
HARBOR_MODEL="minimax/minimax-m2.5:free" \
bb --classpath clj_agents clj_agents/harbor.clj
```

Output is JSON: `{"status":"success","response":"..."}` or `{"status":"error","error":...}`.

### Run the test suite

```sh
bb --classpath clj_agents -e "
(require '[clojure.test :as t])
(load-file \"tests/agent_error_test.clj\")
(load-file \"tests/agent_test.clj\")
(load-file \"tests/registry_test.clj\")
(load-file \"tests/s02_test.clj\")
(load-file \"tests/s04_test.clj\")
(load-file \"tests/s05_test.clj\")
(load-file \"tests/s06_test.clj\")
(t/run-tests 'agent-error-test 'agent-test 'registry-test
             's02-test 's04-test 's05-test 's06-test)
"
# → 8 tests, 42 assertions, 0 failures, 0 errors
```

---

## Repository Structure

```text
xharness/
├── clj_agents/          ← Production Clojure source (30 modules)
│   ├── harbor.clj       ← Entry point (run this with bb)
│   ├── system.clj       ← System map lifecycle
│   ├── agent.clj        ← Core agent loop
│   ├── tools/           ← Tool handlers (terminal, browser, patch, ...)
│   ├── SOUL.md          ← Agent identity & methodology
│   └── KNOWLEDGE.md     ← Institutional operational knowledge
├── tests/               ← Babashka test suite (12 files)
├── docs/en/             ← Architecture & module documentation
├── docs/zh/             ← Chinese documentation
├── scripts/             ← Browser daemon (Node.js/Playwright)
├── learnings.md         ← Cumulative lessons from production runs
├── patterns.md          ← Reusable architectural patterns
├── bb-aarch64.tar.gz    ← Babashka binary for ARM64 containers
├── bb-amd64.tar.gz      ← Babashka binary for x86-64 containers
└── .env.example         ← Environment variable template
```

---

## Configuration

Config is loaded from `~/.hermes/config.yaml` (deep-merged over defaults):

```yaml
models:
  primary: "minimax/minimax-m2.5:free"
  fallback: "poolside/laguna-m.1:free"
  auxiliary: "minimax/minimax-m2.5:free"
base-url: "https://openrouter.ai/api/v1"
api-key: "${OPENROUTER_API_KEY}"
agent:
  max_turns: 90
  max_tokens: 4096
compression:
  enabled: true
  threshold_chars: 25000
memory:
  enabled: true
  char_limit: 8000
```

---

## Harbor Benchmark Integration

The agent is packaged as a Harbor `InstalledAgent` in `hermes_bb.py` (in the Harbor framework repo). On benchmark start it:

1. Downloads `bb` static binary (cached in this repo as `bb-aarch64.tar.gz` / `bb-amd64.tar.gz`)
2. Copies `clj_agents/` + `tests/` into the container at `/tmp/hermes-clj/`
3. Runs: `bb --classpath /tmp/hermes-clj/clj_agents /tmp/hermes-clj/clj_agents/harbor.clj`

The agent reads `HARBOR_INSTRUCTION` and `HARBOR_MODEL` environment variables injected by Harbor.

---

## Production Hardening Status

| Component | Status | Notes |
|-----------|--------|-------|
| Startup crash (registry Atom/Map) | ✅ Fixed | Pure system map threading |
| `set_plan` NPE | ✅ Fixed | plan-atom wired through system |
| Budget infinite loop | ✅ Fixed | Value-based decrement |
| Logger CWD fragility | ✅ Fixed | `~/.hermes/hermes.log` |
| Compression config key drift | ✅ Fixed | `:threshold_chars` aligned |
| 401/402/404 model fallback | ✅ Active | 3-strike then switch |
| Context compaction | ✅ Active | 25k char threshold |
| Memory consolidation | ✅ Active | Every 20 turns checkpoint |
| Parallel tool dispatch | ✅ Active | `pmap` + locking |
| Harbor exit 0 guarantee | ✅ Active | `(System/exit 0)` all paths |

---

## Learnings & Patterns

All production discoveries are documented:

- [`learnings.md`](./learnings.md) — 79 numbered learnings from real benchmark runs
- [`patterns.md`](./patterns.md) — 16 reusable architectural patterns with code examples
