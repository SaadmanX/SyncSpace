package com.syncspace.server.replication;

import com.syncspace.common.Message;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class ReplicationManager {
    private static final Logger logger = Logger.getLogger(ReplicationManager.class.getName());
    private final List<ServerInstance> followers;
    private ServerInstance leader;
    private final ConcurrentHashMap<String, Object> stateStore;
    private final String serverAddress;
    private final int serverPort;
    private int replicationPort;
    private boolean isLeader = false;
    private ServerSocket replicationServerSocket;
    private Thread replicationServerThread;
    private LeaderElection leaderElection;

    public ReplicationManager(String serverAddress, int serverPort, int replicationPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.replicationPort = replicationPort;
        this.followers = new CopyOnWriteArrayList<>();
        this.stateStore = new ConcurrentHashMap<>();
    }

    public void start() {
        try {
            // Start the replication server thread
            startReplicationServer();
            
            // Initialize leader election
            leaderElection = new LeaderElection(getServerList());
            
            // Try to connect to existing servers
            connectToExistingServers();
        } catch (Exception e) {
            logger.severe("Error starting replication manager: " + e.getMessage());
        }
    }

    // Add this method to ReplicationManager.java
    private boolean isPortInUse(int port) {
        try (Socket socket = new Socket("localhost", port)) {
            // If we can connect, port is in use
            return true;
        } catch (IOException e) {
            // Connection refused, port is available
            return false;
        }
    }
        
    private void startReplicationServer() {
        try {
            // Check if port is already in use before attempting to bind
            if (isPortInUse(replicationPort)) {
                logger.warning("Port " + replicationPort + " is already in use, trying alternative port");
                tryAlternativePort();
                return;
            }

            // Try to close any existing socket before creating a new one
            if (replicationServerSocket != null && !replicationServerSocket.isClosed()) {
                try {
                    replicationServerSocket.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
            
            replicationServerSocket = new ServerSocket(replicationPort);
            logger.info("Replication server started on port " + replicationPort);
            
            replicationServerThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted() && !replicationServerSocket.isClosed()) {
                    try {
                        Socket socket = replicationServerSocket.accept();
                        handleReplicationConnection(socket);
                    } catch (IOException e) {
                        if (!replicationServerSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                            logger.severe("Error accepting replication connection: " + e.getMessage());
                        }
                        break;
                    }
                }
            });
            replicationServerThread.setDaemon(true);
            replicationServerThread.start();
        } catch (IOException e) {
            logger.severe("Could not start replication server: " + e.getMessage());
            // Try an alternative port if this one is in use
            tryAlternativePort();
        }
    }

    private void tryAlternativePort() {
        try {
            // Try to find an available port
            int alternativePort = replicationPort + 100;
            int maxAttempts = 5;
            int attempts = 0;
            
            while (attempts < maxAttempts) {
                if (!isPortInUse(alternativePort)) {
                    break;
                }
                alternativePort += 10;
                attempts++;
            }
            
            if (attempts >= maxAttempts) {
                logger.severe("Could not find an available port after " + maxAttempts + " attempts");
                return;
            }
            
            logger.info("Using alternative port: " + alternativePort);
            replicationPort = alternativePort;
            replicationServerSocket = new ServerSocket(replicationPort);
            logger.info("Replication server started on alternative port " + replicationPort);
            
            // Same thread code as above
            replicationServerThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted() && !replicationServerSocket.isClosed()) {
                    try {
                        Socket socket = replicationServerSocket.accept();
                        handleReplicationConnection(socket);
                    } catch (IOException e) {
                        if (!replicationServerSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                            logger.severe("Error accepting replication connection: " + e.getMessage());
                        }
                        break;
                    }
                }
            });
            replicationServerThread.setDaemon(true);
            replicationServerThread.start();
        } catch (IOException e) {
            logger.severe("Could not start replication server on alternative port: " + e.getMessage());
        }
    }
    
    private void handleReplicationConnection(Socket socket) {
        new Thread(() -> {
            try {
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                
                // Read server info
                String remoteAddress = (String) in.readObject();
                int remotePort = (int) in.readObject();
                
                ServerInstance remoteServer = new ServerInstance(remoteAddress, remotePort);
                remoteServer.setSocket(socket);
                remoteServer.setOutputStream(out);
                remoteServer.setInputStream(in);
                
                // Determine leader/follower relationship
                if (isLeader) {
                    // We are leader, they are follower
                    addFollower(remoteServer);
                    sendFullState(remoteServer);
                } else {
                    // We are follower until proven otherwise
                    if (leader == null) {
                        // No leader yet, perform election
                        performLeaderElection();
                    }
                }
                
                // Listen for incoming replication data
                listenForReplicationData(remoteServer, in);
                
            } catch (IOException | ClassNotFoundException e) {
                logger.severe("Error handling replication connection: " + e.getMessage());
                handleServerFailure(socket);
            }
        }).start();
    }
    
    private void sendFullState(ServerInstance follower) {
        try {
            ReplicationPacket packet = new ReplicationPacket(ReplicationPacket.Type.FULL_STATE, stateStore);
            follower.sendData(packet);
        } catch (IOException e) {
            logger.warning("Failed to send full state to follower: " + e.getMessage());
            removeFollower(follower);
        }
    }
    
    private void listenForReplicationData(ServerInstance server, ObjectInputStream in) {
        try {
            while (true) {
                Object obj = in.readObject();
                if (obj instanceof ReplicationPacket) {
                    ReplicationPacket packet = (ReplicationPacket) obj;
                    handleReplicationPacket(packet, server);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            logger.warning("Lost connection with server " + server.getAddress());
            handleServerFailure(server.getSocket());
        }
    }
    
    private void handleReplicationPacket(ReplicationPacket packet, ServerInstance server) {
        switch (packet.getType()) {
            case UPDATE:
                // Update single key-value
                stateStore.put(packet.getKey(), packet.getData());
                break;
            case FULL_STATE:
                // Replace entire state
                if (packet.getFullState() != null) {
                    stateStore.clear();
                    stateStore.putAll(packet.getFullState());
                }
                break;
            case HEARTBEAT:
                // Respond to heartbeat
                try {
                    server.sendData(new ReplicationPacket(ReplicationPacket.Type.HEARTBEAT_ACK, null));
                } catch (IOException e) {
                    logger.warning("Failed to respond to heartbeat: " + e.getMessage());
                }
                break;
            case LEADER_ELECTION:
                // Handle leader election
                performLeaderElection();
                break;
        }
    }
    
    private void connectToExistingServers() {
        // Read server list from config and try to connect to each
        List<String> knownServers = getServerList();
        
        for (String serverInfo : knownServers) {
            String[] parts = serverInfo.split(":");
            if (parts.length == 2) {
                String host = parts[0];
                int port = Integer.parseInt(parts[1]);
                
                // Don't connect to self
                if (host.equals(serverAddress) && port == replicationPort) {
                    continue;
                }
                
                try {
                    Socket socket = new Socket(host, port);
                    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                    out.flush();
                    ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                    
                    // Send our server info
                    out.writeObject(serverAddress);
                    out.writeObject(replicationPort);
                    out.flush();
                    
                    // Create server instance for the connected server
                    ServerInstance serverInstance = new ServerInstance(host, port);
                    serverInstance.setSocket(socket);
                    serverInstance.setOutputStream(out);
                    serverInstance.setInputStream(in);
                    
                    // Determine if this is our leader
                    if (leaderElection.getLeader().equals(host + ":" + port)) {
                        this.leader = serverInstance;
                        isLeader = false;
                    } else {
                        addFollower(serverInstance);
                    }
                    
                    // Start listening for replication data
                    listenForReplicationData(serverInstance, in);
                    
                } catch (IOException e) {
                    logger.warning("Failed to connect to server " + host + ":" + port + " - " + e.getMessage());
                }
            }
        }
        
        // If no leader was found, check if we should be leader
        if (leader == null) {
            if (leaderElection.getLeader().equals(serverAddress + ":" + replicationPort)) {
                isLeader = true;
                logger.info("This server is now the leader: " + serverAddress + ":" + replicationPort + 
                " (PID: " + ProcessHandle.current().pid() + ")");            
            }
        }
    }
    
    private List<String> getServerList() {
        // In a real application, this would read from config
        // For demo, return hardcoded list
        List<String> servers = new CopyOnWriteArrayList<>();
        servers.add("localhost:9001");
        servers.add("localhost:9002");
        servers.add("localhost:9003");
        // Add more servers as needed
        return servers;
    }
    
    private void handleServerFailure(Socket socket) {
        // Find which server failed
        ServerInstance failedServer = null;
        
        if (leader != null && leader.getSocket() == socket) {
            failedServer = leader;
            leader = null;
            // Leader failed, start election
            performLeaderElection();
        } else {
            for (ServerInstance follower : followers) {
                if (follower.getSocket() == socket) {
                    failedServer = follower;
                    removeFollower(follower);
                    break;
                }
            }
        }
        
        if (failedServer != null) {
            logger.warning("Server " + failedServer.getAddress() + " has failed");
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // Ignore close errors
            }
        }
    }
    
    private void performLeaderElection() {
        // Update server list in case of failures
        leaderElection = new LeaderElection(getServerList());
        String newLeaderId = leaderElection.getLeader();
        
        if ((serverAddress + ":" + replicationPort).equals(newLeaderId)) {
            // We are the new leader
            isLeader = true;
            leader = null;
            logger.info("This server is now the leader after election" + ProcessHandle.current().pid());
            // Notify followers of leadership change
            for (ServerInstance follower : followers) {
                try {
                    follower.sendData(new ReplicationPacket(ReplicationPacket.Type.LEADER_CHANGED, serverAddress + ":" + replicationPort));
                } catch (IOException e) {
                    logger.warning("Failed to notify follower of leadership change: " + e.getMessage());
                }
            }
        } else {
            // Someone else is leader
            isLeader = false;
            // Try to connect to new leader if not already connected
            boolean alreadyConnected = false;
            
            if (leader != null && (leader.getAddress() + ":" + leader.getPort()).equals(newLeaderId)) {
                alreadyConnected = true;
            } else {
                for (ServerInstance follower : followers) {
                    if ((follower.getAddress() + ":" + follower.getPort()).equals(newLeaderId)) {
                        // Move from followers to leader
                        leader = follower;
                        followers.remove(follower);
                        alreadyConnected = true;
                        break;
                    }
                }
            }
            
            if (!alreadyConnected) {
                // Need to connect to new leader
                String[] parts = newLeaderId.split(":");
                if (parts.length == 2) {
                    String host = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    
                    try {
                        Socket socket = new Socket(host, port);
                        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                        out.flush();
                        ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                        
                        // Send our server info
                        out.writeObject(serverAddress);
                        out.writeObject(replicationPort);
                        out.flush();
                        
                        // Create server instance for the leader
                        ServerInstance leaderInstance = new ServerInstance(host, port);
                        leaderInstance.setSocket(socket);
                        leaderInstance.setOutputStream(out);
                        leaderInstance.setInputStream(in);
                        
                        this.leader = leaderInstance;
                        
                        // Start listening for replication data
                        listenForReplicationData(leaderInstance, in);
                        
                        logger.info("Connected to new leader at " + host + ":" + port);
                    } catch (IOException e) {
                        logger.severe("Failed to connect to new leader " + host + ":" + port + " - " + e.getMessage());
                    }
                }
            }
        }
    }

    public void addFollower(ServerInstance follower) {
        followers.add(follower);
        logger.info("Added follower: " + follower.getAddress() + ":" + follower.getPort());
    }

    public void removeFollower(ServerInstance follower) {
        followers.remove(follower);
        logger.info("Removed follower: " + follower.getAddress() + ":" + follower.getPort());
    }

    public void setLeader(ServerInstance leader) {
        this.leader = leader;
        logger.info("New leader set: " + leader.getAddress() + ":" + leader.getPort());
    }

    public void replicateData(String key, Object data) {
        // Store data locally first
        stateStore.put(key, data);
        
        if (isLeader) {
            logger.info("Replicating data for key: " + key);
            // Create replication packet
            ReplicationPacket packet = new ReplicationPacket(ReplicationPacket.Type.UPDATE, key, data);
            
            // Send to all followers
            for (ServerInstance follower : followers) {
                try {
                    follower.sendData(packet);
                } catch (IOException e) {
                    logger.warning("Failed to send data to follower " + 
                                  follower.getAddress() + ": " + e.getMessage());
                    // Consider removing failed followers or retrying
                    handleFailedReplication(follower);
                }
            }
        } else if (leader != null) {
            // Send to leader for replication
            try {
                ReplicationPacket packet = new ReplicationPacket(ReplicationPacket.Type.UPDATE, key, data);
                leader.sendData(packet);
            } catch (IOException e) {
                logger.warning("Failed to send data to leader: " + e.getMessage());
                // Leader might be down, start election
                leader = null;
                performLeaderElection();
            }
        } else {
            // No leader, start election
            performLeaderElection();
        }
    }

    public void receiveDataFromLeader(ReplicationPacket packet) {
        logger.info("Received data from leader for key: " + packet.getKey());
        // Update local state with data from leader
        stateStore.put(packet.getKey(), packet.getData());
    }
    
    private void handleFailedReplication(ServerInstance follower) {
        // Implement retry logic or remove the follower if it's unreachable
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                Thread.sleep(1000); // Wait before retry
                follower.checkConnection(); // Method to test connection
                return; // Connection is fine, return
            } catch (Exception e) {
                logger.warning("Retry " + (i+1) + " failed for " + follower.getAddress());
            }
        }
        // After max retries, remove the follower
        removeFollower(follower);
    }

    public Object getReplicatedData(String key) {
        return stateStore.get(key);
    }

    public ServerInstance getLeader() {
        return leader;
    }

    public List<ServerInstance> getFollowers() {
        return followers;
    }
    
    public boolean isLeader() {
        return isLeader;
    }
    
    public void shutdown() {
        try {
            // Interrupt the replication server thread
            if (replicationServerThread != null) {
                replicationServerThread.interrupt();
            }
            
            // Close the replication server socket
            if (replicationServerSocket != null && !replicationServerSocket.isClosed()) {
                replicationServerSocket.close();
            }
            
            // Close all connections
            if (leader != null) {
                leader.close();
            }
            
            for (ServerInstance follower : followers) {
                follower.close();
            }
            
            logger.info("Replication manager shutdown complete");
        } catch (IOException e) {
            logger.severe("Error during shutdown: " + e.getMessage());
        }
    }
}

