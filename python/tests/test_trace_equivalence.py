import json
import subprocess
import shutil
import pytest
from pathlib import Path
from sew_sim.anvil_runner import AnvilRunner, BUYER_ADDR, RESOLVER_ADDR, DEPLOYER_ADDR


pytestmark = pytest.mark.integration

# ---------------------------------------------------------------------------
# Clojure Replay Helper
# ---------------------------------------------------------------------------

def get_clojure_trace(scenario: dict) -> dict:
    """Run a scenario through Clojure replay.clj and return the full result."""
    scenario_json = json.dumps(scenario)
    clj_script = f"""
    (require '[resolver-sim.contract-model.replay :as replay]
             '[clojure.data.json :as json])
    (let [scenario (json/read-str "{scenario_json.replace('"', '\\"')}" :key-fn keyword)
          result   (replay/replay-scenario scenario)]
        (println "__TRACE_START__")
        (println (replay/result->json-str result))
        (println "__TRACE_END__"))
    """
    result = subprocess.run(
        ['clojure', '-M', '-e', clj_script],
        capture_output=True, text=True, check=True
    )
    
    # Extract between markers
    output = result.stdout
    start_marker = "__TRACE_START__"
    end_marker = "__TRACE_END__"
    
    start_idx = output.find(start_marker)
    end_idx = output.find(end_marker)
    
    if start_idx == -1 or end_idx == -1:
        print("Full output:", output)
        print("Error output:", result.stderr)
        raise RuntimeError("Could not find trace markers in Clojure output")
        
    json_str = output[start_idx + len(start_marker) : end_idx].strip()
    return json.loads(json_str)

# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------

SCENARIO_LIFECYCLE = {
    "scenario-id": "trace-equivalence-lifecycle",
    "schema-version": "1.0",
    "initial-block-time": 1000,
    "agents": [
        {"id": "buyer",    "address": BUYER_ADDR,    "type": "honest"},
        {"id": "seller",   "address": DEPLOYER_ADDR, "type": "honest"},
        {"id": "resolver", "address": RESOLVER_ADDR, "type": "resolver"}
    ],
    "protocol-params": {
        "resolver-fee-bps": 50,
        "appeal-window-duration": 60
    },
    "events": [
        {"seq": 0, "time": 1000, "agent": "buyer", "action": "create_escrow", 
         "params": {"token": "0x0000000000000000000000000000000000000001", 
                    "to": DEPLOYER_ADDR, "amount": 10000},
         "save-wf-as": "wf0"},
        {"seq": 1, "time": 1060, "agent": "buyer", "action": "raise_dispute", 
         "params": {"workflow-id": "wf0"}},
        {"seq": 2, "time": 1120, "agent": "resolver", "action": "execute_resolution", 
         "params": {"workflow-id": "wf0", "is-release": True}},
        {"seq": 3, "time": 1200, "agent": "buyer", "action": "execute_pending_settlement", 
         "params": {"workflow-id": "wf0"}}
    ]
}

# ---------------------------------------------------------------------------
# Test Runner
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("scenario", [SCENARIO_LIFECYCLE])
def test_trace_equivalence(scenario):
    """
    Generate a Clojure trace and verify Anvil matches it step-by-step.
    """
    print(f"\nTesting scenario: {scenario['scenario-id']}")

    if shutil.which("clojure") is None:
        pytest.skip("clojure is not installed or not on PATH")
    if shutil.which("anvil") is None or shutil.which("cast") is None or shutil.which("forge") is None:
        pytest.skip("Foundry tools (anvil/cast/forge) are required for trace equivalence")
    
    # 1. Get reference trace from Clojure
    result = get_clojure_trace(scenario)
    assert result['outcome'] == 'pass', f"Clojure replay failed: {result.get('halt-reason')}"
    trace = result['trace']
    
    # 2. Replay against Anvil
    runner = AnvilRunner()
    runner.start()
    
    try:
        wf_id_map = {} # map sim workflow-id -> EVM workflow-id
        
        for step in trace:
            seq = step['seq']
            action = step['action']
            params = dict(step.get('params', {}))
            
            # Map kebab-case from Clojure to snake_case for AnvilRunner
            if 'workflow-id' in params:
                params['wf_id'] = params.pop('workflow-id')
            if 'is-release' in params:
                params['is_release'] = params.pop('is-release')
            
            # Skip steps that were rejected in Clojure
            if step['result'] != 'ok':
                print(f"  Step {seq}: Skipping rejected action {action}")
                continue
            
            print(f"  Step {seq}: Executing {action}...")
            
            # Map workflow-id alias if present
            if 'workflow-id' in params:
                sim_wf_id = params['workflow-id']
                params['workflow-id'] = wf_id_map.get(sim_wf_id, sim_wf_id)
            
            # Execute on Anvil
            res = runner.execute_action(action, params)
            
            # Capture assigned ID from create_escrow
            if action == 'create_escrow':
                sim_wf_id = step['extra']['workflow-id']
                wf_id_map[sim_wf_id] = res
            
            # 3. Verify state
            # Wait a tiny bit for block timestamp to move (handled by anvil automatically on cast send)
            # but anvil_runner read_evm_state needs the current wf_ids
            current_wf_ids = list(wf_id_map.values())
            
            diff = runner.compare_with_projection(step['projection'], current_wf_ids)
            
            if diff:
                pytest.fail(f"Divergence at step {seq} ({action}):\n{json.dumps(diff, indent=2)}")
                
        print("  ✓ Scenario passed trace equivalence.")
        
    finally:
        runner.stop()

if __name__ == "__main__":
    # If run directly, just run the test
    pytest.main([__file__])
