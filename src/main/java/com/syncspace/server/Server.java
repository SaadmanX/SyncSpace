// package com.syncspace.server;

// import java.io.IOException;
// import java.io.ObjectInputStream;
// import java.io.ObjectOutputStream;
// import java.net.InetAddress;
// import java.net.ServerSocket;
// import java.net.Socket;
// import java.net.UnknownHostException;
// import java.text.SimpleDateFormat;
// import java.util.ArrayList;
// import java.util.Date;
// import java.util.List;
// import java.util.concurrent.CopyOnWriteArrayList;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;
// import java.util.concurrent.ScheduledExecutorService;
// import java.util.concurrent.TimeUnit;
// import java.util.concurrent.atomic.AtomicBoolean;

// public class Server {
//     private static final int PORT = 12345; // Client connection port
//     private static final int SERVER_PORT = 12346; // Server-to-server communication port
//     private static final int RECONNECT_DELAY_MS = 5000; // 5 seconds
//     private static final int MAX_RECONNECT_ATTEMPTS = 3;
    
//     private final UserManager userManager;
//     private final List<ClientHandler> connectedClients;
//     private AtomicBoolean actingAsLeader = new AtomicBoolean(false);
//     private String leaderIp;
//     private final String serverIp;
    
//     // Thread pools for better resource management
//     private final ExecutorService connectionThreadPool;
//     private final ScheduledExecutorService schedulerThreadPool;
    
//     // Server connection management
//     private final List<ServerConnection> serverConnections = new CopyOnWriteArrayList<>();
//     // track followers (both leaders and followers use this)
//     private final List<String> followerIps = new CopyOnWriteArrayList<>();

//     // Server sockets
//     private ServerSocket clientServerSocket;
//     private ServerSocket serverServerSocket;
    
//     /**
//      * Constructor for leader server
//      */
//     public Server() {
//         this(null);
//     }

//     /**
//      * Constructor with leader IP
//      * @param leaderIp IP of leader server (null for leader mode)
//      */
//     public Server(String leaderIp) {
//         this.userManager = new UserManager();
//         this.connectedClients = new CopyOnWriteArrayList<>();
//         this.actingAsLeader.set(leaderIp == null);
//         this.leaderIp = leaderIp;
        
//         // Initialize thread pools
//         this.connectionThreadPool = Executors.newCachedThreadPool();
//         this.schedulerThreadPool = Executors.newScheduledThreadPool(1);
        
//         // Get server IP
//         String localIp;
//         try {
//             localIp = InetAddress.getLocalHost().getHostAddress();
//         } catch (UnknownHostException e) {
//             localIp = "127.0.0.1";
//             logMessage("ERROR: Could not determine server IP, using localhost: " + e.getMessage());
//         }
//         this.serverIp = localIp;
        
//         // Log startup information
//         logMessage("======= STARTING SERVER AS " + (isLeader() ? "LEADER" : "FOLLOWER") + " =======");
//         logMessage("SERVER IP: " + serverIp);
//         if (!isLeader()) {
//             logMessage("Command line args: 1 argument provided - Leader IP: " + leaderIp);
//         } else {
//             logMessage("Command line args: 0 arguments provided");
//         }
        
//         // Initialize server based on role
//         if (isLeader()) {
//             startServerToServerListener();
//         } else {
//             connectToLeader(0);
//         }
        
//         // Start ping scheduler
//         startPingScheduler();
//     }
    
//     /**
//      * Logs a message with timestamp and server role
//      */
//     private void logMessage(String message) {
//         SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
//         String timestamp = dateFormat.format(new Date());
//         String serverType = isLeader() ? "[LEADER]" : "[FOLLOWER]";
//         System.out.println(timestamp + " " + serverType + " " + message);
//     }
    
//     /**
//      * Starts the server-to-server listener (leader mode)
//      */
//     private void startServerToServerListener() {
//         if (!isLeader()) return;
        
//         connectionThreadPool.execute(() -> {
//             try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
//                 serverServerSocket = serverSocket;
//                 logMessage("Leader server is listening for followers on port " + SERVER_PORT);
                
//                 while (!Thread.currentThread().isInterrupted()) {
//                     try {
//                         logMessage("Waiting for follower connections...");
//                         Socket followerSocket = serverSocket.accept();
//                         String followerIp = followerSocket.getInetAddress().getHostAddress();
//                         logMessage("NEW FOLLOWER CONNECTED! IP: " + followerIp);
                        
