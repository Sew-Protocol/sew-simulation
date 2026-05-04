import sys, json
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

with SimulationClient(port=50051) as client:
    with managed_session(client, [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
    ], session_id='test-full-001', initial_block_time=1000) as sid:
        
        # Create escrow
        r = client.step(sid, {'seq': 0, 'time': 1000, 'agent': 'buyer', 'action': 'create_escrow',
                                   'params': {'token': 'USDC', 'to': '0xseller', 'amount': 5000}})
        print(f'create_escrow: {r["result"]}')
        wf_id = r['trace_entry']['extra']['workflow_id']
        print(f'wf_id: {wf_id} (type: {type(wf_id).__name__})')
        
        # Get FULL world state
        state = client.get_session_state(sid)
        world = state.get('world', {})
        print(f'World keys: {list(world.keys())}')
        
        # Check escrow-transfers
        et = world.get('escrow_transfers', {})
        print(f'escrow_transfers type: {type(et).__name__}')
        print(f'escrow_transfers keys: {list(et.keys())}')
        if et:
            key = list(et.keys())[0]
            print(f'First key: {key} (type: {type(key).__name__})')
            print(f'escrow_transfers[{key}]: {et.get(key)}')
        
        # Try raise_dispute with int wf_id
        r = client.step(sid, {'seq': 1, 'time': 1060, 'agent': 'buyer', 'action': 'raise_dispute',
                                   'params': {'workflow_id': wf_id}})
        print(f'raise_dispute (int): {r["result"]}')
        print(f'  error: {r.get("trace_entry", {}).get("error")}')
        
        # Print full trace_entry
        print(f'Full trace_entry: {json.dumps(r.get("trace_entry"), indent=2)}')
