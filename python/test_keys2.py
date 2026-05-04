import sys
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

# Debug pending_fraud_slashes structure
with SimulationClient(port=50051) as client:
    with managed_session(client, [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
        {'id': 'seller', 'address': '0xseller', 'strategy': 'honest'},
        {'id': 'resolver', 'address': '0xresolver', 'role': 'resolver', 'strategy': 'honest'},
        {'id': 'governance', 'address': '0xgovernance', 'role': 'governance'},
    ], session_id='debug-keys-001', initial_block_time=1000) as sid:
        
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
        
        # Execute resolution
        r = client.step(sid, {'seq': 2, 'time': 1120, 'agent': 'resolver', 
                                   'action': 'execute_resolution', 
                                   'params': {'workflow_id': wf_id, 'is_release': False, 'resolution_hash': '0xhash'}})
        print(f'execute_resolution: {r["result"]}')
        
        # Propose fraud slash
        r = client.step(sid, {'seq': 3, 'time': 1180, 'agent': 'governance', 
                                   'action': 'propose_fraud_slash', 
                                   'params': {'workflow_id': wf_id, 'resolver_addr': '0xresolver', 'amount': 2500}})
        print(f'propose_fraud_slash: {r["result"]}')
        
        # CHECK WORLD STATE - pending_fraud_slashes
        state = client.get_session_state(sid)
        world = state.get('world', {})
        pending = world.get('pending_fraud_slashes', {})
        print(f'pending_fraud_slashes: {pending}')
        print(f'pending keys: {list(pending.keys())}')
        if pending:
            for k, v in pending.items():
                print(f'  key={k} (type: {type(k).__name__})')
                print(f'  value: {v}')
        
        # Check resolver_stakes
        print(f'resolver_stakes: {world.get("resolver_stakes")}')