//                         // Create and register the server connection
//                         ServerConnection connection = new ServerConnection(
//                             followerSocket, followerIp, ServerConnectionType.FOLLOWER);
//                         serverConnections.add(connection);
//                         followerIps.add(followerIp);
//                         sendFollowersToClients();

//                         // Start the connection
//                         connection.start();
//                     } catch (IOException e) {
//                         if (!serverSocket.isClosed()) {
//                             logMessage("ERROR accepting follower connection: " + e.getMessage());
//                         } else {
//                             break; // Socket was closed, exit the loop
//                         }
//                     }
//                 }
//             } catch (IOException e) {
//                 logMessage("ERROR in server-to-server listener: " + e.getMessage());
//             }
//         });
//     }
    
//     /**
//      * Connects to the leader server (follower mode)
//      */
//     private void connectToLeader(int attemptCount) {
//         if (isLeader()) return;
//         followerIps.clear();
        
//         connectionThreadPool.execute(() -> {
//             logMessage("Attempting to connect to leader at " + leaderIp + ":" + SERVER_PORT);
//             try {
//                 Socket leaderSocket = new Socket(leaderIp, SERVER_PORT);
//                 logMessage("CONNECTED TO LEADER SUCCESSFULLY at " + leaderIp);
                
//                 // Remove any existing leader connections
//                 for (ServerConnection conn : new ArrayList<>(serverConnections)) {
//                     if (conn.getType() == ServerConnectionType.LEADER) {
//                         conn.close();
//                         serverConnections.remove(conn);
//                     }
//                 }
                
//                 // Create and register the leader connection
//                 ServerConnection connection = new ServerConnection(
//                     leaderSocket, leaderIp, ServerConnectionType.LEADER);
//                 serverConnections.add(connection);
                
//                 // Start the connection
//                 connection.start();
//             } catch (IOException e) {
//                 logMessage("ERROR connecting to leader: " + e.getMessage());
                
//                 // Check if we need to start election or retry
//                 if (attemptCount >= MAX_RECONNECT_ATTEMPTS) {
//                     logMessage("Maximum reconnection attempts reached. Starting election...");
//                     startElection();
//                 } else {
//                     logMessage("Will attempt to reconnect in " + (RECONNECT_DELAY_MS/1000) + 
//                             " seconds... (Attempt " + (attemptCount + 1) + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                    
//                     // Schedule reconnection attempt
//                     schedulerThreadPool.schedule(() -> connectToLeader(attemptCount + 1), 
//                             RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
//                 }
//             }
//         });
//     }

//     public void sendFollowersToClients() {
//         if (!isLeader()) return;
        
//         logMessage("Sending updated follower list to all clients: " + followerIps);
        
//         // Create a message with all follower IPs joined with "*" as delimiter
//         // Use the same format as in ping messages for consistency
//         String followerList = String.join(" * ", followerIps);
//         String messageContent = "SERVER_FOLLOWER_LIST:" + followerList;
        
//         // Send to all clients
//         for (ClientHandler client : connectedClients) {
//             client.sendMessage(messageContent);
//         }
//     }
    
    
//     /**
//      * Starts the ping scheduler
//      */
//     private void startPingScheduler() {
//         logMessage("Starting ping scheduler - will ping every 5 seconds");
//         schedulerThreadPool.scheduleAtFixedRate(() -> {
//             if (isLeader()) {
//                 pingFollowers();
//             } else {
//                 pingLeader();
//             }
//         }, 0, 5, TimeUnit.SECONDS);

//         schedulerThreadPool.scheduleAtFixedRate(this::logServerState, 
//     1, 3, TimeUnit.SECONDS);
//     }
    
//     /**
//      * Sends ping messages to all followers (leader mode)
//      */
//     private void pingFollowers() {
//         if (!isLeader()) return;

//         followerIps.clear();
        
//         // Build list of follower IPs
//         for (ServerConnection conn : serverConnections) {
//             if (conn.getType() == ServerConnectionType.FOLLOWER) {
//                 followerIps.add(conn.getRemoteIp());
//             }
//         }
        
