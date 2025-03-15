package com.syncspace.server;

import com.syncspace.server.replication.ReplicationManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

public class Server {
    private static final int DEFAULT_PORT = 12345;
    private int port;
    private int replicationPort;
    private UserManager userManager;
    private WhiteboardSession whiteboardSession;
    private List<ClientHandler> connectedClients;
    private ReplicationManager replicationManager;
    private String serverAddress;
    private boolean running = true;

    public Server(String serverAddress, int port, int replicationPort) {
        this.serverAddress = serverAddress;
        this.port = port;
        this.replicationPort = replicationPort;
        this.userManager = new UserManager();
        this.whiteboardSession = new WhiteboardSession();
        this.connectedClients = new CopyOnWriteArrayList<>();
        
        // Initialize the replication manager
        this.replicationManager = new ReplicationManager(serverAddress, port, replicationPort);
    }

    public void start() {
        // Start replication first
        replicationManager.start();
        
        // Then start the client-facing server
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server is listening on port " + port);
            System.out.println("Replication service on port " + replicationPort);
            
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    System.out.println("New client connected from " + socket.getInetAddress());
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
        } catch (IOException e) {
            System.err.println("Error starting the server: " + e.getMessage());
        }
    }
    
    public void shutdown() {
        running = false;
        
        // Close all client connections
        for (ClientHandler client : connectedClients) {
            client.shutdown();
        }
        
        // Shutdown replication manager
        if (replicationManager != null) {
            replicationManager.shutdown();
        }
        
        System.out.println("Server shutdown complete");
    }
    
    public void removeClient(ClientHandler client) {
        connectedClients.remove(client);
        System.out.println("Client removed. Active connections: " + connectedClients.size());
    }
    
    public void broadcastToAll(Object message, ClientHandler sender) {
        // First replicate this state change if we're sending a Message
        if (message instanceof com.syncspace.common.Message) {
            com.syncspace.common.Message msg = (com.syncspace.common.Message) message;
            String key = "whiteboard.action." + msg.getTimestamp();
            replicationManager.replicateData(key, message);
        }
        
        // Then broadcast to all local clients
        for (ClientHandler client : connectedClients) {
            // Don't send back to the sender (optional)
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }
    
    // Method to be called when a server failure is detected
    public void handleServerFailure() {
        // Notify clients that they need to reconnect to a new server
        com.syncspace.common.Message failureMsg = new com.syncspace.common.Message(
            com.syncspace.common.Message.MessageType.TEXT,
            "Server is experiencing issues. You'll be reconnected to another server.",
            "SYSTEM"
        );
        
        for (ClientHandler client : connectedClients) {
            client.sendMessage(failureMsg);
        }
    }
    
    public ReplicationManager getReplicationManager() {
        return replicationManager;
    }
    
    public boolean isLeaderServer() {
        return replicationManager.isLeader();
    }

    public static void main(String[] args) {
        // Parse command-line arguments for server ports
        int clientPort = DEFAULT_PORT;
        int replicationPort = DEFAULT_PORT + 1000; // Default replication port
        String serverAddress = "localhost";
        
        // Check for command-line arguments
        if (args.length >= 3) {
            try {
                serverAddress = args[0];
                clientPort = Integer.parseInt(args[1]);
                replicationPort = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number. Using defaults.");
            }
        } else {
            // Try to load from properties
            try {
                Properties props = new Properties();
                props.load(new FileInputStream("config.properties"));
                
                clientPort = Integer.parseInt(props.getProperty("server.port", String.valueOf(DEFAULT_PORT)));
                replicationPort = Integer.parseInt(props.getProperty("replication.port", 
                                                 String.valueOf(DEFAULT_PORT + 1000)));
                serverAddress = props.getProperty("server.address", "localhost");
            } catch (IOException | NumberFormatException e) {
                System.out.println("Could not load config, using defaults.");
            }
        }
        
        // Create and start the server
        Server server = new Server(serverAddress, clientPort, replicationPort);
        
        // Add shutdown hook to gracefully shut down the server
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down server...");
            server.shutdown();
        }));
        
        server.start();
    }
}