import sys
sys.path.append('python')
from sew_sim.grpc_client import SimulationClient, managed_session

# Quick test - create escrow and try withdraw
with SimulationClient(port=50051) as client:
    with managed_session(client, [
        {'id': 'buyer', 'address': '0xbuyer', 'strategy': 'honest'},
        {'id': 'seller', 'address': '0xseller', 'strategy': 'honest'},
        {'id': 'resolver', 'address': '0xresolver', 'role': 'resolver', 'strategy': 'honest'},
    ], session_id='test-final-001', initial_block_time=1000) as sid:
        
        # Register stake
        client.step(sid, {'seq': -1, 'time': 1000, 'agent': 'resolver', 
                         'action': 'register_stake', 'params': {'amount': 10000}})
        
        # Create escrow
        r = client.step(sid, {'seq': 0, 'time': 1000, 'agent': 'buyer', 
                         'action': 'create_escrow',
                         'params': {'token': 'USDC', 'to': '0xseller', 'amount': 5000}})
        print(f'create_escrow: {r["result"]}')
        wf_id = r['trace_entry']['extra']['workflow_id']
        
        # Raise dispute
        r = client.step(sid, {'seq': 1, 'time': 1060, 'agent': 'buyer', 
                         'action': 'raise_dispute', 
                         'params': {'workflow_id': wf_id}})
        print(f'raise_dispute: {r["result"]}')
        
        # Propose fraud slash
        r = client.step(sid, {'seq': 2, 'time': 1120, 'agent': 'governance', 
                         'action': 'propose_fraud_slash', 
                         'params': {'workflow_id': wf_id, 'resolver_addr': '0xresolver', 'amount': 2500}})
        print(f'propose_fraud_slash: {r["result"]}')
        
        # Try to withdraw stake WITH pending slash (should FAIL)
        r = client.step(sid, {'seq': 3, 'time': 1180, 'agent': 'resolver', 
                         'action': 'withdraw_stake', 
                         'params': {'amount': 5000}})
        print(f'withdraw_stake with pending slash: {r["result"]}')
        print(f'  error: {r.get("trace_entry", {}).get("error")}')
        
        if r["result"] == "rejected" and r.get("trace_entry", {}).get("error") == "pending_slash_blocks_withdrawal":
            print("SUCCESS: Pending slash bug is FIXED! Withdrawal correctly blocked.")
        else:
            print("BUG: Withdrawal should be blocked with pending slash!")