//         logMessage("LEADER PING - Current leader IP: " + serverIp);
//         logMessage("Total followers to ping: " + followerIps.size());
        
//         if (followerIps.isEmpty()) {
//             logMessage("No followers connected, skipping ping");
//             return;
//         }
        
//         String pingMessage = String.join(" * ", followerIps);
        
//         // Send ping to all followers
//         for (ServerConnection conn : new ArrayList<>(serverConnections)) {
//             if (conn.getType() == ServerConnectionType.FOLLOWER) {
//                 try {
//                     logMessage("Sending ping to follower: " + conn.getRemoteIp());
//                     conn.sendMessage(pingMessage);
//                     logMessage("Ping sent successfully to follower: " + conn.getRemoteIp());
//                 } catch (IOException e) {
//                     logMessage("ERROR pinging follower " + conn.getRemoteIp() + ": " + e.getMessage());
//                     // Connection will be closed in the exception handler of the reader thread
//                 }
//             }
//         }
//     }
    
//     /**
//      * Sends ping message to the leader (follower mode)
//      */
//     private void pingLeader() {
//         if (isLeader()) return;

//         ServerConnection leaderConnection = getLeaderConnection();
        
//         if (leaderConnection == null) {
//             logMessage("Not connected to leader, cannot send ping");
//             connectToLeader(0);
//             return;
//         }
        
//         try {
//             logMessage("Sending ping to leader at " + leaderConnection.getRemoteIp() + 
//                     " with our IP: " + serverIp);
//             leaderConnection.sendMessage(serverIp);
//             logMessage("Ping sent successfully to leader");
//         } catch (IOException e) {
//             logMessage("ERROR pinging leader: " + e.getMessage());
//             // Connection will be closed in the exception handler of the reader thread
//         }
//     }
    
//     /**
//      * Gets the leader connection (follower mode)
//      */
//     private ServerConnection getLeaderConnection() {
//         for (ServerConnection conn : serverConnections) {
//             if (conn.getType() == ServerConnectionType.LEADER) {
//                 return conn;
//             }
//         }
//         return null;
//     }
    
//     /**
//      * Starts the election process
//      */
//     public void startElection() {
//         logMessage("========== STARTING ELECTION PROCESS ==========");
        
//         // Collect all server IPs including this one
//         List<String> allServerIps = new ArrayList<>(followerIps);
//         allServerIps.add(serverIp);
        
//         logMessage("Servers participating in election: " + allServerIps);
        
//         // Simple bully algorithm: highest IP becomes leader
//         String highestIp = "";
//         for (String ip : allServerIps) {
//             if (ip.compareTo(highestIp) > 0) {
//                 highestIp = ip;
//             }
//         }
        
//         logMessage("Election result: Highest IP is " + highestIp);
        
//         if (highestIp.equals(serverIp)) {
//             logMessage("THIS SERVER WON THE ELECTION!");
//             becomeLeader();
//         } else {
//             logMessage("Another server won the election: " + highestIp);
//             followNewLeader(highestIp);
//         }
        
//         logMessage("========== ELECTION PROCESS COMPLETE ==========");
//     }
    
//     // For following a new leader
//     private void followNewLeader(String newLeaderIp) {
//         // Update leader state
//         actingAsLeader.set(false);
//         this.leaderIp = newLeaderIp;
        
//         // Connect to the new leader
//         connectToLeader(0);
//     }

//     // for becoming a leader from a follower
//     private void becomeLeader() {
//         // Only execute if we're not already a leader
//         if (isLeader()) {
//             logMessage("Already in leader mode, no transition needed");
//             return;
//         }
        
//         logMessage("TRANSITIONING TO LEADER MODE");
        
//         // Clear all existing connections
//         for (ServerConnection conn : new ArrayList<>(serverConnections)) {
//             conn.close();
//         }
//         serverConnections.clear();
        
//         // Remove this server's IP from the follower list
//         followerIps.remove(serverIp);
//         logMessage("Removed self from follower list: " + serverIp);

//         // Set leader state
//         actingAsLeader.set(true);
        
//         // Notify clients about leadership change
//         broadcastToAll("SERVER_LEADERSHIP_CHANGE", null);
        
//         // Start server socket for followers
//         startServerToServerListener();

//         // Start listening to Clients now
//         start();
        