// Enhanced ServerInstance class
class ServerInstance {
    private String address;
    private int port;
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private boolean connected = false;

    public ServerInstance(String address, int port) {
        this.address = address;
        this.port = port;
    }

    public void connect() throws IOException {
        socket = new Socket(address, port);
        outputStream = new ObjectOutputStream(socket.getOutputStream());
        outputStream.flush();
        inputStream = new ObjectInputStream(socket.getInputStream());
        connected = true;
    }
    
    public void sendData(Object data) throws IOException {
        if (!connected && socket == null) {
            connect();
        }
        outputStream.writeObject(data);
        outputStream.flush();
    }
    
    public void checkConnection() throws IOException {
        if (socket == null || socket.isClosed()) {
            connect();
        }
    }

    public String getAddress() {
        return address;
    }
    
    public int getPort() {
        return port;
    }
    
    public void close() {
        try {
            if (outputStream != null) outputStream.close();
            if (inputStream != null) inputStream.close();
            if (socket != null) socket.close();
            connected = false;
        } catch (IOException e) {
            // Log exception
        }
    }

    public Socket getSocket() {
        return socket;
    }

    public void setSocket(Socket socket) {
        this.socket = socket;
        this.connected = socket != null && !socket.isClosed();
    }

    public void setOutputStream(ObjectOutputStream outputStream) {
        this.outputStream = outputStream;
    }

