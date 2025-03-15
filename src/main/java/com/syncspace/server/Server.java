package com.syncspace.server;

import com.syncspace.server.replication.LeaderElection;
import com.syncspace.server.replication.ReplicationManager;
import com.syncspace.server.replication.ServerInstance;
import com.syncspace.common.Message;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Server {
    private static final int DEFAULT_PORT = 12345; // Default server port
    private int port;
    private String serverId;
    private boolean isLeader;
    private UserManager userManager;
    private WhiteboardSession whiteboardSession;
    private List<ClientHandler> connectedClients;
    private ReplicationManager replicationManager;
    private LeaderElection leaderElection;
    private List<ServerInstance> peers;
    private ScheduledExecutorService scheduler;
    private ServerSocket serverSocket;
    private boolean running = true;

    public Server(int port, String serverId, List<String> peerAddresses, boolean initialLeader) {
        this.port = port;
        this.serverId = serverId;
        this.isLeader = initialLeader;
        this.userManager = new UserManager();
        this.whiteboardSession = new WhiteboardSession();
        this.connectedClients = new CopyOnWriteArrayList<>();
        this.replicationManager = new ReplicationManager();
        this.peers = new CopyOnWriteArrayList<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        
        // Initialize server instances from peer addresses
        for (String peerAddress : peerAddresses) {
            String[] parts = peerAddress.split(":");
            if (parts.length == 2) {
                String host = parts[0];
                int peerPort = Integer.parseInt(parts[1]);
                
                // Skip if this is our own address
                if (!(host.equals("localhost") || host.equals("127.0.0.1") || 
                     host.equals(getLocalHostname())) || peerPort != this.port) {
                    this.peers.add(new ServerInstance(host, peerPort));
                }
            }
        }
        
        // Initialize leader election
        List<String> serverIds = new ArrayList<>();
        serverIds.add(serverId);
        for (ServerInstance peer : peers) {
            serverIds.add(peer.getAddress() + ":" + peer.getPort());
        }
        this.leaderElection = new LeaderElection(serverIds);
        
        if (initialLeader) {
            this.leaderElection.updateLeader(serverId);
            this.replicationManager.setLeader(new ServerInstance("localhost", port));
        }
    }
    
    private String getLocalHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Server " + serverId + " is listening on port " + port);
            System.out.println("Server is " + (isLeader ? "LEADER" : "FOLLOWER"));
            
            // Start heartbeat if leader, otherwise start listening for heartbeats
            if (isLeader) {
                startHeartbeat();
            } else {
                startLeaderDetector();
            }
            
            // Connect to peers
            connectToPeers();
            
            // Accept client connections
            new Thread(this::acceptClientConnections).start();
            
        } catch (IOException e) {
            System.err.println("Error starting the server: " + e.getMessage());
        }
    }
    
    private void acceptClientConnections() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected");
                
                // Handle client connection in a new thread
                ClientHandler clientHandler = new ClientHandler(socket, userManager, whiteboardSession, this);
                connectedClients.add(clientHandler);
                clientHandler.start();
            } catch (IOException e) {
                if (running) {
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
            }
        }
    }
    
    private void connectToPeers() {
        // Start a thread to connect to each peer
        for (ServerInstance peer : peers) {
            new Thread(() -> {
                try {
                    System.out.println("Connecting to peer at " + peer.getAddress() + ":" + peer.getPort());
                    Socket socket = new Socket(peer.getAddress(), peer.getPort());
                    peer.setSocket(socket);
                    peer.startCommunication();
                    
                    // If I'm the leader, add this peer as a follower
                    if (isLeader) {
                        replicationManager.addFollower(peer);
                    }
                } catch (IOException e) {
                    System.err.println("Failed to connect to peer " + peer.getAddress() + ":" + peer.getPort() + ": " + e.getMessage());
                    // Retry connection after delay
                    scheduler.schedule(() -> connectToPeers(), 5, TimeUnit.SECONDS);
                }
            }).start();
        }
    }
    
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            for (ServerInstance follower : replicationManager.getFollowers()) {
                follower.sendHeartbeat();
            }
        }, 0, 2, TimeUnit.SECONDS);
    }
    
    private void startLeaderDetector() {
        scheduler.scheduleAtFixedRate(() -> {
            // Check if we've received heartbeats from the leader recently
            // If not, initiate leader election
            if (leaderElection.shouldStartElection()) {
                initiateLeaderElection();
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    
    public void initiateLeaderElection() {
        System.out.println("Initiating leader election...");
        // Use bully algorithm - highest ID becomes the leader
        String highestId = serverId;
        for (ServerInstance peer : peers) {
            String peerId = peer.getAddress() + ":" + peer.getPort();
            if (peerId.compareTo(highestId) > 0) {
                highestId = peerId;
            }
        }
        
        // Check if I should be the leader
        if (highestId.equals(serverId)) {
            System.out.println("I am now the leader!");
            becomeLeader();
        } else {
            System.out.println("Server " + highestId + " should be the leader");
            leaderElection.updateLeader(highestId);
            isLeader = false;
        }
        
        // Broadcast the new leader to all peers
        for (ServerInstance peer : peers) {
            peer.sendLeaderUpdate(highestId);
        }
    }
    
    public void becomeLeader() {
        isLeader = true;
        leaderElection.updateLeader(serverId);
        
        // Setup replication
        replicationManager.setLeader(new ServerInstance("localhost", port));
        for (ServerInstance peer : peers) {
            replicationManager.addFollower(peer);
        }
        
        // Start sending heartbeats
        startHeartbeat();
        
        // Broadcast to clients that server is now leader
        broadcastToAllClients(new Message(Message.MessageType.TEXT, 
                "Server has become the leader and will handle all whiteboard updates", "System"));
    }
    
    public void removeClient(ClientHandler client) {
        connectedClients.remove(client);
        System.out.println("Client removed. Active connections: " + connectedClients.size());
    }
    
    public void broadcastToAll(Object message, ClientHandler sender) {
        // If I'm the leader, replicate to followers first
        if (isLeader && message instanceof Message) {
            replicationManager.replicateData(message);
        }
        
        // Then send to all local clients
        broadcastToAllClients(message);
    }
    
    public void broadcastToAllClients(Object message) {
        for (ClientHandler client : connectedClients) {
            client.sendMessage(message);
        }
    }
    
    public void handleReplicatedMessage(Message message) {
        // Forward the message to all local clients
        broadcastToAllClients(message);
    }
    
    public void shutdown() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server socket: " + e.getMessage());
        }
        scheduler.shutdown();
    }
    
    public boolean isLeader() {
        return isLeader;
    }
    
    public void setLeader(boolean leader) {
        isLeader = leader;
        if (leader) {
            startHeartbeat();
        }
    }
    
    public int getPort() {
        return port;
    }
    
    public String getServerId() {
        return serverId;
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String serverId = "server1";
        List<String> peerAddresses = new ArrayList<>();
        boolean initialLeader = false;
        
        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equals("--id") && i + 1 < args.length) {
                serverId = args[i + 1];
                i++;
            } else if (args[i].equals("--peers") && i + 1 < args.length) {
                peerAddresses = Arrays.asList(args[i + 1].split(","));
                i++;
            } else if (args[i].equals("--leader")) {
                initialLeader = true;
            }
        }
        
        Server server = new Server(port, serverId, peerAddresses, initialLeader);
        server.start();
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            server.shutdown();
        }));
    }
}