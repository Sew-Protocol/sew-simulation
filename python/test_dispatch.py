import sys
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

with SimulationClient(port=50051) as client:
    with managed_session(client, [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
    ], session_id='test-dispatch-001', initial_block_time=1000) as sid:
        
        # Test create_escrow
        r = client.step(sid, {'seq': 0, 'time': 1000, 'agent': 'buyer', 
                         'action': 'create_escrow',
                         'params': {'token': 'USDC', 'to': '0xseller', 'amount': 1000}})
        print(f'create_escrow: {r["result"]}')
        if r.get('trace_entry'):
            print(f'  extra: {r["trace_entry"].get("extra")}')
