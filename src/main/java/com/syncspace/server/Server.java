package com.syncspace.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Server {
    private static final int PORT = 12345; // Define the server port
    private UserManager userManager;
    private WhiteboardSession whiteboardSession;
    private List<ClientHandler> connectedClients;

    public Server() {
        userManager = new UserManager();
        whiteboardSession = new WhiteboardSession();
        connectedClients = new CopyOnWriteArrayList<>();
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server is listening on port " + PORT);
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("New client connected");
                // Handle client connection in a new thread
                ClientHandler clientHandler = new ClientHandler(socket, userManager, whiteboardSession, this);
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
        Server server = new Server();
        server.start();
    }
}