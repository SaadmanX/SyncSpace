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
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;

public class WhiteboardClient {
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private JFrame frame;
    private WhiteboardPanel whiteboardPanel;
    private ChatPanel chatPanel;
    private String username;
    private List<String> serverAddresses;
    private int currentServerIndex = 0;
    private boolean reconnecting = false;
    private JLabel connectionStatusLabel;

    public WhiteboardClient(List<String> serverAddresses, String username) {
        this.serverAddresses = new ArrayList<>(serverAddresses);
        this.username = username;
        
        initializeUI();
        connectToServer();
    }
    
    private void connectToServer() {
        if (serverAddresses.isEmpty()) {
            showError("No server addresses provided.");
            System.exit(1);
            return;
        }
        
        // Try to connect to the current server
        String currentServer = serverAddresses.get(currentServerIndex);
        String[] parts = currentServer.split(":");
        if (parts.length != 2) {
            showError("Invalid server address format: " + currentServer);
            return;
        }
        
        String host = parts[0];
        int port;
        try {
            port = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            showError("Invalid port number: " + parts[1]);
            return;
        }
        
        try {
            updateConnectionStatus("Connecting to " + host + ":" + port + "...");
            
            // Close existing connection if any
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            
            socket = new Socket(host, port);
            
            // Important: Create output stream first, then flush it immediately
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            // Then create input stream
            inputStream = new ObjectInputStream(socket.getInputStream());
            
            // Setup event handlers
            setupEventHandlers();
            
            // Register with the server
            registerUser(username);
            
            updateConnectionStatus("Connected to " + host + ":" + port);
            reconnecting = false;
            
        } catch (ConnectException e) {
            System.err.println("Failed to connect to " + host + ":" + port + ": " + e.getMessage());
            
            // Try the next server
            tryNextServer();
        } catch (IOException e) {
            showError("Could not connect to server: " + e.getMessage());
            
            // Try the next server
            tryNextServer();
        }
    }
    
    private void tryNextServer() {
        currentServerIndex = (currentServerIndex + 1) % serverAddresses.size();
        
        if (!reconnecting) {
            reconnecting = true;
            
            // Try to reconnect after a delay
            new Thread(() -> {
                try {
                    updateConnectionStatus("Connection failed. Trying next server in 3 seconds...");
                    Thread.sleep(3000);
                    connectToServer();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        } else {
            // If we've tried all servers, show an error
            if (currentServerIndex == 0) {
                updateConnectionStatus("Failed to connect to any server. Retrying...");
            }
            
            // Try again after a delay
            new Thread(() -> {
                try {
                    Thread.sleep(5000);
                    connectToServer();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }

    private void initializeUI() {
        frame = new JFrame("SyncSpace");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLayout(new BorderLayout());

        // Create status panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        connectionStatusLabel = new JLabel("Not connected");
        connectionStatusLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        statusPanel.add(connectionStatusLabel, BorderLayout.WEST);
        
        // Create toolbar with drawing options
        JToolBar toolBar = new JToolBar();
        JButton clearBtn = new JButton("Clear");
        clearBtn.addActionListener(e -> {
            whiteboardPanel.clearCanvas();
            sendClearAction();
        });
        toolBar.add(clearBtn);
        
        // Add color selector
        JButton colorBtn = new JButton("Color");
        colorBtn.addActionListener(e -> {
            Color newColor = JColorChooser.showDialog(
                frame, "Choose Drawing Color", whiteboardPanel.getCurrentColor());
            if (newColor != null) {
                whiteboardPanel.setColor(newColor);
            }
        });
        toolBar.add(colorBtn);
        
        // Add line thickness selector
        String[] thicknesses = {"1", "2", "3", "5", "8"};
        JComboBox<String> thicknessCombo = new JComboBox<>(thicknesses);
        thicknessCombo.setSelectedIndex(1); // Default to 2
        thicknessCombo.addActionListener(e -> {
            int thickness = Integer.parseInt((String)thicknessCombo.getSelectedItem());
            whiteboardPanel.setStrokeSize(thickness);
        });
        toolBar.add(new JLabel(" Thickness: "));
        toolBar.add(thicknessCombo);
        
        frame.add(statusPanel, BorderLayout.SOUTH);
        frame.add(toolBar, BorderLayout.NORTH);

        // Create whiteboard panel (main drawing area)
        whiteboardPanel = new WhiteboardPanel();
        frame.add(new JScrollPane(whiteboardPanel), BorderLayout.CENTER);

        // Create chat panel
        chatPanel = new ChatPanel();
        chatPanel.setPreferredSize(new Dimension(250, frame.getHeight()));
        frame.add(chatPanel, BorderLayout.EAST);

        frame.setVisible(true);
    }
    
    private void updateConnectionStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            connectionStatusLabel.setText(status);
        });
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
        
        chatPanel.add(inputPanel, BorderLayout.SOUTH);
    }

    private void sendMessage(String message) {
        try {
            outputStream.writeObject(new Message(Message.MessageType.TEXT, message, username));
            outputStream.flush();
            chatPanel.receiveMessage("You: " + message);
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending message: " + e.getMessage());
            handleDisconnect();
        }
    }

    private void sendDrawAction(String drawData) {
        try {
            outputStream.writeObject(new Message(Message.MessageType.DRAW, drawData, username));
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending draw action: " + e.getMessage());
            handleDisconnect();
        }
    }

    private void sendClearAction() {
        try {
            outputStream.writeObject(new Message(Message.MessageType.CLEAR, "CLEAR_ALL", username));
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error sending clear action: " + e.getMessage());
            handleDisconnect();
        }
    }
    
    private void handleDisconnect() {
        updateConnectionStatus("Disconnected. Attempting to reconnect...");
        tryNextServer();
    }

    private void startListeningForMessages() {
        new Thread(() -> {
            while (socket != null && !socket.isClosed()) {
                try {
                    Object input = inputStream.readObject();
                    if (input instanceof Message) {
                        handleMessage((Message) input);
                    } else if (input instanceof String) {
                        chatPanel.receiveMessage((String) input);
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
                } catch (IOException e) {
                    System.err.println("Connection to server lost: " + e.getMessage());
                    handleDisconnect();
                    break;
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                    showError("Error processing server message: " + e.getMessage());
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
                case LEADER_ANNOUNCEMENT:
                    chatPanel.receiveMessage("*** Server leadership changed: " + message.getContent() + " ***");
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

    // Method to register with the server
    private void registerUser(String username) {
        try {
            outputStream.writeObject(username);
            outputStream.flush();
            
            // Start listening for messages after registration
            startListeningForMessages();
        } catch (IOException e) {
            e.printStackTrace();
            showError("Error registering user: " + e.getMessage());
            handleDisconnect();
        }
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> 
            JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Ask for username
            String username = JOptionPane.showInputDialog(null, 
                "Enter your username:", "SyncSpace Login", JOptionPane.QUESTION_MESSAGE);
            
            if (username != null && !username.trim().isEmpty()) {
                // Default server list - can be updated to read from config
                List<String> serverAddresses = new ArrayList<>();
                // serverAddresses.add("localhost:12345"); // Primary server
                // serverAddresses.add("localhost:12346"); // Backup server 1
                // serverAddresses.add("localhost:12347"); // Backup server 2
                
                // For a real distributed setup, replace with actual server IPs
                serverAddresses.add("10.59.174.194:12345");
                serverAddresses.add("10.59.174.193:12345");
                serverAddresses.add("10.59.174.195:12345");
                
                new WhiteboardClient(serverAddresses, username);
            } else {
                System.exit(0);
            }
        });
    }
}