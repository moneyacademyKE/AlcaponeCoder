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

# The official benchmark task dataset path where Harbor downloads it
DATASET_PATH = Path("/tmp/harbor-framework/terminal-bench")
HARBOR_DIR = Path("/tmp/harbor-framework")
HERMES_LOG = Path.home() / ".hermes" / "hermes.log"

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

def ping_model(model_id):
    print(f"Pinging model {model_id}...")
    try:
        # Use our scratch ping test script
        res = subprocess.run(
            ["bb", "scratch/ping_test.clj"],
            cwd=Path.cwd(),
            capture_output=True,
            text=True
        )
        if res.returncode == 0:
            print("\033[92mModel is ALIVE.\033[0m")
            return True
        else:
            print(f"\033[91mModel ping FAILED:\033[0m {res.stdout}\n{res.stderr}")
            return False
    except Exception as e:
        print(f"Error pinging model: {e}")
        return False

def main():
    parser = argparse.ArgumentParser(description="Enhanced Harbor benchmark runner with dashboard.")
    parser.add_argument("--delay", type=int, default=10, help="Delay in seconds between tasks.")
    parser.add_argument("--model", type=str, default="tencent/hy3-preview:free", help="Model to use.")
    parser.add_argument("--agent", type=str, default="hermes-bb", help="Agent adapter.")
    parser.add_argument("--skip-ping", action="store_true", help="Skip initial model ping.")
    
    args = parser.parse_args()

    if not DATASET_PATH.exists():
        print(f"Dataset path {DATASET_PATH} not found.")
        return

    if not args.skip_ping:
        if not ping_model(args.model):
            print("Aborting due to model unreachability.")
            return

    tasks = sorted([d.name for d in DATASET_PATH.iterdir() if d.is_dir()])
    log_file = Path("benchmark_progress.log")
    completed_tasks = set()
    if log_file.exists():
        completed_tasks = set(line.strip() for line in log_file.read_text().splitlines() if line.strip())

    db = Dashboard(len(tasks))
    for t in completed_tasks:
        db.update(t, success=True) # Assume previous were successful for stats

    stop_logs = threading.Event()
    log_thread = threading.Thread(target=tail_logs, args=(stop_logs,), daemon=True)
    log_thread.start()

    try:
        for i, task in enumerate(tasks, 1):
            if task in completed_tasks:
                continue

            db.update(task)
            
            cmd = [
                "uv", "run", "harbor", "run",
                "--dataset", "terminal-bench@2.0",
                "-i", task,
                "--agent", args.agent,
                "--model", args.model,
                "--n-concurrent", "1"
            ]
            
            start_time = time.time()
            trace_id = str(uuid.uuid4())
            env = os.environ.copy()
            env["HARBOR_TRACE_ID"] = trace_id
            
            try:
                result = subprocess.run(cmd, cwd=HARBOR_DIR, env=env)
                success = (result.returncode == 0)
                db.update(task, success=success)
                
                with open(log_file, "a") as f:
                    f.write(f"{task}\n")
                    
            except Exception as e:
                print(f"Error running task {task}: {e}")
                db.update(task, success=False)
            
            if i < len(tasks):
                time.sleep(args.delay)
    except KeyboardInterrupt:
        print("\nInterrupted.")
    finally:
        stop_logs.set()
        log_thread.join(timeout=1)

if __name__ == "__main__":
    main()
