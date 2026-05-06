import json
import glob
import time
import os
from pathlib import Path

def get_success_count():
    jobs_dir = "/tmp/harbor-framework/jobs"
    result_files = glob.glob(f"{jobs_dir}/*/result.json")
    results_by_task = {}
    for rf in result_files:
        try:
            with open(rf, 'r') as f:
                data = json.load(f)
            stats = data.get("stats", {})
            evals = stats.get("evals", {})
            for eval_name, eval_data in evals.items():
                reward_stats = eval_data.get("reward_stats", {}).get("reward", {})
                for reward_val, tasks in reward_stats.items():
                    reward = float(reward_val)
                    for task_id in tasks:
                        base_task = task_id.split("__")[0]
                        # Take the max reward for a task
                        if base_task not in results_by_task or reward > results_by_task[base_task]:
                            results_by_task[base_task] = reward
        except:
            continue
    
    passed = [t for t, r in results_by_task.items() if r >= 1.0]
    return len(passed), passed

def monitor(target_increase=2):
    initial_count, initial_passed = get_success_count()
    print(f"Initial success count: {initial_count}")
    print(f"Target: {initial_count + target_increase}")
    
    while True:
        current_count, current_passed = get_success_count()
        new_passed = [t for t in current_passed if t not in initial_passed]
        
        if current_count > initial_count:
            print(f"Progress: {current_count} (+{current_count - initial_count})")
            if new_passed:
                print(f"New Successes: {', '.join(new_passed)}")
        
        if current_count >= initial_count + target_increase:
            print("Target reached!")
            break
            
        time.sleep(30)

if __name__ == "__main__":
    monitor(2)
