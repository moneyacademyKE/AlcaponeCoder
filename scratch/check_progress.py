import json
import glob
from pathlib import Path

def aggregate_results():
    jobs_dir = "/tmp/harbor-framework/jobs"
    result_files = glob.glob(f"{jobs_dir}/*/result.json")
    
    total_tasks = 0
    passed_tasks = 0
    failed_tasks = 0
    errored_tasks = 0
    timeouts = 0
    
    results_by_task = {}

    for rf in result_files:
        try:
            with open(rf, 'r') as f:
                data = json.load(f)
                
            stats = data.get("stats", {})
            evals = stats.get("evals", {})
            
            for eval_name, eval_data in evals.items():
                reward_stats = eval_data.get("reward_stats", {}).get("reward", {})
                exception_stats = eval_data.get("exception_stats", {})
                
                for reward_val, tasks in reward_stats.items():
                    reward = float(reward_val)
                    for task_id in tasks:
                        # Harbor task IDs often have a suffix, we strip it if it looks like __XXXXXX
                        base_task = task_id.split("__")[0]
                        
                        # We take the best result for each task across all runs
                        if base_task not in results_by_task or reward > results_by_task[base_task]["reward"]:
                            results_by_task[base_task] = {
                                "reward": reward,
                                "exceptions": []
                            }

                for exc_type, tasks in exception_stats.items():
                    for task_id in tasks:
                        base_task = task_id.split("__")[0]
                        if base_task in results_by_task:
                            results_by_task[base_task]["exceptions"].append(exc_type)

        except Exception as e:
            print(f"Error reading {rf}: {e}")

    exception_counts = {}
    for task, data in results_by_task.items():
        total_tasks += 1
        if data["reward"] >= 1.0:
            passed_tasks += 1
        else:
            failed_tasks += 1
            for exc in data["exceptions"]:
                exception_counts[exc] = exception_counts.get(exc, 0) + 1
            if "AgentTimeoutError" in data["exceptions"]:
                timeouts += 1
            if data["exceptions"]:
                errored_tasks += 1

    print(f"--- Benchmark Progress Summary ---")
    print(f"Total Unique Tasks Run: {total_tasks}")
    print(f"Passed (Reward >= 1.0): {passed_tasks}")
    print(f"Failed: {failed_tasks}")
    print(f"  - with Exceptions: {errored_tasks}")
    print(f"  - with Timeouts: {timeouts}")
    
    if exception_counts:
        print("\nCommon Exceptions:")
        for exc, count in sorted(exception_counts.items(), key=lambda x: x[1], reverse=True):
            print(f"  - {exc}: {count}")
            
    print("\nTasks with NonZeroAgentExitCodeError:")
    for task, data in results_by_task.items():
        if "NonZeroAgentExitCodeError" in data["exceptions"]:
            print(f"  - {task}")

    if total_tasks > 0:
        success_rate = (passed_tasks / total_tasks) * 100
        print(f"Success Rate: {success_rate:.2f}%")
        print(f"Target: 75.00%")
        print(f"Gap: {75.00 - success_rate:.2f}%")
    else:
        print("No tasks found.")

if __name__ == "__main__":
    aggregate_results()
