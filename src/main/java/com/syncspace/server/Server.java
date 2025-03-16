// package com.syncspace.server;

// import java.io.IOException;
// import java.net.ServerSocket;
// import java.net.Socket;
// import java.util.ArrayList;
// import java.util.List;
// import java.util.concurrent.CopyOnWriteArrayList;

// public class Server {
//     private static final int PORT = 12345; // Define the server port
//     private UserManager userManager;
//     private List<ClientHandler> connectedClients;
//     private List<String> followerIps;
//     private Boolean isLeader = false;
//     private String leaderIp;

//     public Server() {
//         userManager = new UserManager();
//         connectedClients = new CopyOnWriteArrayList<>();
//         isLeader = true;
//         followerIps = new ArrayList<String>();
//     }

//     public Server(String lIp) {
//         isLeader = false;
//         leaderIp = lIp;
//     }

//     public void start() {
//         try (ServerSocket serverSocket = new ServerSocket(PORT)) {
//             System.out.println("Server is listening on port " + PORT);
//             while (true) {
//                 Socket socket = serverSocket.accept();
//                 System.out.println("New client connected");
//                 // Handle client connection in a new thread
//                 ClientHandler clientHandler = new ClientHandler(socket, userManager, this);
//                 connectedClients.add(clientHandler);
//                 clientHandler.start();
//             }
//         } catch (IOException e) {
//             System.err.println("Error starting the server: " + e.getMessage());
//         }
//     }
    
//     public void removeClient(ClientHandler client) {
//         connectedClients.remove(client);
//         System.out.println("Client removed. Active connections: " + connectedClients.size());
//     }
    
//     public void broadcastToAll(Object message, ClientHandler sender) {
//         for (ClientHandler client : connectedClients) {
//             // Don't send back to the sender (optional)
//             if (client != sender) {
//                 client.sendMessage(message);
//             }
//         }
//     }

//     public static void main(String[] args) {
//         Server server = new Server();
//         server.start();
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
import java.util.ArrayList;
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

    public Server() {
        userManager = new UserManager();
        connectedClients = new CopyOnWriteArrayList<>();
        isLeader = true;
        followerIps = new ArrayList<String>();
        
        // Get this server's IP address
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
            System.out.println("Server IP: " + serverIp);
        } catch (UnknownHostException e) {
            serverIp = "127.0.0.1";
            System.err.println("Could not determine server IP, using localhost");
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
        
        // Get this server's IP address
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
            System.out.println("Server IP: " + serverIp);
        } catch (UnknownHostException e) {
            serverIp = "127.0.0.1";
            System.err.println("Could not determine server IP, using localhost");
        }
        
        // Connect to leader
        connectToLeader();
        
        // Start ping scheduler
        startPingScheduler();
    }
    
    private void startServerToServerListener() {
        if (isLeader) {
            new Thread(() -> {
                try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
                    System.out.println("Leader server is listening for followers on port " + SERVER_PORT);
                    
                    while (true) {
                        Socket followerSocket = serverSocket.accept();
                        String followerIp = followerSocket.getInetAddress().getHostAddress();
                        System.out.println("New follower connected from: " + followerIp);
                        
                        // Add follower to the list
                        followerIps.add(followerIp);
                        followerSockets.add(followerSocket);
                        
                        // Create output stream for the follower
                        ObjectOutputStream out = new ObjectOutputStream(followerSocket.getOutputStream());
                        out.flush();
                        followerOutputStreams.add(out);
                        
                        // Start a thread to listen for messages from this follower
                        startFollowerListener(followerSocket, followerIp);
                    }
                } catch (IOException e) {
                    System.err.println("Error in server-to-server listener: " + e.getMessage());
                }
            }).start();
        }
    }
    
    private void startFollowerListener(Socket followerSocket, String followerIp) {
        new Thread(() -> {
            try {
                ObjectInputStream in = new ObjectInputStream(followerSocket.getInputStream());
                
                while (true) {
                    Object message = in.readObject();
                    if (message instanceof String) {
                        String pingMessage = (String) message;
                        System.out.println("Received ping from follower: " + pingMessage);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("Error reading from follower " + followerIp + ": " + e.getMessage());
                // Remove disconnected follower
                int index = followerSockets.indexOf(followerSocket);
                if (index >= 0) {
                    followerIps.remove(followerIp);
                    followerSockets.remove(index);
                    followerOutputStreams.remove(index);
                    System.out.println("Follower " + followerIp + " disconnected");
                }
            }
        }).start();
    }
    
    private void connectToLeader() {
        new Thread(() -> {
            try {
                leaderSocket = new Socket(leaderIp, SERVER_PORT);
                System.out.println("Connected to leader at " + leaderIp);
                
                // Set up streams
                leaderOutputStream = new ObjectOutputStream(leaderSocket.getOutputStream());
                leaderOutputStream.flush();
                
                // Start a thread to listen for messages from the leader
                startLeaderListener();
                
            } catch (IOException e) {
                System.err.println("Error connecting to leader: " + e.getMessage());
                // Try to reconnect after a delay
                try {
                    Thread.sleep(5000);
                    connectToLeader();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }
    
    private void startLeaderListener() {
        new Thread(() -> {
            try {
                ObjectInputStream in = new ObjectInputStream(leaderSocket.getInputStream());
                
                while (true) {
                    Object message = in.readObject();
                    if (message instanceof String) {
                        String pingMessage = (String) message;
                        System.out.println("Received ping from leader: " + pingMessage);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("Lost connection to leader: " + e.getMessage());
                // Try to reconnect
                try {
                    leaderSocket.close();
                } catch (IOException ex) {
                    // Ignore
                }
                
                try {
                    Thread.sleep(5000);
                    connectToLeader();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }
    
    private void startPingScheduler() {
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
        System.out.println("Leader IP: " + serverIp);
        
        for (int i = 0; i < followerOutputStreams.size(); i++) {
            try {
                followerOutputStreams.get(i).writeObject(serverIp);
                followerOutputStreams.get(i).flush();
            } catch (IOException e) {
                System.err.println("Error pinging follower " + followerIps.get(i) + ": " + e.getMessage());
            }
        }
    }
    
    private void pingLeader() {
        // Follower pings leader with its own IP
        if (leaderOutputStream != null) {
            try {
                leaderOutputStream.writeObject(serverIp);
                leaderOutputStream.flush();
                System.out.println("Pinged leader with: " + serverIp);
            } catch (IOException e) {
                System.err.println("Error pinging leader: " + e.getMessage());
            }
        }
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening on port " + PORT);
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected");
                // Handle client connection in a new thread
                ClientHandler clientHandler = new ClientHandler(socket, userManager, this);
                connectedClients.add(clientHandler);
                clientHandler.start();
            }
        } catch (IOException e) {
            System.err.println("Error starting the server: " + e.getMessage());
        }
    }
    
    public void removeClient(ClientHandler client) {
        connectedClients.remove(client);
        System.out.println("Client removed. Active connections: " + connectedClients.size());
    }
    
    public void broadcastToAll(Object message, ClientHandler sender) {
        for (ClientHandler client : connectedClients) {
            // Don't send back to the sender (optional)
            if (client != sender) {
                client.sendMessage(message);
            }
        }
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