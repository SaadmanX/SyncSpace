package com.syncspace.server.replication;

import com.syncspace.common.Message;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private String serverIdentity;
    private ScheduledExecutorService statusReporter;
    private ScheduledExecutorService heartbeatService;
    private AtomicLong lastHeartbeatResponse = new AtomicLong(0);
    private Map<String, Long> heartbeatResponses = new ConcurrentHashMap<>();

    public ReplicationManager(String serverAddress, int serverPort, int replicationPort) {
        this.serverAddress = serverAddress;
        this.serverPort = serverPort;
        this.replicationPort = replicationPort;
        this.followers = new CopyOnWriteArrayList<>();
        this.stateStore = new ConcurrentHashMap<>();
        this.serverIdentity = "[Server " + serverAddress + ":" + serverPort + ":" + replicationPort + "]";
    }
    
    private void logInfo(String message) {
        logger.info(serverIdentity + " " + message);
    }
    
    private void logWarning(String message) {
        logger.warning(serverIdentity + " " + message);
    }
    
    private void logSevere(String message) {
        logger.severe(serverIdentity + " " + message);
    }

    public void start() {
        try {
            // Start the replication server thread
            startReplicationServer();
            
            // Initialize leader election
            leaderElection = new LeaderElection(getServerList());
            
            // Try to connect to existing servers
            connectToExistingServers();
            
            // Start status reporter
            startStatusReporter();
            
            // Start heartbeat service
            startHeartbeatService();
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
                logWarning("Port " + replicationPort + " is already in use, trying alternative port");
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
            logInfo("Replication server started on port " + replicationPort);
            
            replicationServerThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted() && !replicationServerSocket.isClosed()) {
                    try {
                        Socket socket = replicationServerSocket.accept();
                        handleReplicationConnection(socket);
                    } catch (IOException e) {
                        if (!replicationServerSocket.isClosed() && !Thread.currentThread().isInterrupted()) {
                            logSevere("Error accepting replication connection: " + e.getMessage());
                        }
                        break;
                    }
                }
            });
            replicationServerThread.setDaemon(true);
            replicationServerThread.start();
        } catch (IOException e) {
            logSevere("Could not start replication server: " + e.getMessage());
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
                
                logInfo("Connection established with remote server " + remoteAddress + ":" + remotePort);
                
                // Determine leader/follower relationship
                if (isLeader) {
                    // We are leader, they are follower
                    addFollower(remoteServer);
                    sendFullState(remoteServer);
                } else {
                    // We are follower until proven otherwise
                    if (leader == null) {
                        // No leader yet, perform election
                        logInfo("No leader yet, performing election");
                        performLeaderElection();
                    }
                }
                
                // Listen for incoming replication data
                listenForReplicationData(remoteServer, in);
                
            } catch (IOException | ClassNotFoundException e) {
                logSevere("Error handling replication connection: " + e.getMessage());
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
            logInfo("Listening for replication data from " + server.getAddress() + ":" + server.getPort());
            while (true) {
                Object obj = in.readObject();
                if (obj instanceof ReplicationPacket) {
                    ReplicationPacket packet = (ReplicationPacket) obj;
                    handleReplicationPacket(packet, server);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            logWarning("Lost connection with server " + server.getAddress() + ":" + server.getPort() + " - " + e.getMessage());
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
                    // Send back the time we received it for ping calculation
                    server.sendData(new ReplicationPacket(ReplicationPacket.Type.HEARTBEAT_ACK, 
                                                          System.currentTimeMillis()));
                } catch (IOException e) {
                    logger.warning("Failed to respond to heartbeat: " + e.getMessage());
                }
                break;
            case HEARTBEAT_ACK:
                // Process heartbeat acknowledgment
                if (isLeader) {
                    // Update response time for follower
                    String serverKey = server.getAddress() + ":" + server.getPort();
                    heartbeatResponses.put(serverKey, System.currentTimeMillis());
                } else if (server == leader) {
                    // Update response time for leader
                    lastHeartbeatResponse.set(System.currentTimeMillis());
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
                logInfo("This server is now the leader: " + serverAddress + ":" + replicationPort + 
                " (PID: " + ProcessHandle.current().pid() + ", Client Port: " + serverPort + ")");            
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
            logWarning("Leader has failed, starting election");
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
            logWarning("Server " + failedServer.getAddress() + ":" + failedServer.getPort() + " has failed");
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
            logInfo("This server is now the leader after election - PID: " + ProcessHandle.current().pid() + 
               ", Client Port: " + serverPort + ", Repl Port: " + replicationPort);
            // Notify followers of leadership change
            for (ServerInstance follower : followers) {
                try {
                    logInfo("Notifying follower " + follower.getAddress() + ":" + follower.getPort() + " of leadership change");
                    follower.sendData(new ReplicationPacket(ReplicationPacket.Type.LEADER_CHANGED, serverAddress + ":" + replicationPort));
                } catch (IOException e) {
                    logWarning("Failed to notify follower of leadership change: " + e.getMessage());
                }
            }
        } else {
            // Someone else is leader
            isLeader = false;
            logInfo("Another server is the leader: " + newLeaderId);
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
        logInfo("Added follower: " + follower.getAddress() + ":" + follower.getPort());
    }

    public void removeFollower(ServerInstance follower) {
        followers.remove(follower);
        logInfo("Removed follower: " + follower.getAddress() + ":" + follower.getPort());
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
            // Stop the status reporter
            if (statusReporter != null) {
                statusReporter.shutdownNow();
            }
            
            // Stop the heartbeat service
            if (heartbeatService != null) {
                heartbeatService.shutdownNow();
            }
            
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
            
            for (ServerInstance follower : new ArrayList<>(followers)) {
                follower.close();
            }
            
            logger.info("Replication manager shutdown complete");
        } catch (IOException e) {
            logger.severe("Error during shutdown: " + e.getMessage());
        }
    }
    
    // Add this method to start the status reporter
    private void startStatusReporter() {
        statusReporter = Executors.newSingleThreadScheduledExecutor();
        statusReporter.scheduleAtFixedRate(() -> {
            try {
                printServerStatus();
            } catch (Exception e) {
                logWarning("Error in status reporter: " + e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS);
    }
    
    // Add this method to start the heartbeat service
    private void startHeartbeatService() {
        heartbeatService = Executors.newSingleThreadScheduledExecutor();
        heartbeatService.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeats();
            } catch (Exception e) {
                logWarning("Error in heartbeat service: " + e.getMessage());
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    
    // Add this method to send heartbeats to all connected servers
    private void sendHeartbeats() {
        if (isLeader) {
            // Send heartbeat to all followers
            for (ServerInstance follower : followers) {
                try {
                    long pingTime = System.currentTimeMillis();
                    ReplicationPacket heartbeat = new ReplicationPacket(
                        ReplicationPacket.Type.HEARTBEAT, 
                        "ping", 
                        pingTime
                    );
                    follower.sendData(heartbeat);
                    // Store the time sent for this follower
                    heartbeatResponses.put(follower.getAddress() + ":" + follower.getPort(), -1L); // -1 means waiting for response
                } catch (IOException e) {
                    logWarning("Failed to send heartbeat to follower " + follower.getAddress() + ":" + follower.getPort());
                    // Mark as unreachable
                    heartbeatResponses.put(follower.getAddress() + ":" + follower.getPort(), -2L); // -2 means unreachable
                }
            }
        } else if (leader != null) {
            // Send heartbeat to leader
            try {
                long pingTime = System.currentTimeMillis();
                ReplicationPacket heartbeat = new ReplicationPacket(
                    ReplicationPacket.Type.HEARTBEAT, 
                    "ping", 
                    pingTime
                );
                leader.sendData(heartbeat);
                lastHeartbeatResponse.set(-1L); // -1 means waiting for response
            } catch (IOException e) {
                logWarning("Failed to send heartbeat to leader " + leader.getAddress() + ":" + leader.getPort());
                lastHeartbeatResponse.set(-2L); // -2 means unreachable
            }
        }
    }
    
    // Add this method to print server status
    private void printServerStatus() {
        StringBuilder status = new StringBuilder();
        status.append("\n==================================\n");
        status.append("SERVER STATUS REPORT - PID: ").append(ProcessHandle.current().pid()).append("\n");
        status.append("Server: ").append(serverAddress).append(":")
              .append(serverPort).append(" (Repl port: ").append(replicationPort).append(")\n");
        status.append("Role: ").append(isLeader ? "LEADER" : "FOLLOWER").append("\n");
        
        if (isLeader) {
            status.append("Followers (").append(followers.size()).append("):\n");
            for (ServerInstance follower : followers) {
                long pingTime = heartbeatResponses.getOrDefault(follower.getAddress() + ":" + follower.getPort(), 0L);
                String pingStatus;
                if (pingTime == -1L) {
                    pingStatus = "waiting for response";
                } else if (pingTime == -2L) {
                    pingStatus = "unreachable";
                } else if (pingTime == 0L) {
                    pingStatus = "not pinged yet";
                } else {
                    pingStatus = (System.currentTimeMillis() - pingTime) + "ms";
                }
                
                status.append("  - ").append(follower.getAddress()).append(":")
                      .append(follower.getPort()).append(" (ping: ").append(pingStatus).append(")\n");
            }
        } else {
            if (leader != null) {
                long pingTime = lastHeartbeatResponse.get();
                String pingStatus;
                if (pingTime == -1L) {
                    pingStatus = "waiting for response";
                } else if (pingTime == -2L) {
                    pingStatus = "unreachable";
                } else if (pingTime == 0L) {
                    pingStatus = "not pinged yet";
                } else {
                    pingStatus = (System.currentTimeMillis() - pingTime) + "ms";
                }
                
                status.append("Leader: ").append(leader.getAddress()).append(":")
                      .append(leader.getPort()).append(" (ping: ").append(pingStatus).append(")\n");
            } else {
                status.append("Leader: None (election may be in progress)\n");
            }
        }
        
        String currentLeaderId = leaderElection.getLeader();
        status.append("Election Leader ID: ").append(currentLeaderId).append("\n");
        status.append("State store entries: ").append(stateStore.size()).append("\n");
        status.append("==================================\n");
        
        System.out.println(status.toString());
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
            System.out.println("Some exception: "+e.getMessage());
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