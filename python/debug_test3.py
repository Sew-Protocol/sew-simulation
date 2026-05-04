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
    
    with managed_session(client, agents, session_id='debug-006', initial_block_time=1000):
        # Create escrow
        r = client.step('debug-006', {'seq': 0, 'time': 1000, 'agent': 'buyer', 'action': 'create_escrow',
                                           'params': {'token': 'USDC', 'to': '0xseller', 'amount': 5000}})
        print(f'create_escrow: {r["result"]}')
        wf_id = r['trace_entry']['extra']['workflow_id']
        print(f'  wf_id: {wf_id} (type: {type(wf_id).__name__})')
        
        # Check world state keys
        state = client.get_session_state('debug-006')
        world = state.get('world', {})
        keys = list(world.get("escrow_transfers", {}).keys())
        print(f'  escrow_transfers keys: {keys}')
        
        # Try raise_dispute with STRING workflow_id
        r = client.step('debug-006', {'seq': 1, 'time': 1060, 'agent': 'buyer', 'action': 'raise_dispute',
                                           'params': {'workflow_id': str(wf_id)}})
        print(f'raise_dispute with str ID: {r["result"]}')
        print(f'  error: {r.get("trace_entry", {}).get("error")}')
        
        # Check escrow state
        state = client.get_session_state('debug-006')
        world = state.get('world', {})
        et = world.get("escrow_transfers", {}).get(str(wf_id), {})
        print(f'  escrow state after dispute: {et.get("escrow_state")}')
