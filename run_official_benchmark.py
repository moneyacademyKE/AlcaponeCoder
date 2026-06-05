#!/usr/bin/env python3
import os
import subprocess
import time
import argparse
import json
import threading
import uuid
from pathlib import Path
from datetime import datetime


def load_hermes_env() -> dict:
    """Load ~/.hermes/.env and return a dict of key=value pairs."""
    env_file = Path.home() / ".hermes" / ".env"
    result = {}
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            result[k.strip()] = v.strip()
    return result


def build_env() -> dict:
    """Build subprocess environment with API keys resolved from ~/.hermes/.env."""
    env = os.environ.copy()
    hermes_env = load_hermes_env()
    # Inject any missing keys from .env file
    for k, v in hermes_env.items():
        if k not in env or not env[k]:
            env[k] = v
    # If OPENROUTER_API_KEY is unset but OPENAI_API_KEY looks like an OR key, alias it
    if not env.get("OPENROUTER_API_KEY") and env.get("OPENAI_API_KEY", "").startswith("sk-or-"):
        env["OPENROUTER_API_KEY"] = env["OPENAI_API_KEY"]
        print(f"\033[93m[ENV]\033[0m Aliased OPENAI_API_KEY → OPENROUTER_API_KEY (OpenRouter key detected)")
    if not env.get("OPENROUTER_API_KEY") and not env.get("OPENAI_API_KEY"):
        print("\033[91m[WARN]\033[0m Neither OPENROUTER_API_KEY nor OPENAI_API_KEY found. Agent may fail auth!")
    return env

# The official benchmark task dataset path where Harbor downloads it
DATASET_PATH = Path("/tmp/harbor-framework/terminal-bench")
HARBOR_DIR = Path("/tmp/harbor-framework")
HERMES_LOG = Path.home() / ".hermes" / "hermes.log"
LOCK_FILE = Path("/tmp/hermes_benchmark.lock")

class PIDLock:
    def __enter__(self):
        if LOCK_FILE.exists():
            try:
                old_pid = int(LOCK_FILE.read_text().strip())
                # Check if process actually exists
                os.kill(old_pid, 0)
                print(f"\033[91mError: Another instance (PID {old_pid}) is already running.\033[0m")
                exit(1)
            except (ProcessLookupError, ValueError):
                # Process is gone, safe to take over
                pass
        
        LOCK_FILE.write_text(str(os.getpid()))
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if LOCK_FILE.exists():
            LOCK_FILE.unlink()

class Dashboard:
    def __init__(self, total):
        self.total = total
        self.completed = 0
        self.success = 0
        self.failure = 0
        self.start_time = time.time()
        self.current_task = ""
        self.lock = threading.Lock()

    def update(self, task, success=None):
        with self.lock:
            if success is True:
                self.success += 1
                self.completed += 1
            elif success is False:
                self.failure += 1
                self.completed += 1
            self.current_task = task
            self.render()

    def render(self):
        elapsed = time.time() - self.start_time
        avg_time = elapsed / self.completed if self.completed > 0 else 0
        eta = avg_time * (self.total - self.completed)
        
        # ANSI escape to clear screen and show dashboard
        # print("\033[H\033[J", end="") 
        print("\n" + "="*60)
        print(f" HERMES BENCHMARK DASHBOARD | {datetime.now().strftime('%H:%M:%S')}")
        print("="*60)
        print(f" Progress:   [{self.completed}/{self.total}] ({self.completed/self.total*100:.1f}%)")
        print(f" Success:    \033[92m{self.success}\033[0m")
        print(f" Failure:    \033[91m{self.failure}\033[0m")
        print(f" Current:    {self.current_task}")
        print(f" Elapsed:    {elapsed/60:.1f}m")
        print(f" ETA:        {eta/60:.1f}m")
        print("="*60 + "\n")

def tail_logs(stop_event):
    """Tails ~/.hermes/hermes.log and prints interesting events."""
    if not HERMES_LOG.exists():
        return
    
    with open(HERMES_LOG, "r") as f:
        f.seek(0, 2) # Go to end
        while not stop_event.is_set():
            line = f.readline()
            if not line:
                time.sleep(0.1)
                continue
            try:
                entry = json.loads(line)
                # Filter for interesting events
                ev = entry.get("event_type")
                data = entry.get("data", {})
                if ev == "tool_call_start":
                    print(f"  \033[94m[TOOL]\033[0m {data.get('name')}({data.get('args')})")
                elif ev == "llm_call":
                    print(f"  \033[93m[LLM]\033[0m Calling {data.get('model')} (Attempt {data.get('attempt')})")
                elif ev == "compaction_triggered":
                    print(f"  \033[95m[COMP]\033[0m Context compaction triggered ({data.get('chars')} chars)")
                elif ev == "error":
                    print(f"  \033[91m[ERR]\033[0m {data.get('message')}")
            except:
                pass

