package com.syncspace.server;

import com.syncspace.common.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ClientHandler extends Thread {
    private Socket socket;
    private UserManager userManager;
    private ObjectInputStream inputStream;
    private ObjectOutputStream outputStream;
    private String username;
    private Server server;

    public ClientHandler(Socket socket, UserManager userManager, Server server) {
        this.socket = socket;
        this.userManager = userManager;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            // Important: Create the output stream first, and flush it immediately
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            // Then create the input stream
            inputStream = new ObjectInputStream(socket.getInputStream());
            
            // Handle user registration or authentication
            handleUserRegistration();
            
            // Listen for client messages
            while (true) {
                try {
                    Object input = inputStream.readObject();
                    if (input instanceof Message) {
                        handleMessage((Message) input);
                    }
                } catch (ClassNotFoundException e) {
                    System.err.println("Error reading message: " + e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Client disconnected: " + e.getMessage());
        } finally {
            // Clean up when client disconnects
            if (username != null) {
                userManager.removeUser(username);
                System.out.println("User " + username + " disconnected");
                
                // Notify other clients that this user has left
                Message leaveMessage = new Message(Message.MessageType.USER_LEAVE, 
                    "has left the whiteboard session", username);
                broadcastToAll(leaveMessage);
            }
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            server.removeClient(this);
        }
    }

    private void handleUserRegistration() throws IOException {
        try {
            Object input = inputStream.readObject();
            if (input instanceof String) {
                username = (String) input;
                boolean registered = userManager.registerUser(username);
                outputStream.writeObject(registered);
                outputStream.flush();
                
                if (registered) {
                    System.out.println("User " + username + " connected");
                    
                    // Send drawing history to the new client
                    server.sendActionHistoryToClient(this);
                    
                    // Send follower list to the new client if the server is a leader
                    if (server.isLeader()) {
                        String followerList = String.join(" * ", server.getFollowerIps());
                        sendMessage("SERVER_FOLLOWER_LIST:" + followerList);
                    }
                    
                    // Broadcast to all clients that a new user has joined
                    Message joinMessage = new Message(Message.MessageType.USER_JOIN, 
                                                   "has joined the whiteboard session", username);
                    broadcastToAll(joinMessage);
                }
            }
        } catch (ClassNotFoundException e) {
            System.err.println("Error during user registration: " + e.getMessage());
        }
    }

    private void handleMessage(Message message) {
        System.out.println("Received message: " + message.getContent() + " from " + username);
        // Handle different types of messages
        switch (message.getType()) {
            case TEXT:
                // Broadcast chat message to all clients
                broadcastToAll(message);
                break;
            case DRAW:
                // Handle drawing action
                broadcastToAll(message);
                break;
            case CLEAR:
                // Handle clear action
                broadcastToAll(message);
                break;
            default:
                break;
        }
    }

    // Add this method to handle string messages including time sync
    public void handleStringMessage(String message) {
        if (message.startsWith("TIME_SYNC:")) {
            handleTimeSync(message);
        } else {
            // Handle other string messages as needed
            System.out.println("Received string message: " + message + " from " + username);
        }
    }

    private void handleTimeSync(String message) {
        String[] parts = message.split(":");
        if (parts.length < 2) return;
        
        String command = parts[1];
        
        if ("REQUEST".equals(command)) {
            // Server is requesting our time
            long clientTime = System.currentTimeMillis();
            sendMessage("TIME_SYNC:RESPONSE:" + clientTime);
        } else if ("RESPONSE".equals(command) && parts.length >= 3) {
            // Client has responded with their time
            try {
                long clientTime = Long.parseLong(parts[2]);
                server.processClientTime(username, clientTime);
            } catch (NumberFormatException e) {
                System.err.println("Invalid time format received: " + parts[2]);
            }
        } else if ("ADJUST".equals(command) && parts.length >= 3) {
            // Server tells client to adjust their time
            // Just forward to client, WhiteboardClient will handle
            sendMessage(message);
        }
    }

    
    public void sendMessage(Object message) {
        try {
            outputStream.writeObject(message);
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void broadcastToAll(Message message) {
        server.broadcastToAll(message, this);
    }

    public String getUsername() {
        return username;
    }
}