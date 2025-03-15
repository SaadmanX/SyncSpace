#!/bin/bash

# Script to start a SyncSpace server cluster
# Usage: ./start-syncspace-cluster.sh [local|distributed]

MODE=${1:-local}
JAR_PATH="target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar"

# Check if JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo "Error: JAR file not found at $JAR_PATH"
    echo "Make sure you've built the project with 'mvn clean package'"
    exit 1
fi

# Define server configurations
SERVER1_ID="server1"
SERVER2_ID="server2"
SERVER3_ID="server3"

SERVER1_PORT=12345
SERVER2_PORT=12346
SERVER3_PORT=12347

if [ "$MODE" == "local" ]; then
    # Local mode - All servers on localhost
    SERVER1_HOST="localhost"
    SERVER2_HOST="localhost"
    SERVER3_HOST="localhost"
    
    echo "Starting servers in local mode (all on localhost)"
    
    # Start Server 1 (Leader)
    echo "Starting Server 1 (Leader) on port $SERVER1_PORT"
    java -cp "$JAR_PATH" com.syncspace.server.Server --port $SERVER1_PORT --id $SERVER1_ID \
        --peers "$SERVER2_HOST:$SERVER2_PORT,$SERVER3_HOST:$SERVER3_PORT" --leader > server1.log 2>&1 &
    
    sleep 2
    
    # Start Server 2
    echo "Starting Server 2 on port $SERVER2_PORT"
    java -cp "$JAR_PATH" com.syncspace.server.Server --port $SERVER2_PORT --id $SERVER2_ID \
        --peers "$SERVER1_HOST:$SERVER1_PORT,$SERVER3_HOST:$SERVER3_PORT" > server2.log 2>&1 &
    
    sleep 2
    
    # Start Server 3
    echo "Starting Server 3 on port $SERVER3_PORT"
    java -cp "$JAR_PATH" com.syncspace.server.Server --port $SERVER3_PORT --id $SERVER3_ID \
        --peers "$SERVER1_HOST:$SERVER1_PORT,$SERVER2_HOST:$SERVER2_PORT" > server3.log 2>&1 &
    
elif [ "$MODE" == "distributed" ]; then
    # Distributed mode - Servers on different hosts
    # Update these values with your actual server hostnames or IP addresses
    SERVER1_HOST="10.13.148.125" # Update with actual IP
    SERVER2_HOST="10.13.130.185" # Update with actual IP
    SERVER3_HOST="10.13.156.4" # Update with actual IP
    
    echo "Starting servers in distributed mode (on different hosts)"
    echo "Note: You need to run this script on each host with appropriate parameters"
    
    # Check which server to start based on the hostname
    HOSTNAME=$(hostname)
    
    if [ "$HOSTNAME" == "$SERVER1_HOST" ]; then
        echo "Starting Server 1 (Leader) on $SERVER1_HOST:$SERVER1_PORT"
        java -cp "$JAR_PATH" com.syncspace.server.Server --port $SERVER1_PORT --id $SERVER1_ID \
            --peers "$SERVER2_HOST:$SERVER2_PORT,$SERVER3_HOST:$SERVER3_PORT" --leader > server1.log 2>&1
    
    elif [ "$HOSTNAME" == "$SERVER2_HOST" ]; then
        echo "Starting Server 2 on $SERVER2_HOST:$SERVER2_PORT"
        java -cp "$JAR_PATH" com.syncspace.server.Server --port $SERVER2_PORT --id $SERVER2_ID \
            --peers "$SERVER1_HOST:$SERVER1_PORT,$SERVER3_HOST:$SERVER3_PORT" > server2.log 2>&1
    
    elif [ "$HOSTNAME" == "$SERVER3_HOST" ]; then
        echo "Starting Server 3 on $SERVER3_HOST:$SERVER3_PORT"
        java -cp "$JAR_PATH" com.syncspace.server.Server --port $SERVER3_PORT --id $SERVER3_ID \
            --peers "$SERVER1_HOST:$SERVER1_PORT,$SERVER2_HOST:$SERVER2_PORT" > server3.log 2>&1
    
    else
        echo "Error: Current hostname ($HOSTNAME) doesn't match any of the configured servers"
        echo "Update the script with the correct hostnames/IPs or run in local mode"
        exit 1
    fi
else
    echo "Invalid mode: $MODE"
    echo "Usage: ./start-syncspace-cluster.sh [local|distributed]"
    exit 1
fi

echo "Cluster startup initiated. Check log files for details."