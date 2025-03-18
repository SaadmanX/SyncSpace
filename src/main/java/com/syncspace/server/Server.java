package com.syncspace.server;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Server {
    private static final int PORT = 12345; // Client connection port
    private static final int SERVER_PORT = 12346; // Server-to-server communication port
    private static final int RECONNECT_DELAY_MS = 5000; // 5 seconds
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    
    private final UserManager userManager;
    private final List<ClientHandler> connectedClients;
    private final AtomicBoolean actingAsLeader = new AtomicBoolean(false);
    private volatile String leaderIp;  // volatile ensures visibility across threads
    private final String serverIp;
    
    // Thread pools for resource management
    private final ExecutorService connectionThreadPool;
    private final ScheduledExecutorService schedulerThreadPool;
    
    // Server connection management
    // private final List<ServerConnection> serverConnections = new CopyOnWriteArrayList<>();
    // Track followers
    private final List<String> followerIps = new CopyOnWriteArrayList<>();

    // Server sockets
    private ServerSocket clientServerSocket;
    private ServerSocket serverServerSocket;
    private final Object connectLock = new Object();
    private volatile boolean connectingToLeader = false;
    private ScheduledFuture<?> leaderConnectFuture = null;
    private volatile boolean electionInProgress = false;

    /**
     * Constructor for leader server.
     */
    public Server() {
        this(null);
    }

    /**
     * Constructor with leader IP.
     * @param leaderIp IP of leader server (null for leader mode)
     */
    public Server(String leaderIp) {
        this.userManager = new UserManager();
        this.connectedClients = new CopyOnWriteArrayList<>();
        this.actingAsLeader.set(leaderIp == null);
        this.leaderIp = leaderIp;
        
        // Initialize thread pools
        this.connectionThreadPool = Executors.newCachedThreadPool();
        this.schedulerThreadPool = Executors.newScheduledThreadPool(2);

        
        // Get server IP
        String localIp;
        try {
            localIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            localIp = "127.0.0.1";
            logMessage("ERROR: Could not determine server IP, using localhost: " + e.getMessage());
        }
        this.serverIp = localIp;
        
        // Log startup information
        logMessage("======= STARTING SERVER AS " + (isLeader() ? "LEADER" : "FOLLOWER") + " =======");
        logMessage("SERVER IP: " + serverIp);
        if (!isLeader()) {
            logMessage("Command line args: 1 argument provided - Leader IP: " + leaderIp);
        } else {
            logMessage("Command line args: 0 arguments provided");
        }
        
        // Initialize server based on role
        if (isLeader()) {
            initFollowerManagement();
            start();
        } else {
            startLeaderListener();
        }
    }
    
    /**
     * Logs a message with timestamp and server role.
     */
    private void logMessage(String message) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String timestamp = dateFormat.format(new Date());
        String serverType = isLeader() ? "[LEADER]" : "[FOLLOWER]";
        System.out.println(timestamp + " " + serverType + " " + message);
    }

    /**
     * Starts a listener for leader messages (for follower mode).
     */
    private void startLeaderListener() {
        if (isLeader()) return;
        
        connectionThreadPool.execute(() -> {
            try (ServerSocket leaderListenerSocket = new ServerSocket(SERVER_PORT)) {
                logMessage("Follower is listening for leader messages on port " + SERVER_PORT);
                
                while (!Thread.currentThread().isInterrupted() && !isLeader()) {
                    try {
                        Socket leaderSocket = leaderListenerSocket.accept();
                        String incomingIp = leaderSocket.getInetAddress().getHostAddress();
                        
                        if (incomingIp.equals(leaderIp)) {
                            logMessage("Received connection from leader: " + leaderIp);
                            
                            // Handle leader messages
                            handleLeaderMessages(leaderSocket);
                        } else {
                            logMessage("Rejected connection from non-leader server: " + incomingIp);
                            leaderSocket.close();
                        }
                    } catch (IOException e) {
                        if (!leaderListenerSocket.isClosed()) {
                            logMessage("ERROR in leader listener: " + e.getMessage());
                        } else {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logMessage("ERROR starting leader listener: " + e.getMessage());
            }
        });
    }

    /**
     * Handles messages from the leader.
     */
    private void handleLeaderMessages(Socket leaderSocket) {
        connectionThreadPool.execute(() -> {
            try (ObjectInputStream in = new ObjectInputStream(leaderSocket.getInputStream())) {
                leaderSocket.setSoTimeout(30000); 
                while (!Thread.currentThread().isInterrupted() && !isLeader()) {
                    String message = (String) in.readObject();
                    String[] parts = message.split(":", 2);
                    String messageType = parts[0];
                    String content = parts.length > 1 ? parts[1] : "";
                    
                    switch (messageType) {
                        case "PING":
                            // Update the leader timestamp to indicate the leader is alive
                            logMessage("Received PING from leader");
                            break;
                        case "LEADER_INFO":
                            // Update follower list
                            updateFollowerList(content);
                            break;
                        case "LEADERSHIP_CHANGE":
                            // Handle leadership change
                            handleLeadershipChange(content);
                            break;
                        case "ELECTION":
                            handleElectionMessage(content);
                            break;
                        case "ELECTION_RESPONSE":
                            // Reset election timeout - leader is responsive
                            electionInProgress = false;
                            logMessage("Received election response from: " + content);
                            break;
                        default:
                            logMessage("Received unknown message type from leader: " + messageType);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                logMessage("ERROR reading from leader: " + e.getMessage());
                // Trigger reconnection or election if leader connection is lost
                handleLeaderDisconnect();
                
            }
        });
    }

    /**
     * Updates the follower list based on leader information.
     */
    private void updateFollowerList(String leaderInfo) {
        String[] parts = leaderInfo.split("\\|");
        if (parts.length > 1) {
            String currentLeader = parts[0];
            String[] followers = parts[1].split(",");
            
            // Verify leader is the same
            if (!currentLeader.equals(leaderIp)) {
                logMessage("WARNING: Leader IP mismatch in follower list update");
                leaderIp = currentLeader; // Update leader IP if it changed
            }
            
            // Update follower list
            followerIps.clear();
            for (String follower : followers) {
                if (!follower.isEmpty() && !follower.equals(serverIp)) {
                    followerIps.add(follower);
                }
            }
            
            logMessage("Updated follower list from leader: " + followerIps);
        }
    }

    
    /**
     * Manages follower connections and their health.
     */
    private void initFollowerManagement() {
        if (!isLeader()) return;
        
        // Schedule regular pings to followers
        schedulerThreadPool.scheduleAtFixedRate(() -> {
            sendFollowerListToFollowers();
        }, 0, 5, TimeUnit.SECONDS);
        
        // Schedule health checks
        schedulerThreadPool.scheduleAtFixedRate(() -> {
            checkFollowerHealth();
        }, 10, 10, TimeUnit.SECONDS);
    }

    /**
     * Checks follower health and removes unresponsive followers.
     */
    private void checkFollowerHealth() {
        if (!isLeader()) return;
        
        List<String> unhealthyFollowers = new ArrayList<>();
        
        for (String followerIp : followerIps) {
            if (!isFollowerResponsive(followerIp)) {
                unhealthyFollowers.add(followerIp);
            }
        }
        
        // Remove unhealthy followers
        for (String ip : unhealthyFollowers) {
            followerIps.remove(ip);
            logMessage("Removed unresponsive follower: " + ip);
        }
        
        if (!unhealthyFollowers.isEmpty()) {
            sendFollowerListToFollowers();
            sendFollowersToClients();
        }
    }

    /**
     * Checks if a follower is responsive.
     */
    private boolean isFollowerResponsive(String followerIp) {
        // Simple ping implementation - just try to connect
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(followerIp, SERVER_PORT), 2000); // 2-second timeout
            return true;
        } catch (IOException e) {
            logMessage("Follower at " + followerIp + " is unresponsive: " + e.getMessage());
            return false;
        }
    }
    
    private void cancelLeaderConnectTask() {
        synchronized (connectLock) {
            if (leaderConnectFuture != null && !leaderConnectFuture.isCancelled()) {
                leaderConnectFuture.cancel(false);
            }
            connectingToLeader = false;
        }
    }
    
    /**
     * Sends the follower list to all clients.
     */
    public void sendFollowersToClients() {
        if (!isLeader()) return;
        
        logMessage("Sending updated follower list to all clients: " + followerIps);
        
        String followerList = String.join(" * ", followerIps);
        String messageContent = "SERVER_FOLLOWER_LIST:" + followerList;
        
        for (ClientHandler client : connectedClients) {
            client.sendMessage(messageContent);
        }
    }
        
    /**
     * Sends the current list of follower IPs to all followers.
     */
    private void sendFollowerListToFollowers() {
        if (!isLeader()) return;
        
        String followerList = String.join(",", followerIps);
        String leaderInfo = serverIp + "|" + followerList;
        
        logMessage("Sending leader info to all followers: " + leaderInfo);
        
        for (String followerIp : followerIps) {
            sendServerMessage(followerIp, "LEADER_INFO", leaderInfo);
        }
    }

    private void handleLeadershipChange(String newLeaderIp) {
        logMessage("Received leadership change notification. New leader: " + newLeaderIp);
        if (!newLeaderIp.equals(serverIp)) {
            followNewLeader(newLeaderIp);
        }
    }
    
    

    /**
     * Handles leader disconnect by starting reconnection or election.
     */
    private void handleLeaderDisconnect() {
        if (isLeader()) return;
        
        logMessage("Lost connection to leader, initiating reconnection...");
        
        // Cancel any ongoing connection attempts
        cancelLeaderConnectTask();
        
        // Schedule reconnection attempts
        leaderConnectFuture = schedulerThreadPool.scheduleWithFixedDelay(new Runnable() {
            private int attemptCount = 0;
            
            @Override
            public void run() {
                if (isLeader()) {
                    cancelLeaderConnectTask();
                    return;
                }
                
                logMessage("Attempting to reconnect to leader " + leaderIp + 
                        " (Attempt " + (attemptCount + 1) + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                
                if (isServerReachable(leaderIp)) {
                    logMessage("Leader is reachable, reconnecting...");
                    startLeaderListener();
                    cancelLeaderConnectTask();
                } else {
                    attemptCount++;
                    if (attemptCount >= MAX_RECONNECT_ATTEMPTS) {
                        logMessage("Max reconnection attempts reached, starting election...");
                        cancelLeaderConnectTask();
                        startElection();
                    }
                }
            }
        }, 0, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Checks if a server is reachable.
     */
    private boolean isServerReachable(String serverIp) {
        try {
            InetAddress address = InetAddress.getByName(serverIp);
            return address.isReachable(3000); // 3-second timeout
        } catch (IOException e) {
            return false;
        }
    }


    /**
     * Improved election process using Bully algorithm.
     */
    public synchronized void startElection() {
        if (electionInProgress) return;

        electionInProgress = true;
        try {

            logMessage("========== STARTING ELECTION PROCESS (BULLY ALGORITHM) ==========");
            
            // All server IPs including this server
            List<String> allServerIps = new ArrayList<>(followerIps);
            // allServerIps.add(serverIp);
            
            // Sort IPs in descending order
            allServerIps.sort((ip1, ip2) -> ip2.compareTo(ip1));
            
            logMessage("Servers participating in election (in priority order): " + allServerIps);
            
            // First check if there are higher priority servers
            boolean higherPriorityExists = false;
            
            for (String ip : allServerIps) {
                if (ip.compareTo(serverIp) > 0) {
                    // Higher priority server exists, send election message
                    higherPriorityExists = true;
                    if (sendServerMessage(ip, "ELECTION", serverIp)) {
                        logMessage("Sent election message to higher priority server: " + ip);
                    }
                }
            }
            
            if (!higherPriorityExists) {
                // No higher priority server, declare self as leader
                logMessage("No higher priority servers found, declaring self as LEADER");
                becomeLeader();
            } else {
                // Wait for response from higher priority servers
                logMessage("Waiting for higher priority servers to respond...");
                
                // Schedule a timeout to check if higher priority servers respond
                schedulerThreadPool.schedule(() -> {
                    synchronized (Server.this) {
                        if (!isLeader()) {
                            logMessage("No response from higher priority servers, declaring self as LEADER");
                            becomeLeader();
                        }
                    }
                }, 5, TimeUnit.SECONDS);
            } 
        } finally {
            electionInProgress = false;
        }
    }

    /**
     * Handles an election message from another server.
     */
    private void handleElectionMessage(String senderIp) {
        logMessage("Received ELECTION message from " + senderIp);
        
        // Send response to acknowledge the election message
        sendServerMessage(senderIp, "ELECTION_RESPONSE", serverIp);
        
        // Start own election if not already in progress
        if (!connectingToLeader) {
            startElection();
        }
    }
    
    /**
     * Transitions to follower mode with a new leader.
     */
    private synchronized void followNewLeader(String newLeaderIp) {
        actingAsLeader.set(false);
        this.leaderIp = newLeaderIp;
        startLeaderListener();
    }

    /**
     * Transitions this server to leader mode.
     */
    public synchronized void becomeLeader() {
        if (isLeader()) {
            logMessage("Already in leader mode, no transition needed");
            return;
        }
        
        logMessage("TRANSITIONING TO LEADER MODE");
        
        notifyLeadershipChange();
        
        followerIps.remove(serverIp);
        logMessage("Removed self from follower list: " + serverIp);

        actingAsLeader.set(true);
        
        broadcastToAll("SERVER_LEADERSHIP_CHANGE", null);
        
        initFollowerManagement();
        start();
        
        logMessage("Successfully transitioned to leader mode");
    }

    /**
     * Notifies all servers about this server becoming the leader.
     */
    private void notifyLeadershipChange() {
        logMessage("Broadcasting leadership change to all servers");
        
        // Create message with new leader information
        String leadershipMessage = serverIp;
        
        for (String serverIp : followerIps) {
            sendServerMessage(serverIp, "LEADERSHIP_CHANGE", leadershipMessage);
        }
        
        // Update leader information in clients
        for (ClientHandler client : connectedClients) {
            client.sendMessage("SERVER_LEADERSHIP_CHANGE:" + serverIp);
        }
    }

    
    /**
     * Starts the client server.
     */
    public void start() {
        connectionThreadPool.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                clientServerSocket = serverSocket;
                logMessage("Server is listening for clients on port " + PORT);
                
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket socket = serverSocket.accept();
                        logMessage("New client connected");
                        
                        ClientHandler clientHandler = new ClientHandler(socket, userManager, this);
                        connectedClients.add(clientHandler);
                        clientHandler.start();
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            logMessage("ERROR accepting client connection: " + e.getMessage());
                        } else {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logMessage("ERROR starting the server: " + e.getMessage());
            }
        });
    }
    
    /**
     * Removes a client handler from the list.
     */
    public void removeClient(ClientHandler client) {
        connectedClients.remove(client);
        logMessage("Client removed. Active connections: " + connectedClients.size());
    }
    
    /**
     * Broadcasts a message to all clients except the sender.
     */
    public void broadcastToAll(Object message, ClientHandler sender) {
        for (ClientHandler client : connectedClients) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }
    
    /**
     * Checks if this server is the leader.
     */
    public boolean isLeader() {
        return actingAsLeader.get();
    }

    /**
     * Gets the current list of follower IPs.
     */
    public List<String> getFollowerIps() {
        return new ArrayList<>(followerIps);
    }
        
    /**
     * Shuts down the server and releases resources.
     */
    public void shutdown() {
        logMessage("Shutting down server...");
        
        // Send disconnect messages if leader
        if (isLeader()) {
            for (String followerIp : followerIps) {
                sendServerMessage(followerIp, "LEADER_SHUTDOWN", serverIp);
            }
        } 
        for (ClientHandler client : connectedClients) {
            try {
                client.close(); // Make sure ClientHandler has a close method
            } catch (Exception e) {
                logMessage("Error closing client handler: " + e.getMessage());
            }
        }
        connectedClients.clear();
        
        
        // Close server sockets
        try {
            if (clientServerSocket != null && !clientServerSocket.isClosed()) {
                clientServerSocket.close();
            }
        } catch (IOException e) {
            logMessage("Error closing client socket: " + e.getMessage());
        }
        
        try {
            if (serverServerSocket != null && !serverServerSocket.isClosed()) {
                serverServerSocket.close();
            }
        } catch (IOException e) {
            logMessage("Error closing server socket: " + e.getMessage());
        }
        
        // Shutdown thread pools
        schedulerThreadPool.shutdownNow();
        connectionThreadPool.shutdownNow();
        
        try {
            if (!schedulerThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                logMessage("Warning: Scheduler thread pool did not terminate in time");
            }
            if (!connectionThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                logMessage("Warning: Connection thread pool did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logMessage("Shutdown interrupted: " + e.getMessage());
        }
        
        logMessage("Server shutdown complete");
    }

    /**
     * Sends a message to another server (leader or follower).
     * @param targetIp IP address of the target server
     * @param messageType Type of message (e.g., "PING", "LEADER_CHANGE")
     * @param messageContent Content of the message
     * @return true if message was sent successfully, false otherwise
     */
    private boolean sendServerMessage(String targetIp, String messageType, String messageContent) {
        try (Socket socket = new Socket(targetIp, SERVER_PORT);
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
            
            String fullMessage = messageType + ":" + messageContent;
            out.writeObject(fullMessage);
            out.flush();
            
            logMessage("Sent " + messageType + " message to server at " + targetIp);
            return true;
        } catch (IOException e) {
            logMessage("ERROR sending message to server at " + targetIp + ": " + e.getMessage());
            return false;
        }
    }

    
    /**
     * Main method.
     */
    public static void main(String[] args) {
        Server server;
        if (args.length > 0) {
            server = new Server(args[0]);
        } else {
            server = new Server();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    }
}
