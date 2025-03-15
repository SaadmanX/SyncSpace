package com.syncspace.server.replication;

import com.syncspace.common.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerInstance {
    private String address;
    private int port;
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private Thread listenerThread;

    public ServerInstance(String address, int port) {
        this.address = address;
        this.port = port;
    }
    
    public void setSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.outputStream = new ObjectOutputStream(socket.getOutputStream());
        this.outputStream.flush();
        this.inputStream = new ObjectInputStream(socket.getInputStream());
        this.isConnected.set(true);
    }
    
    public void startCommunication() {
        if (listenerThread == null || !listenerThread.isAlive()) {
            listenerThread = new Thread(this::listenForMessages);
            listenerThread.setDaemon(true);
            listenerThread.start();
        }
    }
    
    private void listenForMessages() {
        while (isConnected.get()) {
            try {
                Object message = inputStream.readObject();
                handleMessage(message);
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("Error receiving message from " + address + ":" + port + ": " + e.getMessage());
                disconnect();
            }
        }
    }
    
    private void handleMessage(Object message) {
        if (message instanceof Message) {
            Message msg = (Message) message;
            System.out.println("Received message from server " + address + ":" + port + " of type: " + msg.getType());
            
            // Process based on message type
            switch (msg.getType()) {
                case HEARTBEAT:
                    // Update last heartbeat time
                    System.out.println("Received heartbeat from " + address + ":" + port);
                    break;
                case LEADER_ELECTION:
                    // Handle leader election message
                    System.out.println("Received leader election notification");
                    break;
                default:
                    // Forward to local clients
                    System.out.println("Forwarding replicated message to local clients");
                    break;
            }
        } else if (message instanceof String) {
            String cmd = (String) message;
            if (cmd.startsWith("LEADER:")) {
                String newLeaderId = cmd.substring(7);
                System.out.println("New leader notification: " + newLeaderId);
                // Update local leader election
            }
        }
    }

    public void sendData(Object data) {
        if (isConnected.get()) {
            try {
                outputStream.writeObject(data);
                outputStream.flush();
            } catch (IOException e) {
                System.err.println("Error sending data to " + address + ":" + port + ": " + e.getMessage());
                disconnect();
            }
        }
    }
    
    public void sendHeartbeat() {
        if (isConnected.get()) {
            try {
                // Create a heartbeat message
                Message heartbeat = new Message(Message.MessageType.HEARTBEAT, "HEARTBEAT", "LEADER");
                outputStream.writeObject(heartbeat);
                outputStream.flush();
            } catch (IOException e) {
                System.err.println("Error sending heartbeat to " + address + ":" + port + ": " + e.getMessage());
                disconnect();
            }
        }
    }
    
    public void sendLeaderUpdate(String leaderId) {
        if (isConnected.get()) {
            try {
                outputStream.writeObject("LEADER:" + leaderId);
                outputStream.flush();
            } catch (IOException e) {
                System.err.println("Error sending leader update to " + address + ":" + port + ": " + e.getMessage());
                disconnect();
            }
        }
    }
    
    public void disconnect() {
        isConnected.set(false);
        try {
            if (outputStream != null) outputStream.close();
            if (inputStream != null) inputStream.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            System.err.println("Error closing connection to " + address + ":" + port + ": " + e.getMessage());
        }
    }

    public String getAddress() {
        return address;
    }
    
    public int getPort() {
        return port;
    }
    
    public boolean isConnected() {
        return isConnected.get();
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        ServerInstance other = (ServerInstance) obj;
        return address.equals(other.address) && port == other.port;
    }
    
    @Override
    public int hashCode() {
        return 31 * address.hashCode() + port;
    }
}