package com.syncspace.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.syncspace.common.Message;

/**
 * Server implementation for SyncSpace application.
 * Handles client connections, server-to-server communication,
 * and implements leader-follower architecture for redundancy.
 */
public class Server {
    // Network configuration
    private static final int PORT = 12345; // Client connection port
    private static final int SERVER_PORT = 12346; // Server-to-server communication port
    private static final int RECONNECT_DELAY_MS = 1000; // 1 second
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    
    // Server state
    private final UserManager userManager;
    private final List<ClientHandler> connectedClients = new CopyOnWriteArrayList<>();
    private final AtomicBoolean actingAsLeader = new AtomicBoolean(false);
    private volatile String leaderIp;
    private final String serverIp;
    
    // Thread management
    private final ExecutorService taskExecutor;
    private final ScheduledExecutorService scheduledTaskExecutor;
    private Thread connectionReaderThread;
    private Thread messageHandlerThread;
    private volatile boolean running = true;
    
    // Connection management
    private final List<ServerConnection> serverConnections = new CopyOnWriteArrayList<>();
    private final List<String> followerIps = new CopyOnWriteArrayList<>();
    private final BlockingQueue<ConnectionMessage> messageQueue = new LinkedBlockingQueue<>(1000);
    
    // Server sockets
    private ServerSocket clientServerSocket;
    private ServerSocket serverServerSocket;
    private final Object connectLock = new Object();
    private volatile boolean connectingToLeader = false;
    private ScheduledFuture<?> leaderConnectFuture = null;
    private ScheduledFuture<?> leaderHeartbeatFuture = null;
    private static final int HEARTBEAT_INTERVAL_MS = 2000; // 2 seconds
    private static final int HEARTBEAT_TIMEOUT_MS = 6000; // 6 seconds
    private volatile long lastLeaderHeartbeat = 0;


    // Drawing state
    private final List<Message> drawingHistory = new CopyOnWriteArrayList<>();
    
    /**
     * Message container for the connection processing queue.
     */
    private static class ConnectionMessage {
        final ServerConnection connection;
        final Object message;
        
        ConnectionMessage(ServerConnection connection, Object message) {
            this.connection = connection;
            this.message = message;
        }
    }

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
        this.actingAsLeader.set(leaderIp == null);
        this.leaderIp = leaderIp;
        
        // Initialize thread pools
        this.taskExecutor = Executors.newCachedThreadPool();
        this.scheduledTaskExecutor = Executors.newScheduledThreadPool(1);

        // Initialize server IP
        this.serverIp = initializeServerIp();
        
        // Log startup information
        logMessage("======= STARTING SERVER AS " + (isLeader() ? "LEADER" : "FOLLOWER") + " =======");
        logMessage("SERVER IP: " + serverIp);
        if (!isLeader()) {
            logMessage("Command line args: 1 argument provided - Leader IP: " + leaderIp);
        } else {
            logMessage("Command line args: 0 arguments provided");
        }
        
        // Start connection processing threads
        startConnectionThreads();
        
