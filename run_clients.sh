#!/bin/bash
# save as run_clients.sh in SyncSpace directory

# Start Client 1 - connects to Server 1
java -cp target/SyncSpace-1.0-SNAPSHOT.jar com.syncspace.client.Client localhost 12345 &
CLIENT1_PID=$!
echo "Client 1 started with PID: $CLIENT1_PID"

# Wait a bit 
sleep 1

# Start Client 2 - connects to Server 2
java -cp target/SyncSpace-1.0-SNAPSHOT.jar com.syncspace.client.Client localhost 12346 &
CLIENT2_PID=$!
echo "Client 2 started with PID: $CLIENT2_PID"

# Wait a bit
sleep 1

# Start Client 3 - connects to Server 3
java -cp target/SyncSpace-1.0-SNAPSHOT.jar com.syncspace.client.Client localhost 12347 &
CLIENT3_PID=$!
echo "Client 3 started with PID: $CLIENT3_PID"

echo "All clients started. Press Ctrl+C to stop all clients."

# Wait for user to press Ctrl+C
wait

# This section runs when the user presses Ctrl+C
echo "Shutting down clients..."
kill $CLIENT1_PID $CLIENT2_PID $CLIENT3_PID
echo "Clients shut down."