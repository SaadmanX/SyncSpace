package com.syncspace.server;

import com.syncspace.common.Message;
import com.syncspace.common.User;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class ClientHandler extends Thread {
    private Socket socket;
    private UserManager userManager;
    private WhiteboardSession whiteboardSession;
    private ObjectInputStream inputStream;
    private ObjectOutputStream outputStream;
    private String username;
    private Server server;
    private boolean isRunning = true;

    public ClientHandler(Socket socket, UserManager userManager, WhiteboardSession whiteboardSession, Server server) {
        this.socket = socket;
        this.userManager = userManager;
        this.whiteboardSession = whiteboardSession;
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
            while (isRunning) {
                try {
                    Object input = inputStream.readObject();
                    if (input instanceof Message) {
                        handleMessage((Message) input);
                    }
                } catch (ClassNotFoundException e) {
                    System.err.println("Error reading message: " + e.getMessage());
                    break;
                } catch (IOException e) {
                    System.err.println("Connection lost: " + e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Client disconnected: " + e.getMessage());
        } finally {
            // Clean up when client disconnects
            handleClientDisconnect();
        }
    }

    private void handleClientDisconnect() {
        if (username != null) {
            userManager.removeUser(username);
            System.out.println("User " + username + " disconnected");
            
            // Notify other clients that this user has left
            Message leaveMessage = new Message(Message.MessageType.USER_LEAVE, 
                "has left the whiteboard session", username);
            server.broadcastToAll(leaveMessage, this);
        }
        
        try {
            isRunning = false;
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        server.removeClient(this);
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
                    // Add user to whiteboard session
                    whiteboardSession.addUser(new User(username, username));
                    System.out.println("User " + username + " connected");
                    
                    // Tell the client if they're connected to the leader or a follower
                    String serverRole = server.isLeader() ? "LEADER" : "FOLLOWER";
                    outputStream.writeObject(new Message(Message.MessageType.TEXT, 
                            "Connected to " + serverRole + " server", "System"));
                    outputStream.flush();
                    
                    // Broadcast to all clients that a new user has joined
                    Message joinMessage = new Message(Message.MessageType.USER_JOIN, 
                                                   "has joined the whiteboard session", username);
                    server.broadcastToAll(joinMessage, this);
                }
            }
        } catch (ClassNotFoundException e) {
            System.err.println("Error during user registration: " + e.getMessage());
        }
    }

    private void handleMessage(Message message) {
        System.out.println("Received message: " + message.getContent() + " from " + username);
        
        // If this is a follower server, forward messages to the leader server
        if (!server.isLeader() && message.getType() != Message.MessageType.TEXT) {
            System.out.println("Forwarding message to leader server");
            // Actual message forwarding implementation would be handled by the server
            return;
        }
        
        // Handle different types of messages
        switch (message.getType()) {
            case TEXT:
                // Broadcast chat message to all clients
                server.broadcastToAll(message, this);
                break;
            case DRAW:
                // Handle drawing action
                server.broadcastToAll(message, this);
                break;
            case CLEAR:
                // Handle clear action
                server.broadcastToAll(message, this);
                break;
            default:
                break;
        }
    }
    
    public void sendMessage(Object message) {
        try {
            outputStream.writeObject(message);
            outputStream.flush();
        } catch (IOException e) {
            System.err.println("Error sending message to client: " + e.getMessage());
            // If sending fails, consider the client disconnected
            handleClientDisconnect();
        }
    }
    
    public String getUsername() {
        return username;
    }
}