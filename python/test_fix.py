import sys
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

with SimulationClient(port=50051) as client:
    with managed_session(client, [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
        {'id': 'seller', 'address': '0xseller', 'strategy': 'honest'},
        {'id': 'resolver', 'address': '0xresolver', 'role': 'resolver', 'strategy': 'honest'},
    ], session_id='test-fix-001', initial_block_time=1000) as sid:
        
        # Create escrow
        r = client.step(sid, {'seq': 0, 'time': 1000, 'agent': 'buyer', 
                                   'action': 'create_escrow',
                                   'params': {'token': 'USDC', 'to': '0xseller', 'amount': 5000}})
        print(f'create_escrow: {r["result"]}')
        wf_id = r['trace_entry']['extra']['workflow_id']
        print(f'wf_id: {wf_id} (type: {type(wf_id).__name__})')
        
        # Try raise_dispute with STRING workflow_id
        r = client.step(sid, {'seq': 1, 'time': 1060, 'agent': 'buyer', 
                                   'action': 'raise_dispute', 
                                   'params': {'workflow_id': str(wf_id)}})
        print(f'raise_dispute with str(wf_id): {r["result"]}')
        print(f'  error: {r.get("trace_entry", {}).get("error")}')
        
        # Check escrow state
        state = client.get_session_state(sid)
        world = state.get('world', {})
        et = world.get('escrow_transfers', {}).get(str(wf_id), {})
        print(f'escrow state: {et.get("escrow_state")}')
