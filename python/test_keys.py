import sys, json
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

with SimulationClient(port=50051) as client:
    with managed_session(client, [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
    ], session_id='test-keys-001', initial_block_time=1000) as sid:
        
        # Create escrow
        r = client.step(sid, {'seq': 0, 'time': 1000, 'agent': 'buyer', 'action': 'create_escrow',
                                   'params': {'token': 'USDC', 'to': '0xseller', 'amount': 5000}})
        print(f'create_escrow: {r["result"]}')
        
        # Get world state
        state = client.get_session_state(sid)
        world = state.get('world', {})
        et = world.get('escrow_transfers', {})
        print(f'escrow_transfers: {et}')
        print(f'keys: {list(et.keys())}')
        if et:
            key = list(et.keys())[0]
            print(f'first key: {key} (type: {type(key).__name__})')