//         logMessage("Successfully transitioned to leader mode");
//     }
    

//     /**
//      * Logs the complete state of the server
//      */
//     private void logServerState() {
//         StringBuilder state = new StringBuilder();
//         state.append("\n=============== SERVER STATE ===============\n");
//         state.append("Role: ").append(isLeader() ? "LEADER" : "FOLLOWER").append("\n");
//         state.append("Server IP: ").append(serverIp).append("\n");
        
//         if (isLeader()) {
//             state.append("Leader status: This server is the leader\n");
//         } else {
//             ServerConnection leaderConn = getLeaderConnection();
//             state.append("Leader IP: ").append(leaderIp).append("\n");
//             state.append("Leader connection: ").append(leaderConn != null ? "CONNECTED" : "DISCONNECTED").append("\n");
//         }
        
//         state.append("Client connections: ").append(connectedClients.size()).append("\n");
//         state.append("Server connections: ").append(serverConnections.size()).append("\n");
        
//         // Follower IPs
//         state.append("Follower IPs (").append(followerIps.size()).append("):");
//         if (followerIps.isEmpty()) {
//             state.append(" None\n");
//         } else {
//             state.append("\n");
//             for (String ip : followerIps) {
//                 state.append("  - ").append(ip).append("\n");
//             }
//         }
        
//         state.append("===========================================");
//         logMessage(state.toString());
//     }

    
//     /**
//      * Starts the client server
//      */
//     public void start() {
//         connectionThreadPool.execute(() -> {
//             try (ServerSocket serverSocket = new ServerSocket(PORT)) {
//                 clientServerSocket = serverSocket;
//                 logMessage("Server is listening for clients on port " + PORT);
                
//                 while (!Thread.currentThread().isInterrupted()) {
//                     try {
//                         Socket socket = serverSocket.accept();
//                         logMessage("New client connected");
                        
//                         // Handle client connection
//                         ClientHandler clientHandler = new ClientHandler(socket, userManager, this);
//                         connectedClients.add(clientHandler);
//                         clientHandler.start();
//                     } catch (IOException e) {
//                         if (!serverSocket.isClosed()) {
//                             logMessage("ERROR accepting client connection: " + e.getMessage());
//                         } else {
//                             break; // Socket was closed, exit the loop
//                         }
//                     }
//                 }
//             } catch (IOException e) {
//                 logMessage("ERROR starting the server: " + e.getMessage());
//             }
//         });
//     }
    
//     /**
//      * Removes a client handler from the list
//      */
//     public void removeClient(ClientHandler client) {
//         connectedClients.remove(client);
//         logMessage("Client removed. Active connections: " + connectedClients.size());
//     }
    
//     /**
//      * Broadcasts a message to all clients except the sender
//      */
//     public void broadcastToAll(Object message, ClientHandler sender) {
//         for (ClientHandler client : connectedClients) {
//             if (client != sender) {
//                 client.sendMessage(message);
//             }
//         }
//     }
    
//     /**
//      * Checks if this server is the leader
//      */
//     public boolean isLeader() {
//         return actingAsLeader.get();
//     }

//     /**
//      * Gets the current list of follower IPs
//      */
//     public List<String> getFollowerIps() {
//         return new ArrayList<>(followerIps);
//     }
    
    
//     /**
//      * Gets the leader output stream (follower mode)
//      */
//     public ObjectOutputStream getLeaderOutputStream() {
//         ServerConnection leaderConn = getLeaderConnection();
//         return leaderConn != null ? leaderConn.getOutputStream() : null;
//     }
    
//     /**
//      * Shuts down the server and releases resources
//      */
//     public void shutdown() {
//         logMessage("Shutting down server...");
        
//         // Close all connections
//         for (ServerConnection conn : new ArrayList<>(serverConnections)) {
//             conn.close();
//         }
//         serverConnections.clear();
        
//         // Close server sockets
//         try {
//             if (clientServerSocket != null && !clientServerSocket.isClosed()) {
//                 clientServerSocket.close();
//             }
//         } catch (IOException e) {
//             logMessage("Error closing client socket: " + e.getMessage());
//         }
        
//         try {
//             if (serverServerSocket != null && !serverServerSocket.isClosed()) {
//                 serverServerSocket.close();
//             }
//         } catch (IOException e) {
//             logMessage("Error closing server socket: " + e.getMessage());
//         }
        
