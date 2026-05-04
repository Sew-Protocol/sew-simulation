import sys
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

with SimulationClient(port=50051) as client:
    agents = [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
        {'id': 'seller', 'address': '0xseller', 'strategy': 'honest'},
        {'id': 'resolver', 'address': '0xresolver', 'role': 'resolver', 'strategy': 'honest'},
        {'id': 'governance', 'address': '0xgovernance', 'role': 'governance'},
    ]
    
    with managed_session(client, agents, session_id='debug-003', initial_block_time=1000):
        # Create escrow
        r = client.step('debug-003', {'seq': 0, 'time': 1000, 'agent': 'buyer', 'action': 'create_escrow', 
                                          'params': {'token': 'USDC', 'to': '0xseller', 'amount': 5000}})
        print(f'create_escrow: {r["result"]}')
        print(f'  error: {r.get("trace_entry", {}).get("error")}')
        wf_id = r['trace_entry']['extra']['workflow_id']
        
        # Check escrow state
        state = client.get_session_state('debug-003')
        world = state.get('world', {})
        print(f'escrow state: {world.get("escrow_transfers", {}).get(str(wf_id), {}).get("escrow_state")}')
        
        # Raise dispute
        r = client.step('debug-003', {'seq': 1, 'time': 1060, 'agent': 'buyer', 'action': 'raise_dispute', 
                                          'params': {'workflow_id': wf_id}})
        print(f'raise_dispute: {r["result"]}')
        print(f'  error: {r.get("trace_entry", {}).get("error")}')
        
        # Check escrow state after dispute
        state = client.get_session_state('debug-003')
        world = state.get('world', {})
        print(f'escrow state after dispute: {world.get("escrow_transfers", {}).get(str(wf_id), {}).get("escrow_state")}')
