import os
import subprocess
from pathlib import Path

# The official benchmark task dataset path where Harbor downloads it
HARBOR_DIR = Path("/tmp/harbor-framework")

def run_task(task_name):
    cmd = [
        "uv", "run", "harbor", "run",
        "--dataset", "terminal-bench@2.0",
        "-i", task_name,
        "--agent", "hermes-bb",
        "--model", "tencent/hy3-preview:free",
        "--n-concurrent", "1"
    ]
    
    print(f"Starting evaluation for: {task_name}")
    result = subprocess.run(
        cmd,
        cwd=HARBOR_DIR,
        env=os.environ.copy()
    )
    
    if result.returncode == 0:
        print(f"Task {task_name} finished successfully.")
    else:
        print(f"Task {task_name} failed with exit code {result.returncode}.")

if __name__ == "__main__":
    run_task("regex-chess")
