package com.syncspace.server;

import java.io.IOException;
import com.syncspace.common.Message;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class Database {
    private static final String DATABASE_FILE = "database_log.txt";
    private static final int PORT = 1500;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static ServerSocket serverSocket;
    
    public static void main(String[] args) {
        System.out.println("Database server starting on port " + PORT);
        
        // Set up a shutdown hook to handle CTRL+C gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown requested. Closing server socket...");
            running.set(false);
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                System.err.println("Error during shutdown: " + e.getMessage());
            }
        }));
        
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("Database server is running and waiting for connections...");
            
            // Single-threaded accept loop
            while (running.get()) {
                try {
                    // Set socket to accept with timeout to check running state periodically
                    serverSocket.setSoTimeout(1000);
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New connection from: " + clientSocket.getInetAddress().getHostAddress());
                    
                    // Handle connection directly in the main thread
                    handleClientConnection(clientSocket);
                    
                } catch (java.net.SocketTimeoutException e) {
                    // This is expected due to setSoTimeout - just continue the loop
                    continue;
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("Error accepting connection: " + e.getMessage());
                        // Brief pause before trying again
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Fatal error starting database server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println("Database server shutdown complete");
        }
    }
    
    private static void handleClientConnection(Socket clientSocket) {
        System.out.println("Handling connection from: " + clientSocket.getInetAddress().getHostAddress());
        
        ObjectOutputStream outStream = null;
        ObjectInputStream inStream = null;
        
        try {
            // Set socket options for better disconnect detection
            clientSocket.setSoTimeout(30000);  // 30 second read timeout
            clientSocket.setKeepAlive(true);   // Enable TCP keepalive
            
            outStream = new ObjectOutputStream(clientSocket.getOutputStream());
            outStream.flush();
            inStream = new ObjectInputStream(clientSocket.getInputStream());
            
            // Send ready signal
            outStream.writeObject("DB_READY");
            outStream.flush();
            System.out.println("Sent DB_READY signal");
            
            // Send existing drawing data from file
            sendDrawingHistory(outStream);
            
            // Process incoming messages until connection closes or server stops
            while (running.get() && !clientSocket.isClosed()) {
                try {
                    // Set a smaller timeout for the read to check running state periodically
                    clientSocket.setSoTimeout(1000);
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
                } catch (java.net.SocketTimeoutException e) {
                    // Expected due to timeout - just continue the loop
                    continue;
                } catch (ClassNotFoundException e) {
                    System.err.println("Unknown object type received: " + e.getMessage());
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("Connection error with " + 
                            clientSocket.getInetAddress().getHostAddress() + ": " + e.getMessage());
                    }
                    break;  // Exit the loop on IO exception
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                System.err.println("Error with client connection: " + e.getMessage());
            }
        } finally {
            // Close resources in reverse order
            System.out.println("Cleaning up connection resources for " + 
                clientSocket.getInetAddress().getHostAddress());
            
            try {
                if (inStream != null) inStream.close();
            } catch (IOException e) {
                System.err.println("Error closing input stream: " + e.getMessage());
            }
            
            try {
                if (outStream != null) outStream.close();
            } catch (IOException e) {
                System.err.println("Error closing output stream: " + e.getMessage());
            }
            
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    clientSocket.close();
                    System.out.println("Socket closed successfully with: " + 
                        clientSocket.getInetAddress().getHostAddress());
                }
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }
    
    private static void sendDrawingHistory(ObjectOutputStream outStream) {
        try (java.io.FileReader fr = new java.io.FileReader(DATABASE_FILE);
             java.io.BufferedReader br = new java.io.BufferedReader(fr)) {
            
            StringBuilder drawContents = new StringBuilder();
            StringBuilder textContents = new StringBuilder();
            String line;
            int lineCount = 0;
            
            // drawContents.append("ALLDRAW:");
            while ((line = br.readLine()) != null) {
                // fileContents.append(line).append("\n");
                if(line.contains("TEXT:")){
                    textContents.append(line+("\n"));
                }else if(line.contains("CLEAR")){
                    drawContents = new StringBuilder();
                }else{
                    drawContents.append(line+"\n");
                }
                lineCount++;
            }

            StringBuilder filecontents = textContents.append(drawContents);
            
            // Send the content to the client
            System.out.println("Sending channel history with " + lineCount + " actions to client");
            outStream.writeObject(filecontents.toString());
            outStream.flush();
            System.out.println("Channel history sent successfully");
           
            
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
