#!/bin/bash
# save as run_servers.sh in SyncSpace directory

# Create logs directory if it doesn't exist
mkdir -p logs

# Function to check if port is in use
is_port_used() {
    netstat -an | grep -q "LISTEN.*:$1"
    return $?
}

# Make sure ports are available, or find alternative ports
find_available_port() {
    local base_port=$1
    local port=$base_port
    while is_port_used $port; do
        echo "Port $port is in use, trying next port"
        port=$((port + 1))
    done
    echo $port
}

# Find available replication ports
REPL_PORT1=$(find_available_port 9001)
REPL_PORT2=$(find_available_port 9002)
REPL_PORT3=$(find_available_port 9003)

echo "Using replication ports: $REPL_PORT1, $REPL_PORT2, $REPL_PORT3"

# Start Server 1
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server localhost 12345 $REPL_PORT1 2>&1 | tee logs/server1.log | sed 's/^/[Server 1] /' &
SERVER1_PID=$!
echo "Server 1 started with PID: $SERVER1_PID (Client port: 12345, Repl port: $REPL_PORT1)"

# Wait a bit for the first server to initialize
sleep 5

# Start Server 2
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server localhost 12346 $REPL_PORT2 2>&1 | tee logs/server2.log | sed 's/^/[Server 2] /' &  
SERVER2_PID=$!
echo "Server 2 started with PID: $SERVER2_PID (Client port: 12346, Repl port: $REPL_PORT2)"

# Wait a bit for the second server to initialize
sleep 5

# Start Server 3
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server localhost 12347 $REPL_PORT3 2>&1 | tee logs/server3.log | sed 's/^/[Server 3] /' &
SERVER3_PID=$!
echo "Server 3 started with PID: $SERVER3_PID (Client port: 12347, Repl port: $REPL_PORT3)"

echo "All servers started. Press Ctrl+C to stop all servers."
echo "Server logs are also saved in the logs directory."

# Wait for user to press Ctrl+C
trap 'echo "Shutting down servers..."; kill $SERVER1_PID $SERVER2_PID $SERVER3_PID 2>/dev/null || true; echo "Servers shut down."; exit 0' INT
wait