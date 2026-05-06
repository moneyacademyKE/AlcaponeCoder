#!/usr/bin/env python3
import os
import subprocess
import time
import argparse
from pathlib import Path

# The official benchmark task dataset path where Harbor downloads it
DATASET_PATH = Path("/tmp/harbor-framework/terminal-bench")
HARBOR_DIR = Path("/tmp/harbor-framework")

def main():
    parser = argparse.ArgumentParser(description="Queue Harbor benchmark tasks to avoid API rate limits.")
    parser.add_argument("--delay", type=int, default=60, help="Delay in seconds between tasks.")
    parser.add_argument("--model", type=str, default="meta-llama/llama-3.3-70b-instruct:free", help="Model to use for the benchmark.")
    parser.add_argument("--agent", type=str, default="hermes-bb", help="Agent adapter to use.")
    
    args = parser.parse_args()

    if not DATASET_PATH.exists():
        print(f"Dataset path {DATASET_PATH} not found. Ensure you have run 'harbor datasets download terminal-bench@2.0'.")
        return

    # Get all tasks
    tasks = [d.name for d in DATASET_PATH.iterdir() if d.is_dir()]
    tasks.sort()
    
    total_tasks = len(tasks)
    print(f"Found {total_tasks} tasks to evaluate.")

    log_file = Path("benchmark_progress.log")
    
    # Simple resume mechanism
    completed_tasks = set()
    if log_file.exists():
        with open(log_file, "r") as f:
            completed_tasks = set(line.strip() for line in f if line.strip())

    for i, task in enumerate(tasks, 1):
        if task in completed_tasks:
            print(f"[{i}/{total_tasks}] Skipping already completed task: {task}")
            continue

        print(f"[{i}/{total_tasks}] Starting evaluation for: {task}")
        
        # Construct the Harbor run command
        cmd = [
            "uv", "run", "harbor", "run",
            "--dataset", "terminal-bench@2.0",
            "-i", task,
            "--agent", args.agent,
            "--model", args.model,
            "--n-concurrent", "1"
        ]
        
        start_time = time.time()
        
        try:
            # We use subprocess.run, piping output to the terminal so we can see progress
            result = subprocess.run(
                cmd,
                cwd=HARBOR_DIR,
                env=os.environ.copy()
            )
            
            if result.returncode == 0:
                print(f"Task {task} finished successfully.")
                with open(log_file, "a") as f:
                    f.write(f"{task}\n")
            else:
                print(f"Task {task} failed with exit code {result.returncode}. (It may have failed the test, which is fine, but we note it).")
                # Harbor exits with non-zero if the verifier fails or agent crashes. 
                # We still consider it "completed" so we don't retry it infinitely.
                with open(log_file, "a") as f:
                    f.write(f"{task}\n")
                    
        except KeyboardInterrupt:
            print("\nEvaluation interrupted by user.")
            break
        except Exception as e:
            print(f"Error running task {task}: {e}")
            
        elapsed = time.time() - start_time
        
        if i < total_tasks:
            print(f"Evaluation took {elapsed:.1f}s. Sleeping for {args.delay} seconds to prevent API rate limits...")
            time.sleep(args.delay)

if __name__ == "__main__":
    main()
