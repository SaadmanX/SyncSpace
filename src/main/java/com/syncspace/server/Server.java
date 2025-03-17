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
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Server {
    private static final int PORT = 12345; // Client connection port
    private static final int SERVER_PORT = 12346; // Server-to-server communication port
    
    private UserManager userManager;
    private List<ClientHandler> connectedClients;
    private List<String> followerIps;
    private Boolean isLeader = false;
    private String leaderIp;
    private String serverIp;
    private ScheduledExecutorService pingScheduler;
    
    // For leader to track follower connections
    private List<Socket> followerSockets = new ArrayList<>();
    private List<ObjectOutputStream> followerOutputStreams = new ArrayList<>();
    
    // For follower to track leader connection
    private Socket leaderSocket;
    private ObjectOutputStream leaderOutputStream;
    
    // For logging

    public Server() {
        userManager = new UserManager();
        connectedClients = new CopyOnWriteArrayList<>();
        isLeader = true;
        followerIps = new ArrayList<String>();
        
        logMessage("======= STARTING SERVER AS LEADER =======");
        logMessage("Command line args: 0 arguments provided");
        
        // Get this server's IP address
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
            logMessage("SERVER IP: " + serverIp);
        } catch (UnknownHostException e) {
            serverIp = "127.0.0.1";
            logMessage("ERROR: Could not determine server IP, using localhost: " + e.getMessage());
        }
        
        // Start server-to-server listener for incoming follower connections
        startServerToServerListener();
        
        // Start ping scheduler
        startPingScheduler();
    }

    public Server(String lIp) {
        userManager = new UserManager();
        connectedClients = new CopyOnWriteArrayList<>();
        isLeader = false;
        leaderIp = lIp;
        
        logMessage("======= STARTING SERVER AS FOLLOWER =======");
        logMessage("Command line args: 1 argument provided - Leader IP: " + lIp);
        
        // Get this server's IP address
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
            logMessage("SERVER IP: " + serverIp);
        } catch (UnknownHostException e) {
            serverIp = "127.0.0.1";
            logMessage("ERROR: Could not determine server IP, using localhost: " + e.getMessage());
        }
        
        // Connect to leader
        connectToLeader(0);
        
        // Start ping scheduler
        startPingScheduler();
    }
    
    private void logMessage(String message) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String timestamp = dateFormat.format(new Date());
        String serverType = isLeader ? "[LEADER]" : "[FOLLOWER]";
        System.out.println(timestamp + " " + serverType + " " + message);
    }
    
    private void startServerToServerListener() {
        if (isLeader) {
            new Thread(() -> {
                try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
                    logMessage("Leader server is listening for followers on port " + SERVER_PORT);
                    
                    while (true) {
                        logMessage("Waiting for follower connections...");
                        Socket followerSocket = serverSocket.accept();
                        String followerIp = followerSocket.getInetAddress().getHostAddress();
                        logMessage("NEW FOLLOWER CONNECTED! IP: " + followerIp);
                        
                        // Add follower to the list
                        followerIps.add(followerIp);
                        followerSockets.add(followerSocket);
                        
                        // Create output stream for the follower
                        logMessage("Setting up communication streams with follower: " + followerIp);
                        ObjectOutputStream out = new ObjectOutputStream(followerSocket.getOutputStream());
                        out.flush();
                        followerOutputStreams.add(out);
                        
                        // Start a thread to listen for messages from this follower
                        startFollowerListener(followerSocket, followerIp);
                        
                        logMessage("Follower registration complete. Total followers: " + followerIps.size());
                    }
                } catch (IOException e) {
                    logMessage("ERROR in server-to-server listener: " + e.getMessage());
                }
            }).start();
        }
    }
    
    private void startFollowerListener(Socket followerSocket, String followerIp) {
        new Thread(() -> {
            logMessage("Starting listener thread for follower: " + followerIp);
            try {
                ObjectInputStream in = new ObjectInputStream(followerSocket.getInputStream());
                logMessage("Input stream created for follower: " + followerIp);
                
                while (true) {
                    logMessage("Waiting for messages from follower: " + followerIp);
                    Object message = in.readObject();
                    String pingMessage = (String) message;
                    logMessage("PING RECEIVED from follower [" + followerIp + "]: " + pingMessage);
                }
            } catch (IOException | ClassNotFoundException e) {
                logMessage("ERROR reading from follower " + followerIp + ": " + e.getMessage());
                // Remove disconnected follower
                int index = followerSockets.indexOf(followerSocket);
                if (index >= 0) {
                    followerIps.remove(followerIp);
                    followerSockets.remove(index);
                    followerOutputStreams.remove(index);
                    logMessage("Follower " + followerIp + " DISCONNECTED. Total followers: " + followerIps.size());
                }
            }
        }).start();
    }
    
    private void connectToLeader(int count) {
        logMessage("Attempting to connect to leader at " + leaderIp + ":" + SERVER_PORT);
        new Thread(() -> {
            try {
                leaderSocket = new Socket(leaderIp, SERVER_PORT);
                logMessage("CONNECTED TO LEADER SUCCESSFULLY at " + leaderIp);
                
                // Set up streams
                logMessage("Setting up communication streams with leader");
                leaderOutputStream = new ObjectOutputStream(leaderSocket.getOutputStream());
                leaderOutputStream.flush();
                logMessage("Output stream created for leader communication");
                
                // Start a thread to listen for messages from the leader
                startLeaderListener();
                
            } catch (IOException e) {
                logMessage("ERROR connecting to leader: " + e.getMessage());
                // Try to reconnect after a delay
                logMessage("Will attempt to reconnect in 5 seconds...");
                try {
                    if (count >= 3) {
                        startElection();
                    }
                    Thread.sleep(5000);
                    connectToLeader(count + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logMessage("Reconnection attempt interrupted");
                }
            }
        }).start();
    }
    
    private void startLeaderListener() {
        new Thread(() -> {
            logMessage("Starting listener thread for leader messages");
            try {
                ObjectInputStream in = new ObjectInputStream(leaderSocket.getInputStream());
                logMessage("Input stream created for leader communication");
                
                while (true) {
                    logMessage("Waiting for messages from leader");
                    Object message = in.readObject();
                    String pingMessage = (String) message;
                    logMessage("PING RECEIVED from leader: " + pingMessage);
                    followerIps = Arrays.asList(pingMessage.split("\\s*\\*\\s*"));
                }
            } catch (IOException | ClassNotFoundException e) {
                logMessage("LOST CONNECTION TO LEADER: " + e.getMessage());
                // Try to reconnect
                try {
                    leaderSocket.close();
                    logMessage("Closed previous leader socket");
                } catch (IOException ex) {
                    // Ignore
                }
                
                logMessage("Will attempt to reconnect to leader in 5 seconds...");
                try {
                    Thread.sleep(5000);
                    connectToLeader(0);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logMessage("Reconnection attempt interrupted");
                }
            }
        }).start();
    }
    
    private void startPingScheduler() {
        logMessage("Starting ping scheduler - will ping every 5 seconds");
        pingScheduler = Executors.newScheduledThreadPool(1);
        pingScheduler.scheduleAtFixedRate(() -> {
            if (isLeader) {
                pingFollowers();
            } else {
                pingLeader();
            }
        }, 0, 5, TimeUnit.SECONDS);
    }
    
    private void pingFollowers() {
        // Leader pings all followers with its own IP
        logMessage("LEADER PING - Current leader IP: " + serverIp);
        logMessage("Total followers to ping: " + followerIps.size());
        
        if (followerIps.isEmpty()) {
            logMessage("No followers connected, skipping ping");
            return;
        }
        
        for (int i = 0; i < followerOutputStreams.size(); i++) {
            try {
                logMessage("Sending ping to follower: " + followerIps.get(i));
                followerOutputStreams.get(i).writeObject(String.join(" * ", followerIps));
                followerOutputStreams.get(i).flush();
                logMessage("Ping sent successfully to follower: " + followerIps.get(i));
            } catch (IOException e) {
                logMessage("ERROR pinging follower " + followerIps.get(i) + ": " + e.getMessage());
                // Remove failed follower
                logMessage("Removing unresponsive follower: " + followerIps.get(i));
                followerIps.remove(i);
                followerSockets.remove(i);
                followerOutputStreams.remove(i);
                i--; // Adjust index after removal
            }
        }
    }
    
    private void pingLeader() {
        // Follower pings leader with its own IP
        if (leaderOutputStream != null) {
            try {
                logMessage("Sending ping to leader at " + leaderIp + " with our IP: " + serverIp);
                leaderOutputStream.writeObject(serverIp);
                leaderOutputStream.flush();
                logMessage("Ping sent successfully to leader");
            } catch (IOException e) {
                logMessage("ERROR pinging leader: " + e.getMessage());
                logMessage("Will attempt to reconnect to leader");
                try {
                    if (leaderSocket != null) {
                        leaderSocket.close();
                    }
                } catch (IOException ex) {
                    // Ignore
                }
                
                leaderOutputStream = null;
                connectToLeader(0);
            }
        } else {
            logMessage("Not connected to leader, cannot send ping");
            connectToLeader(0);
        }
    }

    public void startElection() {
        logMessage("Should start election now");
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            logMessage("Server is listening for clients on port " + PORT);
            while (true) {
                Socket socket = serverSocket.accept();
                logMessage("New client connected");
                // Handle client connection in a new thread
                ClientHandler clientHandler = new ClientHandler(socket, userManager, this);
                connectedClients.add(clientHandler);
                clientHandler.start();
            }
        } catch (IOException e) {
            logMessage("ERROR starting the server: " + e.getMessage());
        }
    }
    
    public void removeClient(ClientHandler client) {
        connectedClients.remove(client);
        logMessage("Client removed. Active connections: " + connectedClients.size());
    }
    
    public void broadcastToAll(Object message, ClientHandler sender) {
        for (ClientHandler client : connectedClients) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }

    public boolean isLeader() {
        return isLeader;
    }

    public ObjectOutputStream getLeaderOutputStream() {
        return leaderOutputStream;
    }

    public static void main(String[] args) {
        Server server;
        
        if (args.length > 0) {
            // If there's a command-line argument, treat it as the leader IP
            server = new Server(args[0]);
        } else {
            // No arguments, start as a leader
            server = new Server();
        }
        
        server.start();
    }
}

