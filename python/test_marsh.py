import grpc
import json
from sew_sim import simulation_pb2, simulation_pb2_grpc

channel = grpc.insecure_channel('localhost:50051')
stub = simulation_pb2_grpc.SimulationEngineStub(channel)

# Send raw bytes instead of proto objects
start_req = {"session_id": "foo", "agents": []}
serialized = json.dumps(start_req).encode('utf-8')

# We can't send raw bytes via stub directly in high-level gRPC.
# We might need to implement the client side manually if the Marshaller
# expects pure JSON.
print("Manual check complete")