def ping_model(model_id, env: dict) -> bool:
    print(f"Pinging model {model_id}...")
    try:
        # Use our scratch ping test script (needs --classpath to find config/llm namespaces)
        res = subprocess.run(
            ["bb", "--classpath", "clj_agents", "scratch/ping_test.clj"],
            cwd=Path.cwd(),
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
        )
        output = (res.stdout + res.stderr).strip()
        if res.returncode == 0:
            print(f"\033[92mModel is ALIVE.\033[0m {output}")
            return True
        else:
            print(f"\033[91mModel ping FAILED:\033[0m\n{output}")
            return False
    except subprocess.TimeoutExpired:
        print("\033[91mModel ping timed out after 30s.\033[0m")
        return False
    except Exception as e:
        print(f"Error pinging model: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Enhanced Harbor benchmark runner with dashboard.")
    parser.add_argument("--delay", type=int, default=10, help="Delay in seconds between tasks.")
    parser.add_argument("--model", type=str, default="deepseek/deepseek-v4-flash:free", help="Model to use.")
    parser.add_argument("--agent", type=str, default="hermes-bb", help="Agent adapter.")
    parser.add_argument("--skip-ping", action="store_true", help="Skip initial model ping.")
    parser.add_argument("--reset", action="store_true", help="Reset progress log and start from scratch.")
    
    args = parser.parse_args()

    # Build enriched environment (loads ~/.hermes/.env, resolves API keys)
    env = build_env()

    with PIDLock():
        if not DATASET_PATH.exists():
            print(f"Dataset path {DATASET_PATH} not found.")
            return

        if not args.skip_ping:
            if not ping_model(args.model, env):
                print("Aborting due to model unreachability.")
                return

        tasks = sorted([d.name for d in DATASET_PATH.iterdir() if d.is_dir()])
        log_file = Path("benchmark_progress.log")

        if args.reset and log_file.exists():
            log_file.rename(log_file.with_suffix(".log.bak"))
            print("\033[93m[RESET]\033[0m Progress log backed up and cleared.")

        completed_tasks = set()
        if log_file.exists():
            for raw in log_file.read_text().splitlines():
                raw = raw.strip()
                if not raw:
                    continue
                # Strip inline comment: "task-name  # OK" → "task-name"
                task_name = raw.split("#")[0].strip()
                if task_name:
                    completed_tasks.add(task_name)

        remaining = [t for t in tasks if t not in completed_tasks]
        print(f"Tasks: {len(tasks)} total, {len(completed_tasks)} already done, {len(remaining)} remaining.")

        db = Dashboard(len(tasks))
        # Pre-populate dashboard with completed tasks
        for t in completed_tasks:
            db.update(t, success=True)

        stop_logs = threading.Event()
        log_thread = threading.Thread(target=tail_logs, args=(stop_logs,), daemon=True)
        log_thread.start()

        try:
            for i, task in enumerate(tasks, 1):
                if task in completed_tasks:
                    continue

                db.update(task)

                trace_id = str(uuid.uuid4())
                task_env = env.copy()
                task_env["HARBOR_TRACE_ID"] = trace_id
                task_env["HARBOR_AGENT_DIR"] = str(Path.cwd().resolve())

                cmd = [
                    "uv", "run", "harbor", "run",
                    "--dataset", "terminal-bench@2.0",
                    "-i", task,
                    "--agent", args.agent,
                    "--model", args.model,
                    "--n-concurrent", "1",
                    # Give 3× the default setup timeout (3 × 6min = 18min) for heavy images
                    "--agent-setup-timeout-multiplier", "3",
                ]
                # Explicitly pass API keys into the agent environment via Harbor's --ae flag
                if task_env.get("OPENROUTER_API_KEY"):
                    cmd += ["--ae", f"OPENROUTER_API_KEY={task_env['OPENROUTER_API_KEY']}"]
                if task_env.get("OPENAI_API_KEY"):
                    cmd += ["--ae", f"OPENAI_API_KEY={task_env['OPENAI_API_KEY']}"]

                try:
                    result = subprocess.run(cmd, cwd=HARBOR_DIR, env=task_env)
                    success = (result.returncode == 0)
                    db.update(task, success=success)

                    # Only log completed tasks (success OR failure — to avoid retrying crashed tasks)
                    with open(log_file, "a") as f:
                        status_marker = "OK" if success else "FAIL"
                        f.write(f"{task}  # {status_marker}\n")

                except Exception as e:
                    print(f"Error running task {task}: {e}")
                    db.update(task, success=False)

                if i < len(tasks):
                    time.sleep(args.delay)
        except KeyboardInterrupt:
            print("\nInterrupted. Progress saved.")
        finally:
            stop_logs.set()
            log_thread.join(timeout=1)


if __name__ == "__main__":
    main()
