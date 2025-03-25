package com.syncspace.client;

import com.syncspace.client.ui.ChatPanel;
import com.syncspace.client.ui.WhiteboardPanel;
import com.syncspace.common.Message;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.ArrayList;

public class WhiteboardClient {
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private JFrame frame;
    private WhiteboardPanel whiteboardPanel;
    private ChatPanel chatPanel;
    private String username;
    private final List<String> followerIps = new CopyOnWriteArrayList<>();
    private final List<String> knownServerIps = new CopyOnWriteArrayList<>();


    public WhiteboardClient(String serverAddress, int serverPort) {
        try {
            socket = new Socket(serverAddress, serverPort);
            // Important: Create output stream first, then flush it immediately
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            // Then create input stream
            inputStream = new ObjectInputStream(socket.getInputStream());
            initializeUI();
            setupEventHandlers();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Could not connect to server: " + e.getMessage(), 
                                         "Connection Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    public WhiteboardClient(String serverAddress, int serverPort, WhiteboardPanel panel, JFrame fr) {
        try {
            socket = new Socket(serverAddress, serverPort);
            // Important: Create output stream first, then flush it immediately
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            // Then create input stream
            inputStream = new ObjectInputStream(socket.getInputStream());
            setupEventHandlers();
            frame = fr;
            whiteboardPanel = panel;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Could not connect to server: " + e.getMessage(), 
                                         "Connection Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }


    private void initializeUI() {
        frame = new JFrame("SyncSpace");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLayout(new BorderLayout());

        // Create toolbar with drawing options
        JToolBar toolBar = new JToolBar();
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            whiteboardPanel.clearCanvas();
            sendClearAction(0);
        });
        toolBar.add(clearBtn);
        frame.add(toolBar, BorderLayout.NORTH);

        // Create whiteboard panel (main drawing area)
        whiteboardPanel = new WhiteboardPanel();
        frame.add(whiteboardPanel, BorderLayout.CENTER);

        // Create chat panel
        chatPanel = new ChatPanel();
        chatPanel.setPreferredSize(new Dimension(250, frame.getHeight()));
        frame.add(chatPanel, BorderLayout.EAST);

        frame.setVisible(true);
    }

    private void setupEventHandlers() {
        // Add mouse event handlers for drawing
        whiteboardPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Start drawing and send message to server
                whiteboardPanel.startDrawing(new Point(e.getX(), e.getY()), username);
                sendDrawAction("START:" + e.getX() + "," + e.getY(), 0);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                // End drawing and send message to server
                whiteboardPanel.endDrawing(username);
                sendDrawAction("END:" + e.getX() + "," + e.getY(), 0);
            }
        });

        whiteboardPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                // Continue drawing and send message to server
                whiteboardPanel.continueDraw(new Point(e.getX(), e.getY()), username);
                sendDrawAction("DRAW:" + e.getX() + "," + e.getY(), 0);
            }
        });

        // Set up chat input handler
        JTextField chatInput = new JTextField();
        chatInput.addActionListener(e -> {
            String message = chatInput.getText();
            if (!message.isEmpty()) {
                sendMessage(message, 0);
                chatInput.setText("");
            }
        });
        
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(chatInput, BorderLayout.CENTER);
        
        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(e -> {
            String message = chatInput.getText();
            if (!message.isEmpty()) {
                sendMessage(message, 0);
                chatInput.setText("");
            }
        });
        inputPanel.add(sendButton, BorderLayout.EAST);
        
        frame.add(inputPanel, BorderLayout.SOUTH);
    }

    private void sendMessage(String message, int count) {
        try {
            outputStream.writeObject(new Message(Message.MessageType.TEXT, message, username));
            outputStream.flush();
            chatPanel.receiveMessage("You: " + message);
        } catch (IOException e) {
            e.printStackTrace();
            try {
                if (count > 3) {
                    // findNewLeader();
                }
                wait(7000);
                sendMessage(message, count + 1);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        }
    }

    private void sendDrawAction(String drawData, int count) {
        try {
            outputStream.writeObject(new Message(Message.MessageType.DRAW, drawData + ";" + username, username));
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending draw action: " + e.getMessage());
            try {
                if (count > 3) {
                    // findNewLeader();
                }
                wait(7000);
                sendDrawAction(drawData, count + 1);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        }
    }

    private void sendClearAction(int count) {
        try {
            outputStream.writeObject(new Message(Message.MessageType.CLEAR, "CLEAR_ALL", username));
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending clear action: " + e.getMessage());
            try {
                if (count > 3) {
                    // findNewLeader();
                }
                wait(7000);
                sendClearAction(count + 1);
            } catch (InterruptedException e1) {
                e1.printStackTrace();
            }
        }
    }

    // private void findNewLeader() {
    //     // Collect all server IPs including this one
    //     List<String> allServerIps = new ArrayList<>(followerIps);
    //     // Simple bully algorithm: highest IP becomes leader
    //     String highestIp = "";
    //     for (String ip : allServerIps) {
    //         if (ip.compareTo(highestIp) > 0) {
    //             highestIp = ip;
    //         }
    //     }
    // }

    private void startListeningForMessages() {
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        Object input = inputStream.readObject();
                        // Process messages as before...
                        if (input instanceof Message) {
                            handleMessage((Message) input);
                        } else if (input instanceof String) {
                            String message = (String) input;
                            if (message.startsWith("SERVER_FOLLOWER_LIST:")) {
                                // Extract the follower list part
                                String followerListPart = message.substring("SERVER_FOLLOWER_LIST:".length());
                                updateFollowerList(followerListPart);
                            } else if (message.equals("SERVER_LEADERSHIP_CHANGE")) {
                                chatPanel.receiveMessage("*** Server leadership has changed ***");
                            } else {
                                // Handle regular string messages
                                chatPanel.receiveMessage(message);
                            }                    
                        } else if (input instanceof Boolean) {
                            // Registration response
                            boolean success = (Boolean) input;
                            if (success) {
                                chatPanel.receiveMessage("Successfully connected to the server!");
                            } else {
                                showError("Failed to register. Username might be taken.");
                                System.exit(0);
                            }
                        }
                    } catch (ClassNotFoundException e) {
                        chatPanel.receiveMessage("Error processing message: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                // Connection to server lost
                handleServerDisconnection();
            }
        }).start();
    }

    private void handleServerDisconnection() {
        // Client lost connection to server
        chatPanel.receiveMessage("*** Connection to server lost. Attempting to reconnect... ***");
        
        // First, let's wait to allow leader election to complete (5 seconds)
        chatPanel.receiveMessage("Waiting for servers to complete leadership election...");
        try {
            Thread.sleep(8000);  // 5 seconds delay for leader election
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Try to reconnect to all known servers
        List<String> serverCandidates = new ArrayList<>();
        
        // First try the current server
        serverCandidates.add(socket.getInetAddress().getHostAddress());
        
        // Then try all followers
        serverCandidates.addAll(followerIps);
        
        // Then try all other known servers
        for (String ip : knownServerIps) {
            if (!serverCandidates.contains(ip)) {
                serverCandidates.add(ip);
            }
        }
        
        System.out.println("These are all the candidates:\n" + serverCandidates);
        
        // Remove servers we've already tried that failed
        serverCandidates.removeIf(ip -> ip.equals(socket.getInetAddress().getHostAddress()));
        
        if (serverCandidates.isEmpty()) {
            showError("No backup servers available. Please restart the application.");
            return;
        }
        
        // Try each server in the follower list
        for (String serverIp : serverCandidates) {
            chatPanel.receiveMessage("Attempting to connect to server at " + serverIp);
            try {
                // Close existing connection resources
                if (outputStream != null) outputStream.close();
                if (inputStream != null) inputStream.close();
                if (socket != null) socket.close();
                
                // Connect to new server
                System.out.println("Trying to connect to: " + serverIp);
                socket = new Socket(serverIp, 12345);
                outputStream = new ObjectOutputStream(socket.getOutputStream());
                outputStream.flush();
                inputStream = new ObjectInputStream(socket.getInputStream());
                
                // Re-register with the same username
                registerUser(username);
                
                chatPanel.receiveMessage("*** Successfully reconnected to server at " + serverIp + " ***");
                return; // Successfully reconnected
            } catch (IOException e) {
                chatPanel.receiveMessage("Failed to connect to " + serverIp + ": " + e.getMessage());
                
                // Add a short delay between connection attempts
                try {
                    Thread.sleep(2000);  // 2 seconds delay between attempts
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // If we get here, all reconnection attempts failed
        chatPanel.receiveMessage("All reconnection attempts failed. Waiting 10 seconds before trying again...");
        
        // Wait longer and try again with a recursive call
        try {
            Thread.sleep(10000);  // 10 seconds wait before retrying the whole process
            handleServerDisconnection();  // Recursive call to try again
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            showError("Failed to reconnect to any available server. Please restart the application.");
        }
    }

    private void handleMessage(Message message) {
        SwingUtilities.invokeLater(() -> {
            switch (message.getType()) {
                case TEXT:
                    chatPanel.receiveMessage(message.getSenderId() + ": " + message.getContent());
                    break;
                case DRAW:
                    handleDrawAction(message.getContent());
                    break;
                case CLEAR:
                    if ("CLEAR_ALL".equals(message.getContent())) {
                        whiteboardPanel.clearCanvas();
                    }
                    break;
                case USER_JOIN:
                    chatPanel.receiveMessage("*** " + message.getSenderId() + " has joined ***");
                    break;
                case USER_LEAVE:
                    chatPanel.receiveMessage("*** " + message.getSenderId() + " has left ***");
                    break;
                default:
                    break;
            }
        });
    }

    private void handleDrawAction(String actionData) {
        System.out.println("DEBUG: handleDrawAction received: " + actionData);
        
        try {
            // Extract user ID from the action data
            String[] parts = actionData.split(";");
            System.out.println("DEBUG: Split into " + parts.length + " parts");
            
            String action = parts[0];
            String userId = parts.length > 1 ? parts[1] : "unknown";
            System.out.println("DEBUG: Action: '" + action + "', UserId: '" + userId + "'");
            
            if (action.startsWith("START:")) {
                String coords = action.substring(6);
                System.out.println("DEBUG: START coords string: " + coords);
                String[] coordParts = coords.split(",");
                System.out.println("DEBUG: Coord parts length: " + coordParts.length);
                
                int x = Integer.parseInt(coordParts[0]);
                int y = Integer.parseInt(coordParts[1]);
                System.out.println("DEBUG: Starting drawing at position (" + x + "," + y + ") for user " + userId);
                
                whiteboardPanel.startDrawing(new Point(x, y), userId);
                
            } else if (action.startsWith("DRAG:")) {
                String coords = action.substring(5);
                System.out.println("DEBUG: DRAG coords string: " + coords);
                String[] coordParts = coords.split(",");
                
                int x = Integer.parseInt(coordParts[0]);
                int y = Integer.parseInt(coordParts[1]);
                System.out.println("DEBUG: Continuing drawing at (" + x + "," + y + ") for user " + userId);
                
                whiteboardPanel.continueDraw(new Point(x, y), userId);
                
            } else if (action.startsWith("END:")) {
                System.out.println("DEBUG: Ending drawing for user " + userId);
                whiteboardPanel.endDrawing(userId);
                
            } else {
                System.out.println("DEBUG: Unknown action type: " + action);
            }
        } catch (Exception e) {
            System.err.println("ERROR in handleDrawAction: " + e.getMessage());
            e.printStackTrace();
            System.err.println("Original action data: " + actionData);
        }
    }
    
    private void updateFollowerListUI() {
        // This could update a status bar, a label, or add to the chat panel
        chatPanel.receiveMessage("--- Connected follower servers: " + followerIps.size() + " ---");
        for (String ip : followerIps) {
            chatPanel.receiveMessage("    → " + ip);
        }
    }
    

    // Method to register with the server
    private void registerUser(String username) {
        this.username = username;
        try {
            outputStream.writeObject(username);
            outputStream.flush();
            
            // Start listening for messages after registration
            startListeningForMessages();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error registering user: " + e.getMessage());
            System.exit(1);
        }
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> 
            JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE));
    }

    private void updateFollowerList(String followerListString) {
        // Clear the current follower list
        followerIps.clear();
        
        // Parse the new list (format: "ip1 * ip2 * ip3")
        if (followerListString != null && !followerListString.isEmpty()) {
            String[] ips = followerListString.split("\\s*\\*\\s*");
            for (String ip : ips) {
                if (!ip.trim().isEmpty()) {
                    followerIps.add(ip.trim());
                    // Also add to known servers if not already there
                    if (!knownServerIps.contains(ip.trim())) {
                        knownServerIps.add(ip.trim());
                    }
                }
            }
        }
        
        // Add the current server to known servers if not already there
        String currentServer = socket.getInetAddress().getHostAddress();
        if (!knownServerIps.contains(currentServer)) {
            knownServerIps.add(currentServer);
        }
        
        System.out.println("Updated follower server list: " + followerIps);
        System.out.println("Known server list: " + knownServerIps);

        updateFollowerListUI();
    }
    

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Ask for username
            String username = JOptionPane.showInputDialog(null, 
                "Enter your username:", "SyncSpace Login", JOptionPane.QUESTION_MESSAGE);
            
            if (username != null && !username.trim().isEmpty()) {
                String serverAddress = JOptionPane.showInputDialog(
                    null, 
                    "Enter server address:", 
                    "SyncSpace Connection", 
                    JOptionPane.QUESTION_MESSAGE);
                
                if (serverAddress == null || serverAddress.trim().isEmpty()) {
                    serverAddress = "localhost"; // Default for local testing
                }
                
                WhiteboardClient client = new WhiteboardClient(serverAddress, 12345);
                client.registerUser(username);
            } else {
                System.exit(0);
            }
        });
    }
}