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

public class WhiteboardClient {
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private JFrame frame;
    private WhiteboardPanel whiteboardPanel;
    private ChatPanel chatPanel;
    private String username;
    private final List<String> followerIps = new CopyOnWriteArrayList<>();


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
            sendClearAction();
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
                whiteboardPanel.startDrawing(new Point(e.getX(), e.getY()));
                sendDrawAction("START:" + e.getX() + "," + e.getY());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                // End drawing and send message to server
                whiteboardPanel.endDrawing();
                sendDrawAction("END:" + e.getX() + "," + e.getY());
            }
        });

        whiteboardPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                // Continue drawing and send message to server
                whiteboardPanel.continueDraw(new Point(e.getX(), e.getY()));
                sendDrawAction("DRAG:" + e.getX() + "," + e.getY());
            }
        });

        // Set up chat input handler
        JTextField chatInput = new JTextField();
        chatInput.addActionListener(e -> {
            String message = chatInput.getText();
            if (!message.isEmpty()) {
                sendMessage(message);
                chatInput.setText("");
            }
        });
        
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(chatInput, BorderLayout.CENTER);
        
        JButton sendButton = new JButton("Send");
        sendButton.addActionListener(e -> {
            String message = chatInput.getText();
            if (!message.isEmpty()) {
                sendMessage(message);
                chatInput.setText("");
            }
        });
        inputPanel.add(sendButton, BorderLayout.EAST);
        
        frame.add(inputPanel, BorderLayout.SOUTH);
    }

    private void sendMessage(String message) {
        try {
            outputStream.writeObject(new Message(Message.MessageType.TEXT, message, username));
            outputStream.flush();
            chatPanel.receiveMessage("You: " + message);
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending message: " + e.getMessage());
        }
    }

    private void sendDrawAction(String drawData) {
        try {
            outputStream.writeObject(new Message(Message.MessageType.DRAW, drawData, username));
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending draw action: " + e.getMessage());
        }
    }

    private void sendClearAction() {
        try {
            outputStream.writeObject(new Message(Message.MessageType.CLEAR, "CLEAR_ALL", username));
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending clear action: " + e.getMessage());
        }
    }

    private void startListeningForMessages() {
        new Thread(() -> {
            while (true) {
                try {
                    Object input = inputStream.readObject();
                    if (input instanceof Message) {
                        handleMessage((Message) input);
                    } else if (input instanceof String) {
                        String message = (String) input;
                        if (message.startsWith("SERVER_FOLLOWER_LIST:")) {
                            // Extract the follower list part
                            String followerListPart = message.substring("SERVER_FOLLOWER_LIST:".length());
                            updateFollowerList(followerListPart);
                        } else {
                            // Handle regular string messages as before
                            chatPanel.receiveMessage(message);
                        }                    
                    } else if (input instanceof Boolean) {
                        // This is a response to the registration process
                        boolean success = (Boolean) input;
                        if (success) {
                            chatPanel.receiveMessage("Successfully connected to the server!");
                        } else {
                            showError("Failed to register. Username might be taken.");
                            System.exit(0);
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                    showError("Connection to server lost: " + e.getMessage());
                    break;
                }
            }
        }).start();
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
        if (actionData.startsWith("START:")) {
            String coords = actionData.substring(6);
            String[] parts = coords.split(",");
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            whiteboardPanel.startDrawing(new Point(x, y));
        } else if (actionData.startsWith("DRAG:")) {
            String coords = actionData.substring(5);
            String[] parts = coords.split(",");
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            whiteboardPanel.continueDraw(new Point(x, y));
        } else if (actionData.startsWith("END:")) {
            whiteboardPanel.endDrawing();
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
        // Clear the current list
        followerIps.clear();
        
        // Parse the new list (format: "ip1 * ip2 * ip3")
        if (followerListString != null && !followerListString.isEmpty()) {
            String[] ips = followerListString.split("\\s*\\*\\s*");
            for (String ip : ips) {
                if (!ip.trim().isEmpty()) {
                    followerIps.add(ip.trim());
                }
            }
        }
        
        // Log the updated follower list
        System.out.println("Updated follower server list: " + followerIps);

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