//         // Shutdown thread pools
//         schedulerThreadPool.shutdownNow();
//         connectionThreadPool.shutdownNow();
        
//         logMessage("Server shutdown complete");
//     }
    
//     /**
//      * Server connection types
//      */
//     private enum ServerConnectionType {
//         LEADER,
//         FOLLOWER
//     }
    
//     /**
//      * Class to handle server-to-server connections
//      */
//     private class ServerConnection {
//         private final Socket socket;
//         private final String remoteIp;
//         private final ServerConnectionType type;
//         private ObjectOutputStream outputStream;
//         private ObjectInputStream inputStream;
        
//         /**
//          * Creates a new server connection
//          */
//         public ServerConnection(Socket socket, String remoteIp, ServerConnectionType type) {
//             this.socket = socket;
//             this.remoteIp = remoteIp;
//             this.type = type;
//         }
        
//         /**
//          * Starts the connection handler
//          */
//         public void start() {
//             connectionThreadPool.execute(() -> {
//                 try {
//                     // Initialize streams
//                     outputStream = new ObjectOutputStream(socket.getOutputStream());
//                     outputStream.flush();
//                     inputStream = new ObjectInputStream(socket.getInputStream());
                    
//                     logMessage("Communication streams established with " + 
//                             type.name().toLowerCase() + ": " + remoteIp);
//                     // Read messages until connection is closed
//                     while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
//                         try {
//                             Object message = inputStream.readObject();
//                             handleMessage(message);
//                         } catch (ClassNotFoundException e) {
//                             logMessage("ERROR: Received unknown object type: " + e.getMessage());
//                         }
//                     }
//                 } catch (IOException e) {
//                     String role = type == ServerConnectionType.LEADER ? "leader" : "follower";
//                     logMessage("Connection to " + role + " lost: " + e.getMessage());
                    
//                     // Reconnect if this was the leader connection
//                     if (type == ServerConnectionType.LEADER && !isLeader()) {
//                         connectToLeader(0);
//                     }
//                 } finally {
//                     close();
//                 }
//             });
//         }
        
//         /**
//          * Handles incoming messages
//          */
//         private void handleMessage(Object message) {
//             if (message instanceof String) {
//                 String typeName = type == ServerConnectionType.LEADER ? "leader" : "follower";
//                 String pingMessage = (String) message;
//                 logMessage("PING RECEIVED from " + typeName + " [" + remoteIp + "]: " + pingMessage);
                
//                 // Process follower list if we're a follower and message contains delimiter
//                 if (type == ServerConnectionType.LEADER && pingMessage.contains("*")) {
//                     followerIps.clear();

//                     String[] ips = pingMessage.split("\\s*\\*\\s*");
//                     for (String ip : ips) {
//                         if (!ip.trim().isEmpty()) {
//                             followerIps.add(ip.trim());
//                         }
//                     }
//                     logMessage("Updated follower list from leader: " + followerIps);
//                 }
//             } else {
//                 logMessage("Received unknown message type: " + message.getClass().getName());
//             }
//         }
        
//         /**
//          * Sends a message to the remote server
//          */
//         public void sendMessage(Object message) throws IOException {
//             if (outputStream != null) {
//                 synchronized(outputStream) {  // Ensure only one thread writes at a time
//                     outputStream.writeObject(message);
//                     outputStream.flush();
//                 }
//             } else {
//                 throw new IOException("Output stream is not initialized");
//             }
//         }
                
//         /**
//          * Closes the connection and cleans up resources
//          */
//         public void close() {
//             try {
//                 if (outputStream != null) {
//                     outputStream.close();
//                 }
//                 if (inputStream != null) {
//                     inputStream.close();
//                 }
//                 if (socket != null && !socket.isClosed()) {
//                     socket.close();
//                 }
//             } catch (IOException e) {
//                 logMessage("Error closing connection resources: " + e.getMessage());
//             }
            
//             // Remove from list if still there
//             serverConnections.remove(this);
            
//             String typeName = type == ServerConnectionType.LEADER ? "Leader" : "Follower";
//             logMessage(typeName + " " + remoteIp + " disconnected. Active server connections: " + 
//                     serverConnections.size());

