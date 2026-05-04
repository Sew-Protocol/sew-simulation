import sys
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

# Test B1 - Withdraw with pending slash
with SimulationClient(port=50051) as client:
    with managed_session(client, [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
        {'id': 'seller', 'address': '0xseller', 'strategy': 'honest'},
        {'id': 'resolver', 'address': '0xresolver', 'role': 'resolver', 'strategy': 'honest'},
        {'id': 'governance', 'address': '0xgovernance', 'role': 'governance'},
    ], session_id='test-b1-final3', initial_block_time=1000) as sid:
        
        # Register stake
        client.step(sid, {'seq': -1, 'time': 1000, 'agent': 'resolver', 
                            'action': 'register_stake', 'params': {'amount': 10000}})
        
        # Create escrow
        r = client.step(sid, {'seq': 0, 'time': 1000, 'agent': 'buyer', 
                             'action': 'create_escrow',
                             'params': {'token': 'USDC', 'to': '0xseller', 'amount': 5000}})
        wf_id = r['trace_entry']['extra']['workflow_id']
        print(f'wf_id: {wf_id} (type: {type(wf_id).__name__})')
        
        # Raise dispute
        r = client.step(sid, {'seq': 1, 'time': 1060, 'agent': 'buyer', 
                             'action': 'raise_dispute', 
                             'params': {'workflow_id': wf_id}})
        print(f'raise_dispute: {r["result"]}')
        
        # Execute resolution (make resolver malicious)
        r = client.step(sid, {'seq': 2, 'time': 1120, 'agent': 'resolver', 
                             'action': 'execute_resolution', 
                             'params': {'workflow_id': wf_id, 'is_release': False, 'resolution_hash': '0xhash'}})
        print(f'execute_resolution: {r["result"]}')
        
        # Propose fraud slash (creates pending slash)
        r = client.step(sid, {'seq': 3, 'time': 1180, 'agent': 'governance', 
                             'action': 'propose_fraud_slash', 
                             'params': {'workflow_id': wf_id, 'resolver_addr': '0xresolver', 'amount': 2500}})
        print(f'propose_fraud_slash: {r["result"]}')
        
        # Check state - is pending slash created?
        state = client.get_session_state(sid)
        world = state.get('world', {})
        print(f'pending_fraud_slashes: {world.get("pending_fraud_slashes")}')
        print(f'resolver_stakes: {world.get("resolver_stakes")}')
        
        # Try to withdraw stake WITH pending slash (should FAIL)
        r = client.step(sid, {'seq': 4, 'time': 1240, 'agent': 'resolver', 
                             'action': 'withdraw_stake', 
                             'params': {'amount': 5000}})
        print(f'withdraw_stake with pending slash: {r["result"]}')
        print(f'  error: {r.get("trace_entry", {}).get("error")}')
