from sew_sim.grpc_client import SimulationClient
from sew_sim.adversarial import AdversarialProber

def run_probe():
    agents = [
        {"id": "buyer", "address": "0x111", "type": "honest"},
        {"id": "seller", "address": "0x222", "type": "honest"}
    ]
    
    # Minimal base scenario
    scenario = {
        "agents": agents,
        "protocol_params": {},
        "initial_block_time": 1000,
        "events": [
            {"seq": 0, "time": 1010, "agent": "buyer", "action": "create_escrow", "params": {"token": "USDC", "to": "0x222", "amount": 1000}}
        ]
    }
    
    with SimulationClient(port=7070) as client:
        prober = AdversarialProber(client, "probe-session")
        # Attempt to withdraw wf_id 0 as seller at each step
        results = prober.probe_withdrawal_window(scenario, "0", "0x222")
        print("Probe Results:", results)

if __name__ == "__main__":
    run_probe()