//             followerIps.remove(getRemoteIp());
//             if (isLeader() && type == ServerConnectionType.FOLLOWER) {
//                 // The list will be updated after the connection is removed
//                 schedulerThreadPool.schedule(() -> sendFollowersToClients(), 100, TimeUnit.MILLISECONDS);
//             }
//         }
        
//         /**
//          * Gets the remote IP address
//          */
//         public String getRemoteIp() {
//             return remoteIp;
//         }
        
//         /**
//          * Gets the connection type
//          */
//         public ServerConnectionType getType() {
//             return type;
//         }
        
//         /**
//          * Gets the output stream
//          */
//         public ObjectOutputStream getOutputStream() {
//             return outputStream;
//         }
//     }
    
//     /**
//      * Main method
//      */
//     public static void main(String[] args) {
//         Server server;
        
//         if (args.length > 0) {
//             // If there's a command-line argument, treat it as the leader IP
//             server = new Server(args[0]);
//         } else {
//             // No arguments, start as a leader
//             server = new Server();
//             server.start();
//         }
        
//         // Add shutdown hook for clean resource release
//         Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
//     }
// }

package com.syncspace.server;

import java.io.IOException;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final List<ServerConnection> serverConnections = new CopyOnWriteArrayList<>();
    // Track followers
    private final List<String> followerIps = new CopyOnWriteArrayList<>();

    // Server sockets
    private ServerSocket clientServerSocket;
    private ServerSocket serverServerSocket;
    
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
        this.schedulerThreadPool = Executors.newScheduledThreadPool(1);
        
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
            startServerToServerListener();
        } else {
            connectToLeader(0);
        }
        
        // Start ping scheduler
        startPingScheduler();
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
     * Starts the server-to-server listener (leader mode).
     */
    private void startServerToServerListener() {
        if (!isLeader()) return;
        
        connectionThreadPool.execute(() -> {
            try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
                serverServerSocket = serverSocket;
                logMessage("Leader server is listening for followers on port " + SERVER_PORT);
                
                while (!Thread.currentThread().isInterrupted()) {
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
                        sendFollowersToClients();

                        // Start connection handler
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
    private void connectToLeader(int attemptCount) {
        if (isLeader()) return;
        followerIps.clear();
        
        connectionThreadPool.execute(() -> {
            logMessage("Attempting to connect to leader at " + leaderIp + ":" + SERVER_PORT);
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
                
                // Start connection handler
                connection.start();
            } catch (IOException e) {
                logMessage("ERROR connecting to leader: " + e.getMessage());
                
                if (attemptCount >= MAX_RECONNECT_ATTEMPTS) {
                    logMessage("Maximum reconnection attempts reached. Starting election...");
                    startElection();
                } else {
                    logMessage("Will attempt to reconnect in " + (RECONNECT_DELAY_MS / 1000) + 
                            " seconds... (Attempt " + (attemptCount + 1) + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                    
                    schedulerThreadPool.schedule(() -> connectToLeader(attemptCount + 1), 
                            RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
                }
            }
        });
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
     * Starts the ping scheduler.
     */
    private void startPingScheduler() {
        logMessage("Starting ping scheduler - will ping every 5 seconds");
        schedulerThreadPool.scheduleAtFixedRate(() -> {
            if (isLeader()) {
                pingFollowers();
            } else {
                pingLeader();
            }
        }, 0, 5, TimeUnit.SECONDS);

        schedulerThreadPool.scheduleAtFixedRate(this::logServerState, 
            1, 3, TimeUnit.SECONDS);
    }
    
    /**
     * Sends ping messages to all followers (leader mode).
     */
    private void pingFollowers() {
        if (!isLeader()) return;

        // Build follower IP list from active connections
        followerIps.clear();
        for (ServerConnection conn : serverConnections) {
            if (conn.getType() == ServerConnectionType.FOLLOWER) {
                followerIps.add(conn.getRemoteIp());
            }
        }
        
        logMessage("LEADER PING - Current leader IP: " + serverIp);
        logMessage("Total followers to ping: " + followerIps.size());
        
        if (followerIps.isEmpty()) {
            logMessage("No followers connected, skipping ping");
            return;
        }
        
        String pingMessage = String.join(" * ", followerIps);
        
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            if (conn.getType() == ServerConnectionType.FOLLOWER) {
                try {
                    logMessage("Sending ping to follower: " + conn.getRemoteIp());
                    conn.sendMessage(pingMessage);
                    logMessage("Ping sent successfully to follower: " + conn.getRemoteIp());
                } catch (IOException e) {
                    logMessage("ERROR pinging follower " + conn.getRemoteIp() + ": " + e.getMessage());
                }
            }
        }
    }
    
    /**
     * Sends a ping message to the leader (follower mode).
     */
    private void pingLeader() {
        if (isLeader()) return;

        ServerConnection leaderConnection = getLeaderConnection();
        
        if (leaderConnection == null) {
            logMessage("Not connected to leader, cannot send ping");
            connectToLeader(0);
            return;
        }
        
        try {
            logMessage("Sending ping to leader at " + leaderConnection.getRemoteIp() + 
                    " with our IP: " + serverIp);
            leaderConnection.sendMessage(serverIp);
            logMessage("Ping sent successfully to leader");
        } catch (IOException e) {
            logMessage("ERROR pinging leader: " + e.getMessage());
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
        connectToLeader(0);
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
        
        broadcastToAll("SERVER_LEADERSHIP_CHANGE", null);
        
        startServerToServerListener();
        start();
        
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
        
        for (ServerConnection conn : new ArrayList<>(serverConnections)) {
            conn.close();
        }
        serverConnections.clear();
        
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
        
        schedulerThreadPool.shutdownNow();
        connectionThreadPool.shutdownNow();
        
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
        
        public ServerConnection(Socket socket, String remoteIp, ServerConnectionType type) {
            this.socket = socket;
            this.remoteIp = remoteIp;
            this.type = type;
        }
        
        /**
         * Starts the connection handler.
         */
        public void start() {
            connectionThreadPool.execute(() -> {
                try {
                    outputStream = new ObjectOutputStream(socket.getOutputStream());
                    outputStream.flush();
                    inputStream = new ObjectInputStream(socket.getInputStream());
                    
                    logMessage("Communication streams established with " + type.name().toLowerCase() + ": " + remoteIp);
                    
                    while (!Thread.currentThread().isInterrupted() && !socket.isClosed()) {
                        try {
                            Object message = inputStream.readObject();
                            handleMessage(message);
                        } catch (ClassNotFoundException e) {
                            logMessage("ERROR: Received unknown object type: " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    String role = type == ServerConnectionType.LEADER ? "leader" : "follower";
                    logMessage("Connection to " + role + " lost: " + e.getMessage());
                    
                    if (type == ServerConnectionType.LEADER && !isLeader()) {
                        connectToLeader(0);
                    }
                } finally {
                    close();
                }
            });
        }
        
        /**
         * Handles incoming messages.
         */
        private void handleMessage(Object message) {
            if (message instanceof String) {
                String typeName = type == ServerConnectionType.LEADER ? "leader" : "follower";
                String pingMessage = (String) message;
                logMessage("PING RECEIVED from " + typeName + " [" + remoteIp + "]: " + pingMessage);
                
                // If leader, update follower list based on ping message
                if (type == ServerConnectionType.LEADER && pingMessage.contains("*")) {
                    followerIps.clear();
                    String[] ips = pingMessage.split("\\s*\\*\\s*");
                    for (String ip : ips) {
                        if (!ip.trim().isEmpty()) {
                            followerIps.add(ip.trim());
                        }
                    }
                    logMessage("Updated follower list from leader: " + followerIps);
                }
            } else {
                logMessage("Received unknown message type: " + message.getClass().getName());
            }
        }
        
        /**
         * Sends a message to the remote server.
         */
        public void sendMessage(Object message) throws IOException {
            if (outputStream != null) {
                synchronized (outputStream) {
                    outputStream.writeObject(message);
                    outputStream.flush();
                }
            } else {
                throw new IOException("Output stream is not initialized");
            }
        }
        
        /**
         * Closes the connection and cleans up resources.
         */
        public void close() {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
                if (inputStream != null) {
                    inputStream.close();
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
                schedulerThreadPool.schedule(() -> sendFollowersToClients(), 100, TimeUnit.MILLISECONDS);
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
         * Gets the output stream.
         */
        public ObjectOutputStream getOutputStream() {
            return outputStream;
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
            server.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    }
}
