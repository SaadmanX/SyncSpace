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
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.BufferedReader;

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

    // Local database storage
    private static final String DATABASE_FILE = "local_database.txt";
    private final Object databaseLock = new Object();
    
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
    private final List<Message> textHistory = new CopyOnWriteArrayList<>();
    
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
        
        // Initialize local database
        initializeLocalDatabase();
        
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
        
            startServerToServerListener();
            startClientListener();
            
            // If leader, load drawing history from local database
            loadDrawingHistoryFromLocalDatabase();
        } else {
            connectToLeader();
        }
    }

    /**
     * Initializes the local database file if it doesn't exist
     */
    private void initializeLocalDatabase() {
        File dbFile = new File(DATABASE_FILE);
        try {
            if (!dbFile.exists()) {
                logMessage("Creating new local database file: " + DATABASE_FILE);
                if (dbFile.createNewFile()) {
                    logMessage("Local database file created successfully");
                } else {
                    logMessage("Failed to create local database file");
                }
            } else {
                logMessage("Using existing local database file: " + DATABASE_FILE);
            }
        } catch (IOException e) {
            logMessage("Error initializing local database: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
/**
 * Loads drawing history from local database file
 */
private void loadDrawingHistoryFromLocalDatabase() {
    synchronized (databaseLock) {
        drawingHistory.clear();
        textHistory.clear();
        
        try (FileReader fr = new FileReader(DATABASE_FILE);
             BufferedReader br = new BufferedReader(fr)) {
            
            logMessage("Loading drawing history from local database");
            String line;
            int lineCount = 0;
            
            while ((line = br.readLine()) != null) {
                try {
                    if (line.trim().isEmpty()) continue;
                    
                    String[] parts = line.split(":");
                    if (parts.length < 2) continue;
                    
                    String typeStr = parts[0].trim();
                    String content = parts[1].trim();
                    String userId = "SERVER";
                    long timestamp = System.currentTimeMillis();
                    
                    // Extract timestamp if available
                    if (parts.length >= 3) {
                        try {
                            timestamp = Long.parseLong(parts[2].trim());
                        } catch (NumberFormatException e) {
                            logMessage("Invalid timestamp format in database: " + parts[2]);
                        }
                    }
                    
                    // Extract user ID if available in the content
                    if (content.contains(";")) {
                        String[] contentParts = content.split(";", 2);
                        content = contentParts[0];
                        if (contentParts.length > 1) {
                            userId = contentParts[1];
                        }
                    }
                    
                    // Determine message type and create the full message content
                    Message.MessageType messageType;
                    String fullContent;
                    
                    if (typeStr.equals("START")) {
                        messageType = Message.MessageType.DRAW;
                        fullContent = "START:" + content;  // Preserve START prefix
                    } else if (typeStr.equals("END")) {
                        messageType = Message.MessageType.DRAW;
                        fullContent = "END:" + content;    // Preserve END prefix
                    } else if (typeStr.equals("DRAW")) {
                        messageType = Message.MessageType.DRAW;
                        fullContent = "DRAW:" + content;   // Preserve DRAW prefix
                    } else if (typeStr.equals("CLEAR")) {
                        messageType = Message.MessageType.CLEAR;
                        fullContent = content;             // No prefix needed for CLEAR
                    } else if (typeStr.equals("TEXT")) {
                        messageType = Message.MessageType.TEXT;
                        fullContent = content;             // No prefix needed for TEXT
                    } else {
                        // Skip unknown message types
                        continue;
                    }
                    
                    // Create message and add to appropriate history
                    Message msg = new Message(messageType, fullContent, userId, timestamp);
                    
                    if (messageType == Message.MessageType.TEXT) {
                        textHistory.add(msg);
                    } else {
                        drawingHistory.add(msg);
                        
                        // If it's a clear message, remove all drawing actions before this point
                        if (messageType == Message.MessageType.CLEAR && "CLEAR_ALL".equals(content)) {
                            drawingHistory.clear();
                            drawingHistory.add(msg); // Keep the clear message
                        }
                    }
                    
                    lineCount++;
                } catch (Exception e) {
                    logMessage("Error parsing line from database: " + line + " - " + e.getMessage());
                }
            }
            
            logMessage("Loaded " + lineCount + " actions from local database");
            
        } catch (IOException e) {
            logMessage("Error reading from local database: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
    
/**
 * Appends a message to the local database file
 */
private void appendToLocalDatabase(Message message) {
    synchronized (databaseLock) {
        try (FileWriter fw = new FileWriter(DATABASE_FILE, true)) {
            // Extract content and message type
            String content = message.getContent();
            String type = message.getType().toString();
            
            // Default subtype for DRAW messages is "DRAW"
            String subType = "DRAW";
            
            // For DRAW messages, check for subtypes (START, DRAW, END)
            if (type.equals("DRAW")) {
                // Check if content starts with one of our subtypes
                if (content.startsWith("START:")) {
                    subType = "START";
                    content = content.substring(6); // Remove "START:"
                } else if (content.startsWith("END:")) {
                    subType = "END";
                    content = content.substring(4); // Remove "END:"
                } else if (content.startsWith("DRAW:")) {
                    content = content.substring(5); // Remove "DRAW:"
                }
                
                // If content still has another type prefix, remove it
                if (content.startsWith(subType + ":")) {
                    content = content.substring(subType.length() + 1);
                }
                
                // Format entry with subtype for DRAW messages
                String dbEntry = subType + ":" + content + ":" + message.getTimestamp() + "\n";
                fw.write(dbEntry);
                logMessage("Successfully wrote to local database: " + dbEntry.trim());
            } else {
                // For TEXT and CLEAR messages
                if (content.startsWith(type + ":")) {
                    content = content.substring(type.length() + 1);
                }
                
                // Format entry for non-DRAW messages
                String dbEntry = type + ":" + content + ":" + message.getTimestamp() + "\n";
                fw.write(dbEntry);
                logMessage("Successfully wrote to local database: " + dbEntry.trim());
            }
        } catch (IOException e) {
            logMessage("Error writing to local database: " + e.getMessage());
            e.printStackTrace();
        }
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
                        connection.start();

                        followerIps.add(followerIp);
                        sendFollowersToConnections();
                        
                        // Send current drawing history to new follower
                        sendDrawingHistoryToFollower(connection);
                        
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
     * Sends the entire drawing history to a new follower
     */
    private void sendDrawingHistoryToFollower(ServerConnection followerConnection) {
        if (!isLeader() || followerConnection == null) return;
        
        logMessage("Sending drawing history to follower at " + followerConnection.getRemoteIp());
        
        // Create a full history message
        StringBuilder sb = new StringBuilder("FULL_HISTORY:");
        
        // Include all drawing actions
        for (Message drawAction : drawingHistory) {
            sb.append(drawAction.getType())
              .append(":")
              .append(drawAction.getContent())
              .append(":")
              .append(drawAction.getTimestamp())
              .append("\n");
        }
        
        // Include all text messages
        for (Message textMsg : textHistory) {
            sb.append(textMsg.getType())
              .append(":")
              .append(textMsg.getContent())
              .append(":")
              .append(textMsg.getTimestamp())
              .append("\n");
        }
        
        // Send the history to the follower
        followerConnection.sendMessage(sb.toString());
        logMessage("Drawing history sent to follower at " + followerConnection.getRemoteIp());
    }
    
    /**
     * Connects to the leader server (follower mode).
     */
    private void connectToLeader() {
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
        // sendFollowersToConnections();
        
        // Notify clients of leadership change
        for (ClientHandler client : connectedClients) {
            client.sendMessage("SERVER_LEADERSHIP_CHANGE");
            client.sendMessage("NEW_LEADER_IP" + serverIp);
        }
        
        // Load drawing history from local database
        loadDrawingHistoryFromLocalDatabase();
        
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
        state.append("Drawing history size: ").append(drawingHistory.size()).append("\n");
        state.append("Text history size: ").append(textHistory.size()).append("\n");
        
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
        
        // Store drawing actions in history and local database
        if (message instanceof Message) {
            Message msg = (Message) message;
            if (msg.getType() == Message.MessageType.DRAW || 
                msg.getType() == Message.MessageType.CLEAR ||
                msg.getType() == Message.MessageType.TEXT) {
                
                // Store in appropriate history collection
                if (msg.getType() == Message.MessageType.TEXT) {
                    textHistory.add(msg);
                } else {
                    drawingHistory.add(msg);
                    
                    // If it's a clear message, remove all drawing actions
                    if (msg.getType() == Message.MessageType.CLEAR && 
                        msg.getContent().contains("CLEAR_ALL")) {
                        drawingHistory.clear();
                        drawingHistory.add(msg); // Keep the clear message
                    }
                }
                
                // Save to local database
                appendToLocalDatabase(msg);
                
                // If leader, replicate to followers
                if (isLeader()) {
                    replicateMessageToFollowers(msg);
                }
            }
        }
        
        // Send to all connected clients except the sender
        for (ClientHandler client : connectedClients) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }
    
    /**
     * Replicates messages to all followers.
     */
    private void replicateMessageToFollowers(Message message) {
        if (!isLeader()) return;
        
        String messageType = message.getType().toString();
        logMessage("Replicating " + messageType + " message to " + followerIps.size() + " followers");
        
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            if (conn.getType() == ServerConnectionType.FOLLOWER) {
                conn.sendMessage("REPLICATE:" + messageType + ":" + message.getContent() + 
                                ":" + message.getSenderId() + ":" + message.getTimestamp());
            }
        }
    }
    
    /**
     * Sends the entire drawing history to a specific client.
     */
    public void sendDrawingHistoryToClient(ClientHandler client) {
        logMessage("Sending drawing history to new client. History size: " + drawingHistory.size());
        
        // First, send a message to clear any existing content
        client.sendMessage(new Message(Message.MessageType.CLEAR, "CLEAR_ALL", "SERVER", getCurrentTime()));
        
        // Sort drawing history by sender ID first, then by timestamp
        List<Message> sortedHistory = new ArrayList<>(drawingHistory);
        sortedHistory.sort((m1, m2) -> {
            // First compare by sender ID
            int senderCompare = m1.getSenderId().compareTo(m2.getSenderId());
            if (senderCompare != 0) {
                return senderCompare;
            }
            
            // If same sender, then sort by timestamp
            return Long.compare(m1.getTimestamp(), m2.getTimestamp());
        });
        
        // Then send all drawing actions with their original timestamps
        for (Message drawAction : sortedHistory) {
            client.sendMessage(drawAction);
        }
        
        // Send all text messages sorted by timestamp
        List<Message> sortedTextHistory = new ArrayList<>(textHistory);
        sortedTextHistory.sort((m1, m2) -> Long.compare(m1.getTimestamp(), m2.getTimestamp()));
        
        for (Message textMsg : sortedTextHistory) {
            client.sendMessage(textMsg);
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
                else if (stringMessage.startsWith("REPLICATE:")) {
                    // Handle replication messages from the leader
                    handleReplicationMessage(stringMessage);
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
                else if (stringMessage.startsWith("FULL_HISTORY:")) {
                    logMessage("Received full history from leader");
                    String historyData = stringMessage.substring("FULL_HISTORY:".length());
                    processFullHistory(historyData);
                }
                else {
                    // Other string messages
                    logMessage("Received unknown message: " + stringMessage);
                }
            } else {
                logMessage("Received unknown message type: " + message.getClass().getName());
            }
        }

        /**
         * Process a full history update from leader
         * Format: TYPE:CONTENT:TIMESTAMP\n
         */
        private void processFullHistory(String historyData) {
            if (historyData == null || historyData.trim().isEmpty()) {
                logMessage("Received empty history data");
                return;
            }
            
            logMessage("Processing history data with " + historyData.split("\n").length + " entries");
            
            // Clear existing histories
            drawingHistory.clear();
            textHistory.clear();
            
            // Clear database file and create a new one
            synchronized (databaseLock) {
                try {
                    // Create a new empty database file
                    File dbFile = new File(DATABASE_FILE);
                    if (dbFile.exists()) {
                        dbFile.delete();
                    }
                    dbFile.createNewFile();
                    
                    // Write all history to the file
                    try (FileWriter fw = new FileWriter(DATABASE_FILE)) {
                        fw.write(historyData);
                    }
                    
                    logMessage("History data written to local database");
                } catch (IOException e) {
                    logMessage("Error writing history to database: " + e.getMessage());
                }
            }
            
            // Process each line to rebuild in-memory history
            String[] lines = historyData.split("\n");
            for (String line : lines) {
                try {
                    if (line.trim().isEmpty()) continue;
                    
                    String[] parts = line.split(":");
                    if (parts.length < 3) {
                        logMessage("Invalid history line format: " + line);
                        continue;
                    }
                    
                    String typeStr = parts[0].trim();
                    String content = parts[1].trim();
                    long timestamp = Long.parseLong(parts[2].trim());
                    String senderId = parts.length > 3 ? parts[3].trim() : "SERVER";
                    
                    // Create appropriate message type
                    Message.MessageType messageType;
                    if (typeStr.equals("DRAW")) {
                        messageType = Message.MessageType.DRAW;
                    } else if (typeStr.equals("CLEAR")) {
                        messageType = Message.MessageType.CLEAR;
                    } else if (typeStr.equals("TEXT")) {
                        messageType = Message.MessageType.TEXT;
                    } else {
                        continue; // Skip unknown types
                    }
                    
                    Message msg = new Message(messageType, content, senderId, timestamp);
                    
                    // Add to appropriate history collection
                    if (messageType == Message.MessageType.TEXT) {
                        textHistory.add(msg);
                    } else {
                        drawingHistory.add(msg);
                        
                        // Handle CLEAR messages
                        if (messageType == Message.MessageType.CLEAR && 
                            content.contains("CLEAR_ALL")) {
                            drawingHistory.clear();
                            drawingHistory.add(msg);
                        }
                    }
                } catch (Exception e) {
                    logMessage("Error processing history line: " + e.getMessage());
                }
            }
            
            logMessage("History processing complete. Drawing history: " + drawingHistory.size() + 
                       " items, Text history: " + textHistory.size() + " items");
        }

        /**
         * Handle a replication message from the leader
         * Format: REPLICATE:TYPE:CONTENT:SENDER_ID:TIMESTAMP
         */
        private void handleReplicationMessage(String stringMessage) {
            try {
                String[] parts = stringMessage.split(":", 5);
                if (parts.length < 5) {
                    logMessage("Invalid replication message format: " + stringMessage);
                    return;
                }
                
                String messageType = parts[1];
                String content = parts[2];
                String senderId = parts[3];
                long timestamp = Long.parseLong(parts[4]);
                
                Message.MessageType type = null;
                if (messageType.equals("DRAW")) {
                    type = Message.MessageType.DRAW;
                } else if (messageType.equals("CLEAR")) {
                    type = Message.MessageType.CLEAR;
                } else if (messageType.equals("TEXT")) {
                    type = Message.MessageType.TEXT;
                }
                
                if (type != null) {
                    // Create the message
                    Message msg = new Message(type, content, senderId, timestamp);
                    
                    // Store in appropriate history collection
                    if (type == Message.MessageType.TEXT) {
                        textHistory.add(msg);
                    } else {
                        drawingHistory.add(msg);
                        
                        // If it's a clear message, remove all drawing actions
                        if (type == Message.MessageType.CLEAR && 
                            content.contains("CLEAR_ALL")) {
                            drawingHistory.clear();
                            drawingHistory.add(msg); // Keep the clear message
                        }
                    }
                    
                    // Save to local database
                    appendToLocalDatabase(msg);
                    
                    // Forward to any connected clients
                    for (ClientHandler client : connectedClients) {
                        client.sendMessage(msg);
                    }
                }
            } catch (Exception e) {
                logMessage("Error handling replication message: " + e.getMessage());
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
                    // Create message with synchronized server time
                    return new Message(messageType, content, senderId, getCurrentTime());
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