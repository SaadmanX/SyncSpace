#!/bin/bash
# save as run_servers.sh in SyncSpace directory

# Start Server 1
java -cp target/SyncSpace-1.0-SNAPSHOT.jar com.syncspace.server.Server localhost 12345 9001 &
SERVER1_PID=$!
echo "Server 1 started with PID: $SERVER1_PID"

# Wait a bit for the first server to initialize
sleep 2

# Start Server 2
java -cp target/SyncSpace-1.0-SNAPSHOT.jar com.syncspace.server.Server localhost 12346 9002 &  
SERVER2_PID=$!
echo "Server 2 started with PID: $SERVER2_PID"

# Wait a bit for the second server to initialize
sleep 2

# Start Server 3
java -cp target/SyncSpace-1.0-SNAPSHOT.jar com.syncspace.server.Server localhost 12347 9003 &
SERVER3_PID=$!
echo "Server 3 started with PID: $SERVER3_PID"

echo "All servers started. Press Ctrl+C to stop all servers."

# Wait for user to press Ctrl+C
wait

# This section runs when the user presses Ctrl+C
echo "Shutting down servers..."
kill $SERVER1_PID $SERVER2_PID $SERVER3_PID
echo "Servers shut down."