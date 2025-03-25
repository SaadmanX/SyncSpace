package com.syncspace.server;

import java.io.IOException;
import com.syncspace.common.Message;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Database {
    private static final String DATABASE_FILE = "/Users/smitster1403/Desktop/559/database_log.txt";
    private static final int PORT = 1500;
    
    public static void main(String[] args) {
        System.out.println("Database server starting on port " + PORT);
        
        // Create a thread pool for handling multiple client connections
        ExecutorService threadPool = Executors.newCachedThreadPool();
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Database server is running and waiting for connections...");
            
            // Continuously accept connections
            while (true) {
                try {
                    // Wait for a new connection
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New connection from: " + clientSocket.getInetAddress().getHostAddress());
                    
                    // Handle this connection in a separate thread
                    threadPool.submit(() -> handleClientConnection(clientSocket));
                    
                } catch (IOException e) {
                    System.err.println("Error accepting connection: " + e.getMessage());
                    // Brief pause before trying again
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Fatal error starting database server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            threadPool.shutdown();
        }
    }
    
    private static void handleClientConnection(Socket clientSocket) {
        System.out.println("Handling connection from: " + clientSocket.getInetAddress().getHostAddress());
        
        try (
            ObjectOutputStream outStream = new ObjectOutputStream(clientSocket.getOutputStream());
            ObjectInputStream inStream = new ObjectInputStream(clientSocket.getInputStream())
        ) {
            // Send ready signal
            outStream.writeObject("DB_READY");
            outStream.flush();
            System.out.println("Sent DB_READY signal");
            
            // Send existing drawing data from file
            sendDrawingHistory(outStream);
            
            // Process incoming messages until connection closes
            while (!clientSocket.isClosed()) {
                try {
                    Object message = inStream.readObject();
                    System.out.println("Received message of type: " + message.getClass().getName());
                    
                    if (message instanceof Message) {
                        Message msg = (Message) message;
                        System.out.println("Received drawing action: " + msg.getContent());
                        
                        // Store message in the database file
                        appendToDatabase(msg.getContent());
                    } else if (message instanceof String) {
                        String strMsg = (String) message;
                        System.out.println("Received string message: " + strMsg);
                        
                        if (strMsg.equals("SERVER_READY")) {
                            System.out.println("Server confirmed ready state");
                        }
                    }
                } catch (ClassNotFoundException e) {
                    System.err.println("Unknown object type received: " + e.getMessage());
                } catch (IOException e) {
                    System.err.println("Connection error: " + e.getMessage());
                    break;  // Exit the loop on IO exception - connection likely lost
                }
            }
        } catch (IOException e) {
            System.err.println("Error with client connection: " + e.getMessage());
        } finally {
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
                System.out.println("Connection closed with: " + clientSocket.getInetAddress().getHostAddress());
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }
    
    private static void sendDrawingHistory(ObjectOutputStream outStream) {
        try (java.io.FileReader fr = new java.io.FileReader(DATABASE_FILE);
             java.io.BufferedReader br = new java.io.BufferedReader(fr)) {
            
            StringBuilder fileContents = new StringBuilder();
            String line;
            
            fileContents.append("ALLDRAW:");
            while ((line = br.readLine()) != null) {
                System.out.println("Reading from DB: " + line);
                fileContents.append(line).append("\n");
            }
            
            // Send the content to the client
            System.out.println("Sending drawing history to client");
            outStream.writeObject(fileContents.toString());
            outStream.flush();
            System.out.println("Drawing history sent successfully");
            
        } catch (IOException e) {
            System.err.println("Error reading from database file: " + e.getMessage());
            
            // Send an empty history to avoid blocking client
            try {
                outStream.writeObject("ALLDRAW:");
                outStream.flush();
                System.out.println("Sent empty drawing history due to file error");
            } catch (IOException ioEx) {
                System.err.println("Error sending empty drawing history: " + ioEx.getMessage());
            }
        }
    }
    
    private static synchronized void appendToDatabase(String content) {
        try (java.io.FileWriter fw = new java.io.FileWriter(DATABASE_FILE, true)) {
            fw.write(content + "\n");
            System.out.println("Successfully wrote to database: " + content);
        } catch (IOException e) {
            System.err.println("Error writing to database file: " + e.getMessage());
        }
    }
}
