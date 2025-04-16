package com.syncspace.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.syncspace.common.Message;
import com.syncspace.common.Message.MessageType;

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
    private final BlockingQueue<ConnectionMessage> messageQueue = new PriorityBlockingQueue<>(1000, 
        (msg1, msg2) -> {
            // Determine message priority
            int prio1 = getMessagePriority(msg1.message);
            int prio2 = getMessagePriority(msg2.message);
            return Integer.compare(prio1, prio2);
        });

    // Time synchronization 
    private long virtualClockOffset = 0;
    private boolean syncInProgress = false;
    private final Map<String, Long> clientTimes = new HashMap<>();
    private static final int SYNC_INTERVAL_SECONDS = 60;  // Run sync every minute

    
    // Server sockets
    private ServerSocket clientServerSocket;
    private ServerSocket serverServerSocket;
    private final Object connectLock = new Object();
    private final Object fileAccessLock = new Object();

    private volatile boolean connectingToLeader = false;
    private ScheduledFuture<?> leaderConnectFuture = null;
    private ScheduledFuture<?> leaderHeartbeatFuture = null;
    private static final int HEARTBEAT_INTERVAL_MS = 2000; // 2 seconds
    private static final int HEARTBEAT_TIMEOUT_MS = 6000; // 6 seconds
    private volatile long lastLeaderHeartbeat = 0;
    
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
            scheduledTaskExecutor.scheduleAtFixedRate(
                this::initiateTimeSync, 
                30, // Initial delay
                SYNC_INTERVAL_SECONDS, 
                TimeUnit.SECONDS
            );
        
            startLeaderMode();
            startClientListener();
        } else {
            startFollowerMode();
        }
    }

    /**
     * Gets the local server IP address.
     * @return Local IP address string
     */
    private String initializeServerIp() {
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            logMessage("ERROR: Could not list network interfaces: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    /**
     * Initiates Berkeley's time synchronization
     */
    private void initiateTimeSync() {
        if (syncInProgress || !isLeader()) {
            return;
        }
        
        syncInProgress = true;
        clientTimes.clear();
        
        // Add server's own time
        long serverTime = System.currentTimeMillis() + virtualClockOffset;
        clientTimes.put("SERVER", serverTime);
        
        // Request time from all clients
        logMessage("Initiating time synchronization with " + connectedClients.size() + " clients");
        for (ClientHandler client : connectedClients) {
            client.sendMessage("TIME_SYNC:REQUEST");
        }
        
        // Set a timeout to finish sync even if not all clients respond
        scheduledTaskExecutor.schedule(() -> {
            if (syncInProgress) {
                finalizeTimeSync();
            }
        }, 5, TimeUnit.SECONDS);
    }

    /**
     * Processes time from a client for Berkeley's algorithm
     */
    public void processClientTime(String clientId, long clientTime) {
        if (!syncInProgress) {
            return;
        }
        
        clientTimes.put(clientId, clientTime);
        logMessage("Received time from client " + clientId + ": " + clientTime);
        
        // If we've heard from all clients, complete the sync
        if (clientTimes.size() >= connectedClients.size() + 1) { // +1 for server
            finalizeTimeSync();
        }
    }

    /**
     * Finalizes time synchronization by calculating average and sending adjustments
     */
    private void finalizeTimeSync() {
        if (!syncInProgress) {
            return;
        }
        
        syncInProgress = false;
        
        if (clientTimes.isEmpty()) {
            logMessage("No client times received, aborting sync");
            return;
        }
        
        // Calculate average time (Berkeley algorithm)
        long sum = 0;
        for (long time : clientTimes.values()) {
            sum += time;
        }
        long averageTime = sum / clientTimes.size();
        
        // Calculate offsets and build log message
        StringBuilder logMsg = new StringBuilder("Time sync results:\n");
        logMsg.append("Average time: ").append(averageTime).append("\n");
        
        for (Map.Entry<String, Long> entry : clientTimes.entrySet()) {
            String clientId = entry.getKey();
            long clientTime = entry.getValue();
            long offset = averageTime - clientTime;
            
            logMsg.append(clientId).append(": ")
                .append(clientTime)
                .append(", offset: ").append(offset).append("ms\n");
                
            // Send adjustment to client
            if (!clientId.equals("SERVER")) {
                // Find the client and send adjustment
                for (ClientHandler client : connectedClients) {
                    if (client.getUsername().equals(clientId)) {
                        client.sendMessage("TIME_SYNC:ADJUST:" + offset);
                        break;
                    }
                }
            }
        }
        
        logMessage(logMsg.toString());
        
        // Adjust server's own virtual clock
        long serverOffset = averageTime - clientTimes.get("SERVER");
        virtualClockOffset += serverOffset;
        logMessage("Server virtual clock adjusted by " + serverOffset + "ms, total offset: " + virtualClockOffset + "ms");
    }

    /**
     * Gets the current time with any virtual clock adjustments
     */
    public long getCurrentTime() {
        return System.currentTimeMillis() + virtualClockOffset;
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
     * Determine priority of a message (lower number = higher priority)
     */
    private int getMessagePriority(Object message) {
        // Time sync messages have highest priority
        if (message instanceof String) {
            String strMsg = (String) message;
            if (strMsg.startsWith("TIME_SYNC:")) {
                return 1;
            }
        }
        
        // Drawing actions have medium priority
        if (message instanceof Message) {
            Message msg = (Message) message;
            if (msg.getType() == Message.MessageType.DRAW || 
                msg.getType() == Message.MessageType.CLEAR) {
                return 2;
            }
        }
        
        // All other messages have lowest priority
        return 3;
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
            int i = 0;
            ServerConnection leaderConn = getLeaderConnection();
            while (leaderConn == null && i < 5) {
                i++;
                try {
                    logMessage("couldnt connect to leader, trying again soon with leader ip: " + leaderIp);
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                leaderConn = getLeaderConnection();
            }
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
    private void startLeaderMode() {
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
                        connection.start();

                        followerIps.add(followerIp);
                        sendFollowersToConnections();
                        logServerState();

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
    private void startFollowerMode() {
        // If already leader or a connection attempt is in progress, return.

        if (isLeader()) return;
        followerIps.clear();
        logMessage("DEBUG:::::Before synchronized connection to leader");
        synchronized (connectLock) {
            logMessage("DEBUG:::::In synchronized connection to leader before IF");
            if (connectingToLeader) return;
            connectingToLeader = true;
        }
        logMessage("DEBUG:::::After (outside) synchronized connection to leader");
        
        
        // Schedule a repeated connection attempt
        logMessage("DEBUG:::::Before leader connect future thread");
        
        leaderConnectFuture = scheduledTaskExecutor.scheduleWithFixedDelay(new Runnable() {
            private int attemptCount = 0;
            
            @Override
            public void run() {
                // If we're already a leader, cancel further attempts.
                if (isLeader()) {
                    logMessage("DEBUG:::::is leader -> cancel leader connection task");
                    cancelLeaderConnectTask();
                    return;
                }
                logMessage("DEBUG::::Follower Ips before attempting: " + followerIps);
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
        }, RECONNECT_DELAY_MS, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
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
        logMessage("DEBUG::::::this will return null haha");

        return null;
    }
    
    /**
     * Starts the election process.
     */
    public synchronized void startElection() {
        logMessage("========== STARTING ELECTION PROCESS ==========");
        
        List<String> allServerIps = new ArrayList<>(followerIps);

        
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
        logMessage("DEBUG::::follower Ips after becoming a follower to new leader: " + followerIps);
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            conn.close();
        }
        serverConnections.clear();
        followerIps.clear();
        logMessage("Removed self from follower list: " + serverIp);

        actingAsLeader.set(false);
        this.leaderIp = newLeaderIp;
        // connectToLeader();
        shutdown();
        main(new String[]{newLeaderIp});
    }

    /**
     * Transitions this server to leader mode.
     */
    public synchronized void becomeLeader() {
        if (isLeader()) {
            logMessage("Already in leader mode, no transition needed");
            return;
        }

        logMessage("DEBUG::::Follower Ips after becoming a new leader: " + followerIps);

        
        logMessage("TRANSITIONING TO LEADER MODE");
        
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            conn.close();
        }
        serverConnections.clear();
        
        followerIps.clear();
        logMessage("Removed self from follower list: " + serverIp);

        actingAsLeader.set(true);
        
        // Notify clients of leadership change
        for (ClientHandler client : connectedClients) {
            client.sendMessage("SERVER_LEADERSHIP_CHANGE");
            client.sendMessage("NEW_LEADER_IP" + serverIp);
        }
        
        startLeaderMode();
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
                msg.getType() == Message.MessageType.CLEAR || msg.getType() == Message.MessageType.TEXT) {
                    writeActionToFile(msg);
                    logMessage("---- this is the message we should see ------");
                    logMessage(msg.toString());
                    logMessage("--------------");
                                    
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
     * Sends the entire drawing history to a specific client, sorted by sender and timestamp.
     */
    public void sendActionHistoryToClient(ClientHandler client) {
        logMessage("Sending drawing history to new client. Sorting by sender ID and timestamp");
        
        // First, send a message to clear any existing content
        client.sendMessage(new Message(Message.MessageType.CLEAR, "CLEAR_ALL", "SERVER", getCurrentTime()));
        
        // Read all actions from file and parse them
        List<Message> actionsToSend = new ArrayList<>();
        
        synchronized(fileAccessLock) { // Add this lock as a class field
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(getFilename()))) {
                String line;
                
                while ((line = reader.readLine()) != null) {
                    Message msg = parseActionLine(line);
                    
                    // Skip null or non-drawing messages
                    if (msg == null) continue;
                    
                    // Only collect drawing-related messages
                    if (msg.getType() == Message.MessageType.DRAW || 
                        msg.getType() == Message.MessageType.CLEAR) {
                        
                        // If it's a clear message, clear all previous actions
                        if (msg.getType() == Message.MessageType.CLEAR && 
                            msg.getContent().contains("CLEAR_ALL")) {
                            actionsToSend.clear();
                        }
                        
                        actionsToSend.add(msg);
                    }
                }
            } catch (IOException e) {
                // If file doesn't exist, just ignore
                if (!(e instanceof java.io.FileNotFoundException)) {
                    logMessage("Error reading action file: " + e.getMessage());
                }
            }
        }
        
        // Sort drawing history by sender ID first, then by timestamp
        actionsToSend.sort((m1, m2) -> {
            // First compare by sender ID
            int senderCompare = m1.getSenderId().compareTo(m2.getSenderId());
            if (senderCompare != 0) {
                return senderCompare;
            }
            
            // If same sender, then sort by timestamp
            return Long.compare(m1.getTimestamp(), m2.getTimestamp());
        });
        
        // Send the sorted actions to the client
        logMessage("Sending " + actionsToSend.size() + " sorted drawing actions to new client");
        for (Message action : actionsToSend) {
            client.sendMessage(action);
        }
    }
    

    /**
     * Parses a single action line from history file
     */
    private Message parseActionLine(String line) {
        try {
            if (line.trim().isEmpty()) return null;
            
            String[] mainParts = line.split(";", 2);
            if (mainParts.length < 2) return null;
            
            String actionData = mainParts[0];
            String senderId = mainParts[1];
            
            String[] dataParts = actionData.split(":", 3);
            if (dataParts.length < 3) return null;
            
            String typeStr = dataParts[0].trim();
            String content = dataParts[1].trim();
            long timestamp = Long.parseLong(dataParts[2].trim());
            
            MessageType messageType;
            if (typeStr.equals("DRAW") || typeStr.equals("START") || typeStr.equals("END")) {
                messageType = MessageType.DRAW;
                content = typeStr + ":" + content;
            } else if (typeStr.equals("CLEAR")) {
                messageType = MessageType.CLEAR;
            } else if (typeStr.equals("TEXT")) {
                messageType = MessageType.TEXT;
            } else {
                return null;
            }
            
            return new Message(messageType, content, senderId, timestamp);
        } catch (Exception e) {
            logMessage("Error parsing action line: " + line + " - " + e.getMessage());
            return null;
        }
    }
    

    /**
     * Writes a message action to the server's local log file.
     * Uses open-write-close pattern for simplicity.
     */
    private void writeActionToFile(Message message) {
        synchronized (fileAccessLock) {
            try (java.io.FileWriter fw = new java.io.FileWriter(getFilename(), true)) {
                // For drawing actions, extract the actual action type from content

                String typeStr;
                String content = message.getContent();

                if (message.getType() == Message.MessageType.DRAW) {
                    // Extract the action type from the content
                    if (content.startsWith("START:")) {
                        typeStr = "START";
                        content = content.substring(6); // Remove "START:" prefix
                    } else if (content.startsWith("DRAW:")) {
                        typeStr = "DRAW";
                        content = content.substring(5); // Remove "DRAW:" prefix
                    } else if (content.startsWith("END:")) {
                        typeStr = "END";
                        content = content.substring(4); // Remove "END:" prefix
                    } else {
                        typeStr = "DRAW"; // Default
                    }
                } else {
                    typeStr = message.getType().toString();
                }
                
                // Write in original format: TYPE:CONTENT:TIMESTAMP;SENDER_ID
                String actionData = typeStr + ":" + content + ":" + message.getTimestamp() + ";" + message.getSenderId() + "\n";
                fw.write(actionData);
            } catch (IOException e) {
                logMessage("Error writing to action file: " + e.getMessage());
            }
        }
    }
    
    private void writeActionsToFile(String actionHistory) {
        if (actionHistory == null || actionHistory.trim().isEmpty()) {
            logMessage("Empty action history, not writing to file");
            return;
        }
        
        synchronized(fileAccessLock) {
            try (java.io.FileWriter fw = new java.io.FileWriter(getFilename(), false)) { // false = overwrite
                fw.write(actionHistory);
                logMessage("Wrote " + actionHistory.split("\n").length + " actions to history file");
            } catch (IOException e) {
                logMessage("Error writing actions to file: " + e.getMessage());
            }
        }
    }
    
    
    
    /**
     * Reads the entire content of this server's action log file.
     * @return The file content as a string, or empty string if file doesn't exist
     */
    private String readActionFile() {
        StringBuilder content = new StringBuilder();
        synchronized (fileAccessLock) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(getFilename()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            } catch (IOException e) {
                // If file doesn't exist yet, just return empty string
                if (!(e instanceof java.io.FileNotFoundException)) {
                    logMessage("Error reading action file: " + e.getMessage());
                }
            }
        }
        return content.toString();
    }


    private String getFilename() {
        return "database_" + serverIp.replace('.', '_') + ".txt";
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
            logMessage("Shutting down as LEADER");
            logMessage("Notifying followers of shutdown");
            for (ServerConnection conn : new ArrayList<>(serverConnections)) {
                if (conn.getType() == ServerConnectionType.FOLLOWER) {
                    logMessage("Sending shutdown notice to " + conn.getType() + " at " + conn.getRemoteIp());
                    conn.sendMessage("LEADER_SHUTDOWN");
                }
            }
            
            
            // Give followers time to process the shutdown message
            logMessage("Waiting for followers to process shutdown message");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logMessage("Leader wait interrupted");
                Thread.currentThread().interrupt();
            }
        } else {
            logMessage("Shutting down as FOLLOWER");
            logMessage("Notifying leader of shutdown");
            for (ServerConnection conn : new ArrayList<>(serverConnections)) {
                if (conn.getType() == ServerConnectionType.LEADER) {
                    logMessage("Sending shutdown notice to LEADER at " + conn.getRemoteIp());
                    conn.sendMessage("FOLLOWER_SHUTDOWN:" + serverIp);
                }
            }
    
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                logMessage("Follower wait interrupted");
                Thread.currentThread().interrupt();
            }
        }
        
        // Signal threads to stop
        logMessage("Setting running flag to false");
        running = false;

        // Close server sockets
        try {
            if (clientServerSocket != null && !clientServerSocket.isClosed()) {
                logMessage("Closing client server socket");
                clientServerSocket.close();
                logMessage("Client server socket closed");
            }
        } catch (IOException e) {
            logMessage("Error closing client socket: " + e.getMessage());
        }
        
        try {
            if (serverServerSocket != null && !serverServerSocket.isClosed()) {
                logMessage("Closing server server socket");
                serverServerSocket.close();
                logMessage("Server server socket closed");
            }
        } catch (IOException e) {
            logMessage("Error closing server socket: " + e.getMessage());
        }
        
        // Cancel any scheduled tasks
        if (leaderConnectFuture != null) {
            logMessage("Cancelling leader connect task");
            leaderConnectFuture.cancel(true);
        }
        
        if (leaderHeartbeatFuture != null) {
            logMessage("Cancelling leader heartbeat task");
            leaderHeartbeatFuture.cancel(true);
        }
        
        // Interrupt and wait for threads to finish
        if (connectionReaderThread != null) {
            logMessage("Interrupting connection reader thread");
            connectionReaderThread.interrupt();
            try {
                logMessage("Waiting for connection reader thread to terminate");
                connectionReaderThread.join(2000);
                logMessage("Connection reader thread " + (connectionReaderThread.isAlive() ? "still running" : "terminated"));
            } catch (InterruptedException e) {
                logMessage("Wait for connection reader thread interrupted");
                Thread.currentThread().interrupt();
            }
        }
        
        if (messageHandlerThread != null) {
            logMessage("Interrupting message handler thread");
            messageHandlerThread.interrupt();
            try {
                logMessage("Waiting for message handler thread to terminate");
                messageHandlerThread.join(2000);
                logMessage("Message handler thread " + (messageHandlerThread.isAlive() ? "still running" : "terminated"));
            } catch (InterruptedException e) {
                logMessage("Wait for message handler thread interrupted");
                Thread.currentThread().interrupt();
            }
        }
        
        // Close all connections
        logMessage("Closing " + serverConnections.size() + " server connections");
        int connectionCount = 0;
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            logMessage("Closing connection " + (++connectionCount) + ": " + conn.getType() + " at " + conn.getRemoteIp());
            conn.close();
        }
        serverConnections.clear();
        logMessage("All server connections closed");
        
        
        // Shutdown thread pools
        logMessage("Shutting down scheduled task executor");
        scheduledTaskExecutor.shutdownNow();
        
        logMessage("Shutting down task executor");
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
                        // Request action history if this is a connection to the leader
                if (type == ServerConnectionType.LEADER) {
                    sendMessage("REQUEST_ACTION_HISTORY");
                }
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
                    sendFollowersToConnections();
                    logServerState();
                }
                else if (stringMessage.startsWith("DRAWING:")) {
                    // Handle drawing replication from leader
                    handleDrawingMessage(stringMessage);
                }
                else if (stringMessage.startsWith("TEXT")) {
                    logMessage("DEBUG:::::" + stringMessage);
                    // Handle text message - not implemented in this code
                    handleClientTextMessage(stringMessage);
                } 
                else if (stringMessage.startsWith("FOLLOWER_SHUTDOWN:")) {
                    String followerIp = stringMessage.substring("FOLLOWER_SHUTDOWN:".length());
                    followerIps.remove(followerIp);
                    try {
                        if (socket != null && !socket.isClosed())
                        socket.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    sendFollowersToConnections();
                    logServerState();                
                }
                else if (stringMessage.equals("LEADER_SHUTDOWN")) {
                    logMessage("Leader notified of orderly shutdown, starting election");
                    if (type == ServerConnectionType.LEADER) {
                        close();
                        startElection();
                    }
                }
                else if (stringMessage.equals("REQUEST_ACTION_HISTORY")) {
                    if (type == ServerConnectionType.FOLLOWER) {
                        String actionHistory = readActionFile();
                        sendMessage("ACTION_HISTORY:" + actionHistory);
                    }
                }
                else if (stringMessage.startsWith("ACTION_HISTORY:")) {
                    String actionHistory = stringMessage.substring("ACTION_HISTORY:".length());
                    handleActionHistory(actionHistory);
                }
                else {
                    // Other string messages
                    logMessage("Received message: " + stringMessage);
                    // dbConn.sendMessage(stringMessage);
                }
            } else {
                logMessage("Received unknown message type: " + message.getClass().getName());
            }
        }

        

        private void handleActionHistory(String actionHistory) {
            if (actionHistory == null || actionHistory.trim().isEmpty()) {
                logMessage("Received empty action history");
                return;
            }
            
            // First, write the raw history to file
            writeActionsToFile(actionHistory);
            
            // Then process each line to send to connected clients
            List<Message> actionsToSend = new ArrayList<>();
            String[] actions = actionHistory.split("\n");
            logMessage("Processing " + actions.length + " actions from history");
            
            for (String action : actions) {
                try {

                    // Create a message and send to clients
                    Message msg = parseActionLine(action);

                    // Send to connected clients (for drawings/clears)
                    if (msg == null) continue;
            
                    // Only process drawing-related messages
                    if (msg.getType() == MessageType.DRAW || msg.getType() == MessageType.CLEAR) {
                        // If we encounter a CLEAR_ALL, remove all previous drawing actions
                        if (msg.getType() == MessageType.CLEAR && 
                            msg.getContent().contains("CLEAR_ALL")) {
                            logMessage("Found CLEAR_ALL action, clearing " + actionsToSend.size() + " previous actions");
                            actionsToSend.clear();
                        }
                        
                        // Add this action to our collection
                        actionsToSend.add(msg);
                    }
                } catch (Exception e) {
                    logMessage("Error processing history action: " + action + " - " + e.getMessage());
                }
            }
            
            logMessage("Sending " + actionsToSend.size() + " drawing actions to clients");
            for (Message msg : actionsToSend) {
                for (ClientHandler client : connectedClients) {
                    client.sendMessage(msg);
                }
            }
            
            logMessage("Finished processing action history");
        }        

        private Object deserializeTextMessage(String serialText){
            
            try{
                if (serialText.contains("TEXT:")) {
                    String[] parts = serialText.split(":", 2);
                    String type = parts[0];
                    String content = parts.length > 1 ? parts[1] : "";
                    String senderId = "SERVER";
                    
                    Message.MessageType messageType = null;
                    if (type.equals("TEXT")) {
                        messageType = Message.MessageType.TEXT;
                    }
                    
                    if (messageType != null) {
                        return new Message(messageType, content, senderId);
                    }
                }
            }catch (Exception e){
                logMessage("Error deserializing: " + e.getMessage());
            }
            return null;
        }

        
        /*
         * This handles client text history
         */
        private void handleClientTextMessage(String stringMessage){
            try{
                String textPart = stringMessage.substring(8);
                Object textObj = deserializeTextMessage(textPart);

                if(textObj instanceof Message){
                    Message textMsg = (Message) textObj;
                    if(textMsg.getType() == MessageType.TEXT){

                        writeActionToFile(textMsg);
                        logMessage("---- this is the message we should see ------");
                        logMessage(textMsg.toString());
                        logMessage("--------------");
    
                        for(ClientHandler client : connectedClients){
                            client.sendMessage(textMsg);
                        }
                    }

                }
            }catch (Exception e){
                logMessage("ERROprocessing text messages: "+e.getMessage());
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
                        
                        Message submessage = parseActionLine(drawMsg.getContent());
                        writeActionToFile(drawMsg);
                        logMessage("---- this is the message we should see ------");
                        logMessage(drawMsg.toString());
                        logMessage("--------------");
    
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
            if (outputStream == null || socket == null || socket.isClosed()) {
                logMessage("Cannot send message to " + remoteIp + " - connection is closed");
                return;
            }
            
            synchronized (outputStream) {
                try {
                    outputStream.writeObject(message);
                    outputStream.flush();
                } catch (IOException e) {
                    logMessage("Error sending message to " + remoteIp + ": " + e.getMessage());
                    try {
                        close();
                    } catch (Exception m) {
                        logMessage("Cannot close. line 1151: " + m.getMessage());
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
                    logMessage("Output stream closed");
                }
                if (inputStream != null) {
                    inputStream.close();
                    inputStream = null;
                    logMessage("Input stream closed");
                }
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    logMessage("Socket closed");
                }
            } catch (IOException e) {
                logMessage("Error closing connection resources: " + e.getMessage());
            } catch (Exception e2){
                logMessage("Error closing connection resources (E2): " + e2.getMessage());

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
    // Update the deserializeDrawingMessage method to use timestamps
    private Object deserializeDrawingMessage(String serializedStr) {
        try {
            // Check for drawing or clear actions
            if (serializedStr.contains("DRAW:") || 
                serializedStr.contains("START:") || 
                serializedStr.contains("END:")) {
                
                // All drawing actions use MessageType.DRAW
                return new Message(Message.MessageType.DRAW, serializedStr, "SERVER", getCurrentTime());
                
            } else if (serializedStr.contains("CLEAR:")) {
                return new Message(Message.MessageType.CLEAR, serializedStr, "SERVER", getCurrentTime());
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