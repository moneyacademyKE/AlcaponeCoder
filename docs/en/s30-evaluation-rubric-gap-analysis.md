# Rich Hickey Gap Analysis: 5-Point Quality Rubric & Pareto Dominance

This analysis evaluates the features, architecture, and tradeoffs of implementing a 5-point evaluation rubric and Pareto dominance scoring framework in the Hermes harness compared to the benchmark methodology used by **justrach/codedb**.

---

## 1. Feature Set Difference Table

| Feature / Metric | External CodeDB Benchmark | Hermes Harness Benchmark (`run_official_benchmark.py` / `tests/benchmark.clj`) | Gap Description |
| :--- | :--- | :--- | :--- |
| **Quality Scoring Rubric** | Scores every `(task, backend)` pair on a 5-point rubric: (1) File Correct, (2) Function Correct, (3) Snippet Faithful, (4) Explanation Accurate, (5) Completeness. | Basic correctness checks (test assertions) or basic 3-axis LLM fitness score. | **Gap**: Lacks structured 5-point rubric-based grading for code intelligence tasks. |
| **Pareto Front Calculation** | Determines Pareto dominance across three axes: **Quality** (rubric), **Speed** (wall time), and **Efficiency** (tokens). | Tracks duration (wall time) and binary success/failure. | **Gap**: Does not solve for multi-objective optimization or Pareto frontiers. |
| **Comparison Matrix** | Categorizes alternative runs as "dominated" or "Pareto-dominant" relative to baseline. | Reports simple average latency and success rates. | **Gap**: Lacks relative dominance profiling. |
| **Token-per-Quality metric** | Computes token efficiency per score point. | Not tracked. | **Gap**: Missing token usage metrics for tool calls and prompt context. |

---

## 2. Deep Dive: Explanations, Benefits, and Trade-offs

### A. The 5-Point Analytic Rubric
*   **Explanation**: Code intelligence tasks are multi-faceted. A binary pass/fail is too coarse. The 5-point rubric measures:
    1.  **File Correct**: Did the agent open the correct files?
    2.  **Function Correct**: Did the agent inspect/edit the correct symbols?
    3.  **Snippet Faithful**: Are the extracted code fragments syntactically and semantically identical to the codebase?
    4.  **Explanation Accurate**: Does the agent's summary of the issue match the codebase truth?
    5.  **Completeness**: Are all instructions of the task fully resolved?
*   **Benefits**:
    *   Provides granular diagnostic metrics: shows if a failure is due to poor file search (File Correct = 0) vs. poor reasoning (Explanation Accurate = 0).
    *   Allows calibrating LLM-as-judge prompts precisely.
*   **Trade-offs**:
    *   Requires running an LLM evaluator (judge) on every task execution trace, which increases evaluation cost and latency.

### B. Pareto Dominance Evaluation
*   **Explanation**: An agent design is "Pareto-dominant" if no other design matches or beats it on all three axes: Quality, Speed, and Tokens. Fusing tools (like `codedb_context`) might increase prompt tokens but decrease turns (speed) while maintaining quality. We want to identify whether the change lies on the Pareto front.
*   **Benefits**:
    *   Avoids making sub-optimal design choices (e.g. optimizing speed by sacrificing quality, or optimizing quality by bloating token count unnecessarily).
    *   Offers rigorous, multi-axis validation.
*   **Trade-offs**:
    *   Requires collecting and aligning all three metrics across all test runs.

---

## 3. Complexity vs. Utility Matrix

| Component | Complexity | Utility for Agent | Decision |
| :--- | :--- | :--- | :--- |
| **5-Point Rubric Judge** | Medium | Very High (gives granular debugging signals) | **Implement**: Add an LLM-based rubric evaluator in Clojure. |
| **Pareto Dominance Solver** | Low | High (validates design decisions objectively) | **Implement**: Add a Pareto-optimality checker comparing runs. |
| **Token Tracker** | Low | Medium | **Implement**: Capture token statistics from LLM client logs. |

---

## 4. Actionable Recommendations & Implementation Plan

We recommend implementing a native benchmark evaluator in the harness that parses task execution traces, runs an LLM judge on the 5-point rubric, tracks latency and token counts, and outputs a Pareto-optimality report.

### Step-by-Step Execution Plan:
1.  **Red Phase**: Write unit tests in `tests/codedb_test.clj` for the new scoring and Pareto front logic.
2.  **Green Phase**:
    - Write `clj_agents/evaluator.clj` to:
      - Implement the 5-point rubric grader (`score-run`).
      - Implement the Pareto front calculator (`pareto-dominant?`).
    - Update `clj_agents/logger.clj` or tool runners to capture token counts.
3.  **Verify**: Run the test suite and verify that the evaluator correctly scores runs and identifies Pareto-optimal points.
4.  **Git & Docs**: Add learnings and patterns, commit changes.