    public void setInputStream(ObjectInputStream inputStream) {
        this.inputStream = inputStream;
    }
}

// Add this class for serializable replication packets
class ReplicationPacket implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum Type {
        UPDATE,
        FULL_STATE,
        HEARTBEAT,
        HEARTBEAT_ACK,
        LEADER_ELECTION,
        LEADER_CHANGED
    }
    
    private final Type type;
    private final String key;
    private final Object data;
    private final Map<String, Object> fullState;
    private final long timestamp;
    
    public ReplicationPacket(Type type, String key, Object data) {
        this.type = type;
        this.key = key;
        this.data = data;
        this.fullState = null;
        this.timestamp = System.currentTimeMillis();
    }
    
    public ReplicationPacket(Type type, Map<String, Object> fullState) {
        this.type = type;
        this.key = null;
        this.data = null;
        this.fullState = fullState;
        this.timestamp = System.currentTimeMillis();
    }
    
    public ReplicationPacket(Type type, Object data) {
        this.type = type;
        this.key = null;
        this.data = data;
        this.fullState = null;
        this.timestamp = System.currentTimeMillis();
    }
    
    public Type getType() {
        return type;
    }
    
    public String getKey() {
        return key;
    }
    
    public Object getData() {
        return data;
    }
    
    public Map<String, Object> getFullState() {
        return fullState;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
}