        // Initialize server based on role
        if (isLeader()) {
            startServerToServerListener();
            startClientListener();
        } else {
            connectToLeader();
        }
    }
    
    /**
     * Gets the local server IP address.
     * @return Local IP address string
     */
    private String initializeServerIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            logMessage("ERROR: Could not determine server IP, using localhost: " + e.getMessage());
            return "127.0.0.1";
        }
    }
    
    /**
     * Starts the reader and handler threads for connection processing.
     */
    private void startConnectionThreads() {
        // Start reader thread
        connectionReaderThread = new Thread(this::readConnectionsLoop);
        connectionReaderThread.setName("ConnectionReader");
        connectionReaderThread.start();
        
        // Start handler thread
        messageHandlerThread = new Thread(this::processMessagesLoop);
        messageHandlerThread.setName("MessageHandler");
        messageHandlerThread.start();
        
        logMessage("Connection reader and message handler threads started");
    }
    
    /**
     * Main loop for reading data from connections.
     * Polls all connections for available data and queues messages.
     */
    private void readConnectionsLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Poll each connection for available data
                for (ServerConnection conn : new ArrayList<>(serverConnections)) {
                    try {
                        // First check if socket is closed or connection is invalid
                        if (conn.socket == null || conn.socket.isClosed()) {
                            conn.close();
                            continue;
                        }
                        
                        // Get input stream with null check
                        ObjectInputStream ois = conn.getInputStream();
                        if (ois == null) {
                            // Skip this connection if stream isn't ready
                            continue;
                        }
                        
                        // Check for available data before reading
                        InputStream in = conn.socket.getInputStream();
                        if (in.available() > 0) {
                            Object message = ois.readObject();
                            
                            // Queue the message for processing
                            boolean offered = messageQueue.offer(
                                new ConnectionMessage(conn, message),
                                100, TimeUnit.MILLISECONDS);
                                
                            if (!offered) {
                                logMessage("Warning: Message queue full, dropping message");
                            }
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        logMessage("Error reading from connection: " + e.getMessage());
                        conn.close();
                    }
                }
                
                // Small sleep to prevent CPU spinning
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logMessage("Error in connection reader: " + e.getMessage());
            }
        }
        logMessage("Connection reader thread terminated");
    }
        
    /**
     * Main loop for processing queued messages.
     * Dequeues and handles messages from the message queue.
     */
    private void processMessagesLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // Block until a message is available or timeout
                ConnectionMessage msg = messageQueue.poll(500, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    // Process the message
                    try {
                        msg.connection.handleMessage(msg.message);
                    } catch (Exception e) {
                        logMessage("Error handling message: " + e.getMessage());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logMessage("Error in message handler: " + e.getMessage());
            }
        }
        logMessage("Message handler thread terminated");
    }
    
    /**
     * Logs a message with timestamp and server role.
     */
    private void logMessage(String message) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String timestamp = dateFormat.format(new Date());
        String serverType = isLeader() ? "[LEADER]" : "[FOLLOWER]";
        System.out.println(timestamp + " " + serverType + " " + message);
        
        // Replicate log to followers if this is the leader
        if (isLeader()) {
            replicateLogToFollowers(timestamp + " " + serverType + " " + message);
        }
    }
    
    /**
     * Replicates log message to all followers.
     */
    private void replicateLogToFollowers(String logMessage) {
        if (!isLeader()) return;
        
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            if (conn.getType() == ServerConnectionType.FOLLOWER) {
                conn.sendMessage("LOG:" + logMessage);
            }
        }
    }

    /**
     * Starts heartbeat monitoring of leader
     */
    private void startLeaderHeartbeatMonitor() {
        if (isLeader()) return;
        
        lastLeaderHeartbeat = System.currentTimeMillis();
        
        leaderHeartbeatFuture = scheduledTaskExecutor.scheduleAtFixedRate(() -> {
            if (isLeader()) {
                // Cancel if we've become leader
                cancelHeartbeatMonitor();
                return;
            }
            
            // Check if leader connection exists
            ServerConnection leaderConn = getLeaderConnection();
            if (leaderConn == null) {
                logMessage("Leader connection lost, starting election");
                cancelHeartbeatMonitor();
                startElection();
                return;
            }
            
            // Send heartbeat to leader
            leaderConn.sendMessage("HEARTBEAT");
            
            // Check if we've received a response in the timeout period
            long now = System.currentTimeMillis();
            if (now - lastLeaderHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                logMessage("Leader heartbeat timeout, starting election");
                cancelHeartbeatMonitor();
                leaderConn.close(); // Force close the connection
                startElection();
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the heartbeat monitor
     */
    private void cancelHeartbeatMonitor() {
        if (leaderHeartbeatFuture != null && !leaderHeartbeatFuture.isCancelled()) {
            leaderHeartbeatFuture.cancel(false);
            leaderHeartbeatFuture = null;
        }
    }

    
    /**
     * Starts the server-to-server listener (leader mode).
     */
    private void startServerToServerListener() {
        if (!isLeader()) return;
        
        taskExecutor.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
                serverServerSocket = serverSocket;
                logMessage("Leader server is listening for followers on port " + SERVER_PORT);
                
                while (!Thread.currentThread().isInterrupted() && !serverSocket.isClosed()) {
                    try {
                        logMessage("Waiting for follower connections...");
                        Socket followerSocket = serverSocket.accept();
                        String followerIp = followerSocket.getInetAddress().getHostAddress();
                        logMessage("NEW FOLLOWER CONNECTED! IP: " + followerIp);
                        
                        // Create and register the connection
                        ServerConnection connection = new ServerConnection(
                            followerSocket, followerIp, ServerConnectionType.FOLLOWER);
                        serverConnections.add(connection);
                        followerIps.add(followerIp);
                        sendFollowersToConnections();
                        logServerState();

                        // Initialize the connection
                        connection.start();
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            logMessage("ERROR accepting follower connection: " + e.getMessage());
                        } else {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                logMessage("ERROR in server-to-server listener: " + e.getMessage());
            }
        });
    }
    
    /**
     * Connects to the leader server (follower mode).
     */
    private void connectToLeader() {
        // If already leader or a connection attempt is in progress, return.
        if (isLeader()) return;
        synchronized (connectLock) {
            if (connectingToLeader) return;
            connectingToLeader = true;
        }
        
        followerIps.clear();
        
        // Schedule a repeated connection attempt
        leaderConnectFuture = scheduledTaskExecutor.scheduleWithFixedDelay(new Runnable() {
            private int attemptCount = 0;
            
            @Override
            public void run() {
                // If we're already a leader, cancel further attempts.
                if (isLeader()) {
                    cancelLeaderConnectTask();
                    return;
                }
                
                logMessage("Attempting to connect to leader at " + leaderIp + ":" + SERVER_PORT +
                           " (Attempt " + (attemptCount + 1) + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                
                try {
                    Socket leaderSocket = new Socket(leaderIp, SERVER_PORT);
                    logMessage("CONNECTED TO LEADER SUCCESSFULLY at " + leaderIp);
                    
                    // Remove any existing leader connections
                    for (ServerConnection conn : new ArrayList<>(serverConnections)) {
                        if (conn.getType() == ServerConnectionType.LEADER) {
                            conn.close();
                            serverConnections.remove(conn);
                        }
                    }
                    
                    // Create and register the leader connection
                    ServerConnection connection = new ServerConnection(
                            leaderSocket, leaderIp, ServerConnectionType.LEADER);
                    serverConnections.add(connection);
                    connection.start();
                    
                    // Connection succeeded, cancel further attempts.
                    cancelLeaderConnectTask();
                    startLeaderHeartbeatMonitor();

                } catch (IOException e) {
                    attemptCount++;
                    logMessage("ERROR connecting to leader: " + e.getMessage());
                    if (attemptCount >= MAX_RECONNECT_ATTEMPTS) {
                        logMessage("Maximum reconnection attempts reached. Starting election...");
                        cancelLeaderConnectTask();
                        startElection();
                    }
                    // Otherwise, the scheduled task will try again after the delay.
                }
            }
        }, 0, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Cancels the leader connection task.
     */
    private void cancelLeaderConnectTask() {
        synchronized (connectLock) {
            if (leaderConnectFuture != null && !leaderConnectFuture.isCancelled()) {
                leaderConnectFuture.cancel(false);
            }
            connectingToLeader = false;
        }
        logServerState();
    }
    
    /**
     * Starts the client listener server.
     */
    private void startClientListener() {
        taskExecutor.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                clientServerSocket = serverSocket;
                logMessage("Server is listening for clients on port " + PORT);
                
                while (!Thread.currentThread().isInterrupted() && !serverSocket.isClosed()) {
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
                logMessage("ERROR starting the client server: " + e.getMessage());
            }
        });
    }
    
    /**
     * Sends the follower list to all connections.
     */
    public void sendFollowersToConnections() {
        if (!isLeader()) return;
        
        logMessage("Sending updated follower list to all connections: " + followerIps);
        
        String followerList = String.join(" * ", followerIps);
        String messageContent = "SERVER_FOLLOWER_LIST:" + followerList;
        
        for (ClientHandler client : connectedClients) {
            client.sendMessage(messageContent);
        }

        for (ServerConnection conn: serverConnections) {
            conn.sendMessage(messageContent);
        }
    }
    
    /**
     * Retrieves the leader connection (follower mode).
     */
    private ServerConnection getLeaderConnection() {
        for (ServerConnection conn : serverConnections) {
            if (conn.getType() == ServerConnectionType.LEADER) {
                return conn;
            }
        }
        return null;
    }
    
    /**
     * Starts the election process.
     */
    public synchronized void startElection() {
        logMessage("========== STARTING ELECTION PROCESS ==========");
        
        List<String> allServerIps = new ArrayList<>(followerIps);
        allServerIps.add(serverIp);
        
        logMessage("Servers participating in election: " + allServerIps);
        
        String highestIp = "";
        for (String ip : allServerIps) {
            if (ip.compareTo(highestIp) > 0) {
                highestIp = ip;
            }
        }
        
        logMessage("Election result: Highest IP is " + highestIp);
        
        if (highestIp.equals(serverIp)) {
            logMessage("THIS SERVER WON THE ELECTION!");
            becomeLeader();
        } else {
            logMessage("Another server won the election: " + highestIp);
            followNewLeader(highestIp);
        }
        
        logMessage("========== ELECTION PROCESS COMPLETE ==========");
    }
    
    /**
     * Transitions to follower mode with a new leader.
     */
    private synchronized void followNewLeader(String newLeaderIp) {
        actingAsLeader.set(false);
        this.leaderIp = newLeaderIp;
        connectToLeader();
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
        
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            conn.close();
        }
        serverConnections.clear();
        
        followerIps.remove(serverIp);
        logMessage("Removed self from follower list: " + serverIp);

        actingAsLeader.set(true);
        
        // Notify clients of leadership change
        for (ClientHandler client : connectedClients) {
            client.sendMessage("SERVER_LEADERSHIP_CHANGE");
        }
        
        startServerToServerListener();
        startClientListener();
        
        logMessage("Successfully transitioned to leader mode");
    }
    
    /**
     * Logs the complete state of the server.
     */
    private void logServerState() {
        StringBuilder state = new StringBuilder();
        state.append("\n=============== SERVER STATE ===============\n");
        state.append("Role: ").append(isLeader() ? "LEADER" : "FOLLOWER").append("\n");
        state.append("Server IP: ").append(serverIp).append("\n");
        
        if (isLeader()) {
            state.append("Leader status: This server is the leader\n");
        } else {
            ServerConnection leaderConn = getLeaderConnection();
            state.append("Leader IP: ").append(leaderIp).append("\n");
            state.append("Leader connection: ").append(leaderConn != null ? "CONNECTED" : "DISCONNECTED").append("\n");
        }
        
        state.append("Client connections: ").append(connectedClients.size()).append("\n");
        state.append("Server connections: ").append(serverConnections.size()).append("\n");
        state.append("Message queue size: ").append(messageQueue.size()).append("\n");
        
        state.append("Follower IPs (").append(followerIps.size()).append("):");
        if (followerIps.isEmpty()) {
            state.append(" None\n");
        } else {
            state.append("\n");
            for (String ip : followerIps) {
                state.append("  - ").append(ip).append("\n");
            }
        }
        
        state.append("===========================================");
        logMessage(state.toString());
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
        logMessage("Broadcasting message to all clients: " + message.toString());
        
        // Store drawing actions in history
        if (message instanceof Message) {
            Message msg = (Message) message;
            if (msg.getType() == Message.MessageType.DRAW || 
                msg.getType() == Message.MessageType.CLEAR) {
                drawingHistory.add(msg);
                
                // If CLEAR message, remove all previous drawing actions
                if (msg.getType() == Message.MessageType.CLEAR) {
                    drawingHistory.removeIf(m -> m.getType() == Message.MessageType.DRAW);
                }
                
                // If leader, replicate drawing history to followers
                if (isLeader()) {
                    replicateDrawingToFollowers(msg);
                }
            }
        }
        
        for (ClientHandler client : connectedClients) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }
    
    /**
     * Replicates drawing actions to all followers.
     */
    private void replicateDrawingToFollowers(Message drawingMessage) {
        if (!isLeader()) return;
        
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            if (conn.getType() == ServerConnectionType.FOLLOWER) {
                conn.sendMessage("DRAWING:" + drawingMessage);
            }
        }
    }
    
    /**
     * Sends the entire drawing history to a specific client.
     */
    public void sendDrawingHistoryToClient(ClientHandler client) {
        logMessage("Sending drawing history to new client. History size: " + drawingHistory.size());
        
        // First, send a message to clear any existing content
        client.sendMessage(new Message(Message.MessageType.CLEAR, "CLEAR_ALL", "SERVER"));
        
        // Then send all drawing actions
        for (Message drawAction : drawingHistory) {
            if (drawAction.getType() == Message.MessageType.DRAW) {
                client.sendMessage(drawAction);
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
     * Gets the leader output stream (follower mode).
     */
    public ObjectOutputStream getLeaderOutputStream() {
        ServerConnection leaderConn = getLeaderConnection();
        return leaderConn != null ? leaderConn.getOutputStream() : null;
    }
    
    /**
     * Shuts down the server and releases resources.
     */
    public void shutdown() {
        logMessage("Shutting down server...");
        
        // If leader, notify followers before shutting down
        if (isLeader()) {
            logMessage("Notifying followers of shutdown");
            for (ServerConnection conn : new ArrayList<>(serverConnections)) {
                if (conn.getType() == ServerConnectionType.FOLLOWER) {
                    conn.sendMessage("LEADER_SHUTDOWN");
                }
            }
            // Give followers time to process the shutdown message
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Signal threads to stop
        running = false;
        
        // Cancel any scheduled tasks
        if (leaderConnectFuture != null) {
            leaderConnectFuture.cancel(true);
        }
        
        if (leaderHeartbeatFuture != null) {
            leaderHeartbeatFuture.cancel(true);
        }
        
        // Interrupt and wait for threads to finish
        if (connectionReaderThread != null) {
            connectionReaderThread.interrupt();
            try {
                connectionReaderThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (messageHandlerThread != null) {
            messageHandlerThread.interrupt();
            try {
                messageHandlerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Close all connections
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            conn.close();
        }
        serverConnections.clear();
        
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
        scheduledTaskExecutor.shutdownNow();
        taskExecutor.shutdownNow();
        
        logMessage("Server shutdown complete");
    }

    /**
     * Server connection types.
     */
    private enum ServerConnectionType {
        LEADER,
        FOLLOWER
    }
    
    /**
     * Handles server-to-server connections.
     */
    private class ServerConnection {
        private final Socket socket;
        private final String remoteIp;
        private final ServerConnectionType type;
        private ObjectOutputStream outputStream;
        private ObjectInputStream inputStream;
        
        /**
         * Creates a new server connection.
         * 
         * @param socket The socket for the connection
         * @param remoteIp The remote IP address
         * @param type The connection type (LEADER or FOLLOWER)
         */
        public ServerConnection(Socket socket, String remoteIp, ServerConnectionType type) {
            this.socket = socket;
            this.remoteIp = remoteIp;
            this.type = type;
        }
        
        /**
         * Initializes the connection streams.
         * No thread is created - reading is handled by the reader thread.
         */
        public synchronized void start() {
            try {
                outputStream = new ObjectOutputStream(socket.getOutputStream());
                outputStream.flush();
                inputStream = new ObjectInputStream(socket.getInputStream());
                
                logMessage("Communication streams established with " + type.name().toLowerCase() + ": " + remoteIp);
            } catch (IOException e) {
                String role = type == ServerConnectionType.LEADER ? "leader" : "follower";
                logMessage("Error setting up connection to " + role + ": " + e.getMessage());
                close();
            }
        }
        
        /**
         * Updates the follower list from the leader's message.
         */
        private void updateFollowerList(String followerListString) {
            // Clear the current follower list
            followerIps.clear();
            
            // Parse the new list (format: "ip1 * ip2 * ip3")
            if (followerListString != null && !followerListString.isEmpty()) {
                String[] ips = followerListString.split("\\s*\\*\\s*");
                for (String ip : ips) {
                    if (!ip.trim().isEmpty()) {
                        followerIps.add(ip.trim());
                    }
                }
            }
            logMessage("Updated follower list from leader: " + followerIps);
        }        
    
        /**
         * Handles incoming messages.
         */
        private void handleMessage(Object message) {
            if (message instanceof String) {
                String stringMessage = (String) message;
                if (stringMessage.equals("HEARTBEAT")) {
                    if (type == ServerConnectionType.LEADER) {
                        // Follower received heartbeat from leader
                        lastLeaderHeartbeat = System.currentTimeMillis();
                        sendMessage("HEARTBEAT_ACK");
                    } else if (type == ServerConnectionType.FOLLOWER) {
                        // Leader received heartbeat from follower
                        // Just acknowledge
                        sendMessage("HEARTBEAT_ACK");
                    }
                } else if (stringMessage.equals("HEARTBEAT_ACK")) {
                    // Record heartbeat response
                    lastLeaderHeartbeat = System.currentTimeMillis();
                }
                else if (stringMessage.startsWith("LOG:")) {
                    // This is a log message from the leader - print it directly to console
                    String log = stringMessage.substring(4);
                    System.out.println("FROM LEADER: " + log);
                    logServerState();
                } 
                else if (stringMessage.startsWith("SERVER_FOLLOWER_LIST:")) {
                    // Extract the follower list part
                    String followerListPart = stringMessage.substring("SERVER_FOLLOWER_LIST:".length());
                    updateFollowerList(followerListPart);
                    logServerState();
                }
                else if (stringMessage.startsWith("DRAWING:")) {
                    // Handle drawing replication from leader
                    handleDrawingMessage(stringMessage);
                }
                else if (stringMessage.startsWith("TEXT")) {
                    // Handle text message - not implemented in this code
                } 
                else if (stringMessage.equals("LEADER_SHUTDOWN")) {
                    logMessage("Leader notified of orderly shutdown, starting election");
                    if (type == ServerConnectionType.LEADER) {
                        close();
                        startElection();
                    }
                }
                else {
                    // Other string messages
                    logMessage("Received message: " + stringMessage);
                }
            } else {
                logMessage("Received unknown message type: " + message.getClass().getName());
            }
        }
        
        /**
         * Handles drawing replication messages from the leader.
         */
        private void handleDrawingMessage(String stringMessage) {
            try {
                String drawingPart = stringMessage.substring(8);
                Object drawObj = deserializeDrawingMessage(drawingPart);
                if (drawObj instanceof Message) {
                    Message drawMsg = (Message) drawObj;
                    if (drawMsg.getType() == Message.MessageType.DRAW || 
                        drawMsg.getType() == Message.MessageType.CLEAR) {
                        
                        // Add to local drawing history if not already there
                        if (!drawingHistory.contains(drawMsg)) {
                            drawingHistory.add(drawMsg);
                        }
                        
                        // If it's a clear message, remove all drawing actions
                        if (drawMsg.getType() == Message.MessageType.CLEAR) {
                            drawingHistory.removeIf(m -> m.getType() == Message.MessageType.DRAW);
                        }
                        
                        // Forward to connected clients
                        for (ClientHandler client : connectedClients) {
                            client.sendMessage(drawMsg);
                        }
                    }
                }
            } catch (Exception e) {
                logMessage("Error processing drawing message: " + e.getMessage());
            }
        }
        
        /**
         * Sends a message to the remote server.
         */
        public void sendMessage(Object message) {
            if (outputStream != null) {
                synchronized (outputStream) {
                    try {
                        outputStream.writeObject(message);
                        outputStream.flush();
                    } catch (IOException e) {
                        logMessage("Error sending message: " + e.getMessage());
                        close();
                    }
                }
            }
        }
        
        /**
         * Closes the connection and cleans up resources.
         */
        public synchronized void close() {
            try {
                if (outputStream != null) {
                    outputStream.close();
                    outputStream = null;
                }
                if (inputStream != null) {
                    inputStream.close();
                    inputStream = null;
                }
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                logMessage("Error closing connection resources: " + e.getMessage());
            }
            
            serverConnections.remove(this);
            String typeName = type == ServerConnectionType.LEADER ? "Leader" : "Follower";
            logMessage(typeName + " " + remoteIp + " disconnected. Active server connections: " + serverConnections.size());
            followerIps.remove(getRemoteIp());
            if (isLeader() && type == ServerConnectionType.FOLLOWER) {
                sendFollowersToConnections();
                logServerState();
            }
        }
                
        /**
         * Gets the remote IP address.
         */
        public String getRemoteIp() {
            return remoteIp;
        }
        
        /**
         * Gets the connection type.
         */
        public ServerConnectionType getType() {
            return type;
        }
        
        /**
         * Gets the input stream.
         */
        public synchronized ObjectInputStream getInputStream() {
            return inputStream;
        }
                
        /**
         * Gets the output stream.
         */
        public ObjectOutputStream getOutputStream() {
            return outputStream;
        }
    }
    
    /**
     * Helper method to deserialize drawing messages.
     */
    private Object deserializeDrawingMessage(String serializedStr) {
        try {
            // Basic implementation for Message objects
            if (serializedStr.contains("DRAW:") || serializedStr.contains("CLEAR:")) {
                String[] parts = serializedStr.split(":", 2);
                String type = parts[0];
                String content = parts.length > 1 ? parts[1] : "";
                String senderId = "SERVER";
                
                Message.MessageType messageType = null;
                if (type.equals("DRAW")) {
                    messageType = Message.MessageType.DRAW;
                } else if (type.equals("CLEAR")) {
                    messageType = Message.MessageType.CLEAR;
                }
                
                if (messageType != null) {
                    return new Message(messageType, content, senderId);
                }
            }
        } catch (Exception e) {
            logMessage("Error deserializing: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Main method. This will start either a leader or a follower.
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