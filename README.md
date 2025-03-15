# SyncSpace
A Web-Hosted Distributed Collaborative Workspace

## Overview
SyncSpace is a distributed collaborative whiteboard application that allows multiple users to draw, chat, and collaborate in real-time. The system features a fault-tolerant architecture with leader election to ensure high availability.

## Features

- Real-time collaborative drawing
- Text chat functionality
- Automatic failover with leader election
- Support for distributed server deployment
- Seamless client reconnection

## Architecture

SyncSpace uses a leader-follower architecture:
- One server acts as the leader and handles all write operations
- Follower servers replicate the state from the leader
- If the leader fails, a new leader is automatically elected
- Clients can connect to any server and will be redirected to the current leader

## Building

```bash
mvn clean package
```

## Running the Server Cluster

### Option 1: Local Cluster (Multiple processes on same machine)

Run the provided script:
```bash
./start-syncspace-cluster.sh local
```

Or manually start each server:
```bash
# Terminal 1 - Start server instance 1 (initial leader)
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server --port 12345 --id server1 --peers localhost:12346,localhost:12347 --leader

# Terminal 2 - Start server instance 2 (follower)
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server --port 12346 --id server2 --peers localhost:12345,localhost:12347

# Terminal 3 - Start server instance 3 (follower)
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server --port 12347 --id server3 --peers localhost:12345,localhost:12346
```

### Option 2: Distributed Cluster (Multiple machines)

1. Edit the `start-syncspace-cluster.sh` script to update the IP addresses of your servers
2. Run the script on each machine:
```bash
./start-syncspace-cluster.sh distributed
```

## Running the Client

```bash
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.client.WhiteboardClient
```

The client will automatically connect to the server, and you will be prompted to enter a username.

## Testing Failover

To test the leader election and failover capability:
1. Start all three servers
2. Connect clients to the system
3. Kill the leader server (first terminal)
4. Observe as a new leader is elected and clients reconnect automatically

## Protocol

The application uses a simple object-based protocol over TCP/IP:
- Java serialization is used for message passing
- Each message has a type, content, sender ID, and timestamp
- Custom message types handle drawing actions, chat, and server coordination

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request