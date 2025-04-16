package com.syncspace.client;

import com.syncspace.client.ui.ChatPanel;
import com.syncspace.client.ui.WhiteboardPanel;
import com.syncspace.common.Message;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;

public class WhiteboardClient {
    private Socket socket;
    private ObjectOutputStream outputStream;
    private ObjectInputStream inputStream;
    private String username;
    private final List<String> followerIps = new CopyOnWriteArrayList<>();
    private final List<String> knownServerIps = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat logDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    private long virtualClockOffset = 0;
    private long lastSyncTime = 0;

    private JFrame frame;
    private WhiteboardPanel whiteboardPanel;
    private ChatPanel chatPanel;
    private JToolBar toolBar;
    private JPanel statusBar;
    private JLabel statusLabel;
    private JLabel connectionStatusLabel;
    private JLabel activeUsersLabel;

    private enum DrawTool {
        PEN, LINE, RECTANGLE, CIRCLE, ERASER, TEXT, SELECT
    }

    private DrawTool currentTool = DrawTool.PEN;
    private Color currentColor = Color.BLACK;
    private int strokeSize = 2;
    private Map<String, Color> userColors = new HashMap<>();


    /**
     * Log a message to the terminal with timestamp and category
     * @param category The log category (INFO, ERROR, NETWORK, etc.)
     * @param message The message to log
     */
    private void log(String category, String message) {
        String timestamp = logDateFormat.format(new Date());
        System.out.println("[" + timestamp + "] [" + category + "] " + message);
    }

    /**
     * Log a detailed network event
     */
    private void logNetwork(String message) {
        log("NETWORK", message);
    }

    /**
     * Log an informational message
     */
    private void logInfo(String message) {
        log("INFO", message);
    }

    /**
     * Log an error message
     */
    private void logError(String message, Throwable e) {
        log("ERROR", message + (e != null ? ": " + e.getMessage() : ""));
        if (e != null) {
            e.printStackTrace();
        }
    }

    /**
     * Log a drawing action
     */
    private void logDrawing(String message) {
        log("DRAWING", message);
    }

    public WhiteboardClient(String serverAddress, int serverPort) {
        try {
            logNetwork("Connecting to server at " + serverAddress + ":" + serverPort);
            socket = new Socket(serverAddress, serverPort);
            logNetwork("Connected successfully to " + socket.getInetAddress().getHostAddress());
            
            // Important: Create output stream first, then flush it immediately
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            logNetwork("Output stream initialized");
            
            // Then create input stream
            inputStream = new ObjectInputStream(socket.getInputStream());
            logNetwork("Input stream initialized");
            
            initializeUI();
            setupEventHandlers();
        } catch (IOException e) {
            logError("Connection failed", e);
            JOptionPane.showMessageDialog(null, "Could not connect to server: " + e.getMessage(), 
                                         "Connection Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    public WhiteboardClient(String serverAddress, int serverPort, WhiteboardPanel panel, JFrame fr) {
        try {
            logNetwork("Connecting to server at " + serverAddress + ":" + serverPort + " with existing UI components");
            socket = new Socket(serverAddress, serverPort);
            logNetwork("Connected successfully to " + socket.getInetAddress().getHostAddress());
            
            // Important: Create output stream first, then flush it immediately
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            logNetwork("Output stream initialized");
            
            // Then create input stream
            inputStream = new ObjectInputStream(socket.getInputStream());
            logNetwork("Input stream initialized");
            
            setupEventHandlers();
            frame = fr;
            whiteboardPanel = panel;
        } catch (IOException e) {
            logError("Connection failed", e);
            JOptionPane.showMessageDialog(null, "Could not connect to server: " + e.getMessage(), 
                                         "Connection Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }


    private void initializeUI() {
        // Main frame setup
        frame = new JFrame("SyncSpace Collaborative Whiteboard");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 800);
        frame.setMinimumSize(new Dimension(800, 600));
        
        // Use BorderLayout for the main layout
        frame.setLayout(new BorderLayout(5, 5));
        
        // Create a split pane for the main content and chat
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.8); // Give more space to the whiteboard
        splitPane.setOneTouchExpandable(true);
        
        // Setup the main content panel (toolbar + whiteboard)
        JPanel mainContentPanel = new JPanel(new BorderLayout(0, 0));
        
        // Create toolbar
        createToolbar();
        mainContentPanel.add(toolBar, BorderLayout.NORTH);
        
        // Create property panel (for tool-specific settings)
        JPanel propertyPanel = createPropertyPanel();
        mainContentPanel.add(propertyPanel, BorderLayout.WEST);
        
        // Create whiteboard panel with scroll support
        whiteboardPanel = new WhiteboardPanel();
        JScrollPane scrollPane = new JScrollPane(whiteboardPanel);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        mainContentPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Create chat panel
        chatPanel = new ChatPanel();
        
        // Add components to split pane
        splitPane.setLeftComponent(mainContentPanel);
        splitPane.setRightComponent(chatPanel);
        
        // Add split pane to frame
        frame.add(splitPane, BorderLayout.CENTER);
        
        // Create status bar
        createStatusBar();
        frame.add(statusBar, BorderLayout.SOUTH);
        
        // Display the window
        frame.setVisible(true);
        
        // Set initial split location after frame is visible
        splitPane.setDividerLocation(0.8);
        
        logInfo("UI initialization complete");
    }

    private void createToolbar() {
        toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEtchedBorder());
        
        // File operations group
        toolBar.add(createToolbarButton("New", "file-new.png", e -> clearWhiteboard()));
        toolBar.add(createToolbarButton("Open", "file-open.png", e -> loadWhiteboard()));
        toolBar.add(createToolbarButton("Save", "file-save.png", e -> saveWhiteboard()));
        toolBar.add(createToolbarButton("Export", "file-export.png", e -> exportAsImage()));
        
        toolBar.addSeparator();
        
        // Tool selection group
        ButtonGroup toolGroup = new ButtonGroup();
        JToggleButton penButton = createToolbarToggleButton("Pen", "tool-pen.png", e -> setCurrentTool(DrawTool.PEN));
        penButton.setSelected(true);
        toolGroup.add(penButton);
        toolBar.add(penButton);
        
        JToggleButton lineButton = createToolbarToggleButton("Line", "tool-line.png", e -> setCurrentTool(DrawTool.LINE));
        toolGroup.add(lineButton);
        toolBar.add(lineButton);
        
        JToggleButton rectButton = createToolbarToggleButton("Rectangle", "tool-rectangle.png", e -> setCurrentTool(DrawTool.RECTANGLE));
        toolGroup.add(rectButton);
        toolBar.add(rectButton);
        
        JToggleButton circleButton = createToolbarToggleButton("Circle", "tool-circle.png", e -> setCurrentTool(DrawTool.CIRCLE));
        toolGroup.add(circleButton);
        toolBar.add(circleButton);
        
        JToggleButton eraserButton = createToolbarToggleButton("Eraser", "tool-eraser.png", e -> setCurrentTool(DrawTool.ERASER));
        toolGroup.add(eraserButton);
        toolBar.add(eraserButton);
        
        JToggleButton textButton = createToolbarToggleButton("Text", "tool-text.png", e -> setCurrentTool(DrawTool.TEXT));
        toolGroup.add(textButton);
        toolBar.add(textButton);
        
        JToggleButton selectButton = createToolbarToggleButton("Select", "tool-select.png", e -> setCurrentTool(DrawTool.SELECT));
        toolGroup.add(selectButton);
        toolBar.add(selectButton);
        
        toolBar.addSeparator();
        
        // Color selection
        JButton colorButton = createToolbarButton("Color", "color-palette.png", e -> chooseColor());
        colorButton.setBackground(currentColor);
        toolBar.add(colorButton);
        
        // Stroke size
        String[] strokeSizes = {"1", "2", "3", "5", "8", "12"};
        JComboBox<String> strokeBox = new JComboBox<>(strokeSizes);
        strokeBox.setSelectedIndex(1); // Default to 2px
        strokeBox.setMaximumSize(new Dimension(60, 28));
        strokeBox.addActionListener(e -> {
            strokeSize = Integer.parseInt((String)strokeBox.getSelectedItem());
            whiteboardPanel.setStrokeSize(strokeSize);
        });
        toolBar.add(new JLabel(" Size: "));
        toolBar.add(strokeBox);
        
        toolBar.addSeparator();
        
        // View controls
        toolBar.add(createToolbarButton("Zoom In", "zoom-in.png", e -> zoomIn()));
        toolBar.add(createToolbarButton("Zoom Out", "zoom-out.png", e -> zoomOut()));
        toolBar.add(createToolbarButton("Reset View", "zoom-reset.png", e -> resetZoom()));
        
        toolBar.addSeparator();
        
        // Clear whiteboard
        toolBar.add(createToolbarButton("Clear", "edit-clear.png", e -> {
            if (JOptionPane.showConfirmDialog(frame, 
                    "Are you sure you want to clear the whiteboard?", 
                    "Clear Whiteboard", 
                    JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                clearWhiteboard();
            }
        }));
    }

    private JPanel createPropertyPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createEtchedBorder(),
            new EmptyBorder(5, 5, 5, 5)));
        panel.setPreferredSize(new Dimension(60, 0));
        
        // Quick color palette
        JPanel colorPanel = new JPanel(new GridLayout(4, 2, 3, 3));
        colorPanel.setBorder(BorderFactory.createTitledBorder("Colors"));
        
        addColorSwatch(colorPanel, Color.BLACK);
        addColorSwatch(colorPanel, Color.BLUE);
        addColorSwatch(colorPanel, Color.RED);
        addColorSwatch(colorPanel, Color.GREEN);
        addColorSwatch(colorPanel, Color.ORANGE);
        addColorSwatch(colorPanel, Color.MAGENTA);
        addColorSwatch(colorPanel, Color.CYAN);
        addColorSwatch(colorPanel, Color.PINK);
        
        panel.add(colorPanel);
        
        // Stroke size swatches
        JPanel strokePanel = new JPanel(new GridLayout(3, 1, 3, 3));
        strokePanel.setBorder(BorderFactory.createTitledBorder("Stroke"));
        
        addStrokeSwatch(strokePanel, 2);
        addStrokeSwatch(strokePanel, 5);
        addStrokeSwatch(strokePanel, 10);
        
        panel.add(strokePanel);
        
        // Add filler to push everything to the top
        panel.add(Box.createVerticalGlue());
        
        return panel;
    }

    private void createStatusBar() {
        statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEtchedBorder());
        
        // Left section - general status
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(new EmptyBorder(2, 5, 2, 5));
        statusBar.add(statusLabel, BorderLayout.WEST);
        
        // Right section - connection status and active users
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        connectionStatusLabel = new JLabel("Connected");
        connectionStatusLabel.setIcon(createColorIcon(Color.GREEN, 10, 10));
        connectionStatusLabel.setBorder(new EmptyBorder(0, 5, 0, 5));
        rightPanel.add(connectionStatusLabel);
        
        activeUsersLabel = new JLabel("Users: 1");
        activeUsersLabel.setBorder(new EmptyBorder(0, 5, 0, 10));
        rightPanel.add(activeUsersLabel);
        
        statusBar.add(rightPanel, BorderLayout.EAST);
    }

    private JButton createToolbarButton(String tooltip, String iconName, ActionListener action) {
        JButton button = new JButton();
        button.setToolTipText(tooltip);
        
        // First try to load the actual icon
        try {
            ImageIcon icon = new ImageIcon(getClass().getResource("/icons/" + iconName));
            button.setIcon(icon);
        } catch (Exception e) {
            // If icon can't be loaded, use text fallback
            button.setText(tooltip);
        }
        
        button.setFocusPainted(false);
        button.addActionListener(action);
        return button;
    }
    
    private JToggleButton createToolbarToggleButton(String tooltip, String iconName, ActionListener action) {
        JToggleButton button = new JToggleButton();
        button.setToolTipText(tooltip);
        
        // First try to load the actual icon
        try {
            ImageIcon icon = new ImageIcon(getClass().getResource("/icons/" + iconName));
            button.setIcon(icon);
        } catch (Exception e) {
            // If icon can't be loaded, use text fallback
            button.setText(tooltip);
        }
        
        button.setFocusPainted(false);
        button.addActionListener(action);
        return button;
    }

    private void addColorSwatch(JPanel panel, Color color) {
        JButton swatch = new JButton();
        swatch.setBackground(color);
        swatch.setPreferredSize(new Dimension(20, 20));
        swatch.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        swatch.addActionListener(e -> {
            currentColor = color;
            whiteboardPanel.setColor(color);
            updateStatusMessage("Color selected: " + colorToString(color));
        });
        panel.add(swatch);
    }
    
    private void addStrokeSwatch(JPanel panel, int size) {
        JButton swatch = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D)g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setColor(Color.BLACK);
                int width = getWidth();
                int height = getHeight();
                g2d.drawLine(5, height/2, width-5, height/2);
                g2d.setStroke(new BasicStroke(size));
            }
        };
        swatch.setPreferredSize(new Dimension(50, 20));
        swatch.addActionListener(e -> {
            strokeSize = size;
            whiteboardPanel.setStrokeSize(size);
            updateStatusMessage("Stroke size: " + size + "px");
        });
        panel.add(swatch);
    }
    
    private Icon createColorIcon(Color color, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setColor(color);
        g2d.fillOval(0, 0, width, height);
        g2d.dispose();
        return new ImageIcon(image);
    }
    
    private void setCurrentTool(DrawTool tool) {
        currentTool = tool;
        whiteboardPanel.setTool(tool.toString());
        updateStatusMessage("Tool selected: " + tool.toString());
    }

    private void updateStatusMessage(String message) {
        statusLabel.setText(message);
    }
    
    private void updateConnectionStatus(boolean connected) {
        if (connected) {
            connectionStatusLabel.setText("Connected");
            connectionStatusLabel.setIcon(createColorIcon(Color.GREEN, 10, 10));
        } else {
            connectionStatusLabel.setText("Disconnected");
            connectionStatusLabel.setIcon(createColorIcon(Color.RED, 10, 10));
        }
    }
    
    private void updateActiveUsers(int count) {
        activeUsersLabel.setText("Users: " + count);
    }

    private String colorToString(Color color) {
        if (color.equals(Color.BLACK)) return "Black";
        if (color.equals(Color.BLUE)) return "Blue";
        if (color.equals(Color.RED)) return "Red";
        if (color.equals(Color.GREEN)) return "Green";
        if (color.equals(Color.ORANGE)) return "Orange";
        if (color.equals(Color.MAGENTA)) return "Magenta";
        if (color.equals(Color.CYAN)) return "Cyan";
        if (color.equals(Color.PINK)) return "Pink";
        return "Custom";
    }
    
    private void chooseColor() {
        Color newColor = JColorChooser.showDialog(frame, "Choose Color", currentColor);
        if (newColor != null) {
            currentColor = newColor;
            whiteboardPanel.setColor(newColor);
            updateStatusMessage("Custom color selected");
        }
    }
    
    private void clearWhiteboard() {
        whiteboardPanel.clearCanvas();
        sendClearAction(0);
        updateStatusMessage("Whiteboard cleared");
    }
    
    private void saveWhiteboard() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Whiteboard");
        fileChooser.setFileFilter(new FileNameExtensionFilter("SyncSpace Whiteboard (*.swb)", "swb"));
        
        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String path = file.getAbsolutePath();
            if (!path.toLowerCase().endsWith(".swb")) {
                file = new File(path + ".swb");
            }
            
            // Here you would implement the actual save functionality
            // For now, just show a success message
            updateStatusMessage("Whiteboard saved to: " + file.getName());
        }
    }
    
    private void loadWhiteboard() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Open Whiteboard");
        fileChooser.setFileFilter(new FileNameExtensionFilter("SyncSpace Whiteboard (*.swb)", "swb"));
        
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            
            // Here you would implement the actual load functionality
            // For now, just show a success message
            updateStatusMessage("Whiteboard loaded from: " + file.getName());
        }
    }
    
    private void exportAsImage() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export as Image");
        fileChooser.setFileFilter(new FileNameExtensionFilter("PNG Image (*.png)", "png"));
        
        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String path = file.getAbsolutePath();
            if (!path.toLowerCase().endsWith(".png")) {
                file = new File(path + ".png");
            }
            
            try {
                BufferedImage image = whiteboardPanel.getCanvas();
                ImageIO.write(image, "png", file);
                updateStatusMessage("Image exported to: " + file.getName());
            } catch (IOException e) {
                logError("Error exporting image", e);
                JOptionPane.showMessageDialog(frame, 
                    "Failed to export image: " + e.getMessage(), 
                    "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void zoomIn() {
        whiteboardPanel.setZoom(whiteboardPanel.getZoom() * 1.2f);
        updateStatusMessage("Zoom: " + Math.round(whiteboardPanel.getZoom() * 100) + "%");
    }
    
    private void zoomOut() {
        whiteboardPanel.setZoom(whiteboardPanel.getZoom() / 1.2f);
        updateStatusMessage("Zoom: " + Math.round(whiteboardPanel.getZoom() * 100) + "%");
    }
    
    private void resetZoom() {
        whiteboardPanel.setZoom(1.0f);
        updateStatusMessage("Zoom reset to 100%");
    }






    private void setupEventHandlers() {
        logInfo("Setting up event handlers");
        // Add mouse event handlers for drawing
        whiteboardPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // Start drawing and send message to server
                logDrawing("Mouse pressed at (" + e.getX() + "," + e.getY() + ")");
                whiteboardPanel.startDrawing(new Point(e.getX(), e.getY()), username);
                sendDrawAction("START:" + e.getX() + "," + e.getY(), 0);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                // End drawing and send message to server
                logDrawing("Mouse released at (" + e.getX() + "," + e.getY() + ")");
                whiteboardPanel.endDrawing(username);
                sendDrawAction("END:" + e.getX() + "," + e.getY(), 0);
            }
        });

        whiteboardPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                // Continue drawing and send message to server
                logDrawing("Mouse dragged at (" + e.getX() + "," + e.getY() + ")");
                whiteboardPanel.continueDraw(new Point(e.getX(), e.getY()), username);
                sendDrawAction("DRAW:" + e.getX() + "," + e.getY(), 0);
            }
        });

        // Set up chat input handler
        JTextField chatInput = new JTextField();
        chatInput.addActionListener(e -> {
            String message = chatInput.getText();
            if (!message.isEmpty()) {
                logInfo("User sent chat message: " + message);
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
                logInfo("User sent chat message via button: " + message);
                sendMessage(message, 0);
                chatInput.setText("");
            }
        });
        inputPanel.add(sendButton, BorderLayout.EAST);
        
        frame.add(inputPanel, BorderLayout.SOUTH);
        logInfo("Event handlers setup complete");
    }

    private void sendMessage(String message, int count) {
        try {
            logNetwork("Sending text message: " + message);
            Message msg = new Message(Message.MessageType.TEXT, 
                                  "TEXT:" + message + ";" + username, 
                                  username,
                                  getCurrentTime());
            outputStream.writeObject(msg);
            outputStream.flush();
            chatPanel.receiveMessage("You: " + message);
        } catch (IOException e) {
            logError("Failed to send message", e);
            try {
                if (count > 3) {
                    logNetwork("Maximum retry count reached for message: " + message);
                    // findNewLeader();
                } else {
                    logNetwork("Will retry sending message in 7 seconds (attempt " + (count+1) + "/4)");
                }
                wait(2000);
                sendMessage(message, count + 1);
            } catch (InterruptedException e1) {
                logError("Retry interrupted", e1);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendDrawAction(String drawData, int count) {
        try {
        logDrawing("Sending draw action: " + drawData);
        
        long timestamp = getCurrentTime();
        
        // Create message with just the drawing data and username, using timestamp in Message object
        String messageData = drawData + ";" + username;
        
        // Create message with explicitly provided timestamp
        Message msg = new Message(Message.MessageType.DRAW, messageData, username, timestamp);
        
        outputStream.writeObject(msg);
        outputStream.flush();
        } catch (IOException e) {
            logError("Failed to send draw action: " + drawData, e);
            try {
                if (count > 3) {
                    logNetwork("Maximum retry count reached for draw action: " + drawData);
                    // findNewLeader();
                } else {
                    logNetwork("Will retry sending draw action in 7 seconds (attempt " + (count+1) + "/4)");
                }
                wait(2000);
                sendDrawAction(drawData, count + 1);
            } catch (InterruptedException e1) {
                logError("Retry interrupted", e1);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sendClearAction(int count) {
        try {
            logDrawing("Sending clear canvas action");
            Message msg = new Message(Message.MessageType.CLEAR, "CLEAR_ALL", username, getCurrentTime());
            outputStream.writeObject(msg);
            outputStream.flush();
        } catch (IOException e) {
            logError("Failed to send clear action", e);
            try {
                if (count > 3) {
                    logNetwork("Maximum retry count reached for clear action");
                    // findNewLeader();
                } else {
                    logNetwork("Will retry sending clear action in 7 seconds (attempt " + (count+1) + "/4)");
                }
                wait(2000);
                sendClearAction(count + 1);
            } catch (InterruptedException e1) {
                logError("Retry interrupted", e1);
                Thread.currentThread().interrupt();
            }
        }
    }
    private void startListeningForMessages() {
        logInfo("Starting message listener thread");
        new Thread(() -> {
            try {
                while (true) {
                    try {
                        Object input = inputStream.readObject();
                        if (input instanceof Message) {
                            Message msg = (Message) input;
                            logNetwork("Received message: type=" + msg.getType() + ", sender=" + msg.getSenderId());
                            handleMessage(msg);
                        } else if (input instanceof String) {
                            String message = (String) input;
                            logNetwork("Received string message: " + message);
                            
                            if (message.startsWith("SERVER_FOLLOWER_LIST:")) {
                                // Extract the follower list part
                                String followerListPart = message.substring("SERVER_FOLLOWER_LIST:".length());
                                logNetwork("Received follower list update: " + followerListPart);
                                updateFollowerList(followerListPart);
                            } else if (message.startsWith("TIME_SYNC:")) {
                                logNetwork("Received time sync message: " + message);
                                handleTimeSync(message);
                            } else if (message.equals("SERVER_LEADERSHIP_CHANGE")) {
                                logNetwork("Server notified of leadership change");
                                chatPanel.receiveMessage("*** Server leadership has changed ***");
                            } else if (message.startsWith("NEW_LEADER_IP:")) {
                                // NEW CASE: Handle new leader notification
                                String newLeaderIp = message.substring("NEW_LEADER_IP:".length());
                                logNetwork("Received new leader notification: " + newLeaderIp);
                                chatPanel.receiveMessage("*** New leader server is: " + newLeaderIp + " ***");
                                
                                // Add to known servers if not already there
                                if (!knownServerIps.contains(newLeaderIp)) {
                                    knownServerIps.add(newLeaderIp);
                                    logNetwork("Added " + newLeaderIp + " to known servers list");
                                }
                                
                                // If this isn't the server we're already connected to, reconnect
                                if (!socket.getInetAddress().getHostAddress().equals(newLeaderIp)) {
                                    logNetwork("Current server is not the new leader, initiating reconnection");
                                    chatPanel.receiveMessage("Attempting to connect to new leader: " + newLeaderIp);
                                    // Attempt connection to new leader
                                    handleServerChangeWithNewLeader(newLeaderIp);
                                } else {
                                    logNetwork("Already connected to the new leader server");
                                }
                            } else if (message.startsWith("ALLDRAW:")) {
                                System.out.println("-----------------ALLDRAW--------------------");
                                System.out.println(message);
                                System.out.println("000000000000000000000000000000000000000");
                                logNetwork("Received drawing history with " + message.split("\n").length + " lines");
                                chatPanel.receiveMessage("Received drawing history from server");
                                String[] drawActions = message.substring("ALLDRAW:".length()).split("\n");
                                for (String act: drawActions) {
                                    Object act1 = (Object) act;
                                    if (act1 instanceof Message) {
                                        handleMessage((Message) act1);
                                    }
                                }
                            } else {
                                // Handle regular string messages
                                logNetwork("Received chat or system message: " + message);
                                chatPanel.receiveMessage(message);
                            }                    
                        } else if (input instanceof Boolean) {
                            Boolean regResponse = (Boolean) input;
                            logNetwork("Received registration response: " + regResponse);
                            if (regResponse) {
                                logInfo("Registration successful for user: " + username);
                            } else {
                                logError("Registration failed - username already taken: " + username, null);
                                showError("Username already taken. Please try a different name.");
                                System.exit(1);
                            }
                        } else {
                            logNetwork("Received unknown object type: " + (input != null ? input.getClass().getName() : "null"));
                        }
                    } catch (ClassNotFoundException e) {
                        logError("Error processing received message", e);
                        chatPanel.receiveMessage("Error processing message: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                logNetwork("Connection to server lost: " + e.getMessage());
                // Connection to server lost
                handleServerDisconnection();
            }
        }).start();
    }

    private void handleServerChangeWithNewLeader(String newLeaderIp) {
        logNetwork("Handling server change to new leader: " + newLeaderIp);
        try {
            // Close existing connection resources
            logNetwork("Closing existing connections before connecting to new leader");
            closeExistingConnections();
            
            // Connect to new leader
            logNetwork("Connecting to new leader at " + newLeaderIp + ":12345");
            socket = new Socket();
            socket.connect(new java.net.InetSocketAddress(newLeaderIp, 12345), 5000);
            // socket.setSoTimeout(10000);
            logNetwork("Socket connection established to new leader");
            
            // Set up streams
            logNetwork("Setting up communication streams with new leader");
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            outputStream.flush();
            inputStream = new ObjectInputStream(socket.getInputStream());
            logNetwork("Communication streams established with new leader");
            
            // Re-register with the same username
            logNetwork("Re-registering with username: " + username);
            registerUser(username);
            logNetwork("Registration with new leader complete");
            chatPanel.receiveMessage("*** Successfully connected to new leader at " + newLeaderIp + " ***");
        } catch (Exception e) {
            logError("Failed to connect to new leader", e);
            chatPanel.receiveMessage("Failed to connect to new leader: " + e.getMessage());
            // Fall back to normal reconnection process
            logNetwork("Falling back to normal reconnection process");
            handleServerDisconnection();
        }
    }

    private void handleServerDisconnection() {
        logNetwork("Handling server disconnection");
        // Client lost connection to server
        chatPanel.receiveMessage("*** Connection to server lost. Attempting to reconnect... ***");
        
        // First, let's wait to allow leader election to complete (8 seconds)
        logNetwork("Waiting 8 seconds for servers to complete leadership election");
        chatPanel.receiveMessage("Waiting for servers to complete leadership election...");
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError("Wait interrupted during reconnection process", e);
        }
        
        // Try to reconnect to all known servers
        List<String> serverCandidates = new ArrayList<>();
        
        // First try all followers since one of them might be the new leader
        serverCandidates.addAll(followerIps);
        logNetwork("Added " + followerIps.size() + " follower servers to reconnection candidates");
        
        // Then try the old leader if different from followers
        String oldLeaderIp = socket.getInetAddress().getHostAddress();
        if (!serverCandidates.contains(oldLeaderIp)) {
            serverCandidates.add(oldLeaderIp);
            logNetwork("Added previous leader " + oldLeaderIp + " to reconnection candidates");
        }
        
        // Add any other known servers
        for (String ip : knownServerIps) {
            if (!serverCandidates.contains(ip)) {
                serverCandidates.add(ip);
                logNetwork("Added known server " + ip + " to reconnection candidates");
            }
        }
        
        logNetwork("Reconnection candidates (" + serverCandidates.size() + "): " + serverCandidates);
        
        if (serverCandidates.isEmpty()) {
            logError("No backup servers available for reconnection", null);
            showError("No backup servers available. Please restart the application.");
            return;
        }
        
        // Try each server - IMPORTANT: No early removal of candidates
        for (String serverIp : serverCandidates) {
            logNetwork("Attempting to connect to server at " + serverIp);
            chatPanel.receiveMessage("Attempting to connect to server at " + serverIp);
            try {
                // Close existing connection resources
                logNetwork("Closing any existing connections before connecting to " + serverIp);
                closeExistingConnections();
                
                // Connect to new server with proper timeout handling
                Socket newSocket = null;
                try {
                    logNetwork("Opening socket connection to " + serverIp + ":12345 (timeout: 5000ms)");
                    newSocket = new Socket();
                    newSocket.connect(new java.net.InetSocketAddress(serverIp, 12345), 5000);
                    // newSocket.setSoTimeout(10000);
                    logNetwork("Socket connection established to " + serverIp);
                } catch (IOException e) {
                    logError("Failed to connect to " + serverIp, e);
                    continue; // Try next server
                }
                
                // Set up streams
                try {
                    logNetwork("Setting up communication streams with " + serverIp);
                    // Always create output stream first, then input stream
                    ObjectOutputStream newOut = new ObjectOutputStream(newSocket.getOutputStream());
                    newOut.flush();
                    ObjectInputStream newIn = new ObjectInputStream(newSocket.getInputStream());
                    logNetwork("Communication streams established with " + serverIp);
                    
                    // Update class fields only after successful connection
                    socket = newSocket;
                    outputStream = newOut;
                    inputStream = newIn;
                } catch (IOException e) {
                    // If stream setup fails, close socket and try next server
                    logError("Failed to setup streams with " + serverIp, e);
                    try { 
                        newSocket.close(); 
                        logNetwork("Closed failed socket connection to " + serverIp);
                    } catch (Exception ignored) {
                        logError("Error closing socket after stream setup failure", ignored);
                    }
                    continue;
                }
                
                // Re-register with the same username
                try {
                    logNetwork("Re-registering with username: " + username);
                    registerUser(username);
                    logNetwork("Successfully reconnected to server at " + serverIp);
                    chatPanel.receiveMessage("*** Successfully connected to server at " + serverIp + " ***");
                    return; // Successfully reconnected
                } catch (Exception e) {
                    logError("Failed to register with " + serverIp, e);
                    closeExistingConnections();
                }
                
            } catch (Exception e) {
                logError("Unexpected error connecting to " + serverIp, e);
                chatPanel.receiveMessage("Error connecting to " + serverIp + ": " + e.getMessage());
            }
            
            // Add a short delay between connection attempts
            logNetwork("Waiting 2 seconds before trying next server");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logError("Connection attempt sequence interrupted", ie);
            }
        }
        
        // Recursive retry logic
        logNetwork("All reconnection attempts failed, will retry in 10 seconds");
        chatPanel.receiveMessage("All reconnection attempts failed. Will retry in 10 seconds...");
        try {
            Thread.sleep(2000);
            logNetwork("Retrying reconnection process");
            handleServerDisconnection();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logError("Retry wait interrupted", e);
            showError("Failed to reconnect to any available server. Please restart the application.");
        }
    }

    // Add this method to handle time synchronization messages
    private void handleTimeSync(String message) {
        String[] parts = message.split(":");
        if (parts.length < 2) return;
        
        String command = parts[1];
        
        if ("REQUEST".equals(command)) {
            // Server is requesting our time
            long clientTime = System.currentTimeMillis() + virtualClockOffset;
            logNetwork("Sending time to server: " + clientTime);
            try {
                outputStream.writeObject("TIME_SYNC:RESPONSE:" + clientTime);
                outputStream.flush();
                lastSyncTime = System.currentTimeMillis();
            } catch (IOException e) {
                logError("Failed to send time response", e);
            }
        } else if ("ADJUST".equals(command) && parts.length >= 3) {
            try {
                long adjustment = Long.parseLong(parts[2]);
                virtualClockOffset += adjustment;
                logNetwork("Adjusted virtual clock by " + adjustment + 
                        "ms, total offset: " + virtualClockOffset + "ms");
                chatPanel.receiveMessage("*** Time synchronized with server, offset: " + 
                                        virtualClockOffset + "ms ***");
            } catch (NumberFormatException e) {
                logError("Invalid time adjustment format: " + parts[2], e);
            }
        }
    }

    private void closeExistingConnections() {
        logNetwork("Closing existing connections");
        try {
            if (inputStream != null) {
                inputStream.close();
                logNetwork("Input stream closed");
            }
        } catch (Exception e) {
            logError("Error closing input stream", e);
        }
        
        try {
            if (outputStream != null) {
                outputStream.close();
                logNetwork("Output stream closed");
            }
        } catch (Exception e) {
            logError("Error closing output stream", e);
        }
        
        try {
            if (socket != null) {
                socket.close();
                logNetwork("Socket closed");
            }
        } catch (Exception e) {
            logError("Error closing socket", e);
        }
    }

    private void handleMessage(Message message) {
        System.out.println(message.toString());
        SwingUtilities.invokeLater(() -> {
            switch (message.getType()) {
                case TEXT:
                    logInfo("Received text message from " + message.getSenderId() + 
                        ": " + message.getContent() + " (time: " + new Date(message.getTimestamp()) + ")");
                    
                    String textContent = message.getContent();
                    String displayText = textContent;
                    if (textContent.startsWith("TEXT:")) {
                        displayText = textContent.substring("TEXT:".length());
                        if (displayText.contains(";")) {
                            displayText = displayText.substring(0, displayText.lastIndexOf(";"));
                        }
                    }
                    
                    chatPanel.receiveMessage(message.getSenderId() + ": " + displayText);
                    break;
                case DRAW:
                    logDrawing("Received draw message: " + message.getContent() + 
                        " (time: " + new Date(message.getTimestamp()) + ")");
                    handleDrawAction(message.getContent());
                    break;
                case CLEAR:
                    logDrawing("Received clear canvas command (time: " + new Date(message.getTimestamp()) + ")");
                    if ("CLEAR_ALL".equals(message.getContent())) {
                        clearWhiteboard();;
                    }
                    break;
                case USER_JOIN:
                    logInfo("User joined: " + message.getSenderId() + 
                        " (time: " + new Date(message.getTimestamp()) + ")");
                    chatPanel.receiveMessage("*** " + message.getSenderId() + " has joined ***");
                    break;
                case USER_LEAVE:
                    logInfo("User left: " + message.getSenderId() + 
                        " (time: " + new Date(message.getTimestamp()) + ")");
                    chatPanel.receiveMessage("*** " + message.getSenderId() + " has left ***");
                    break;
                default:
                    logInfo("Received message of unknown type: " + message.getType() + 
                        " (time: " + new Date(message.getTimestamp()) + ")");
                    break;
            }
        });
    }

    private void handleDrawAction(String actionData) {
        logDrawing("Processing draw action: " + actionData);
        
        try {
            long timestamp = 0;

            // Check if this is a raw drawing command without userId (from history)
            if (!actionData.contains(";")) {
                // Add a default userId if none is present
                actionData = actionData + ";SERVER";
                logDrawing("Added default userId to action data: " + actionData);
            }
            
            // Extract user ID from the action data
            String[] parts = actionData.split(";");
            
            String action = parts[0];
            String userId = parts.length > 1 ? parts[1] : "unknown";

            for (String part : parts) {
                if (part.startsWith("time=")) {
                    try {
                        timestamp = Long.parseLong(part.substring(5));
                    } catch (NumberFormatException e) {
                        logError("Invalid timestamp format: " + part, e);
                    }
                }
            }
            
            // Process the drawing action with timestamp information
            // For simplicity, we'll just log the timestamp but could use it for ordering
            if (timestamp > 0) {
                logDrawing("Drawing action from " + userId + " at time " + timestamp);
            }

            if (action.startsWith("START:")) {
                String coords = action.substring(6);
                String[] coordParts = coords.split(",");
                
                int x = Integer.parseInt(coordParts[0]);
                int y = Integer.parseInt(coordParts[1]);
                
                logDrawing("Starting drawing at (" + x + "," + y + ") for user " + userId);
                whiteboardPanel.startDrawing(new Point(x, y), userId);
                
            } else if (action.startsWith("DRAW:")) {
                String coords = action.substring(5);
                String[] coordParts = coords.split(",");
                
                int x = Integer.parseInt(coordParts[0]);
                int y = Integer.parseInt(coordParts[1]);
                
                logDrawing("Continuing drawing at (" + x + "," + y + ") for user " + userId);
                whiteboardPanel.continueDraw(new Point(x, y), userId);
                
            } else if (action.startsWith("END:")) {
                logDrawing("Ending drawing for user " + userId);
                whiteboardPanel.endDrawing(userId);
            } else {
                logDrawing("Unknown action type: " + action);
            }
        } catch (Exception e) {
            logError("Error processing draw action: " + actionData, e);
        }
    }

    private void updateFollowerListUI() {
        logInfo("Updating follower list UI with " + followerIps.size() + " followers");
        // This could update a status bar, a label, or add to the chat panel
        chatPanel.receiveMessage("--- Connected follower servers: " + followerIps.size() + " ---");
        for (String ip : followerIps) {
            chatPanel.receiveMessage("    → " + ip);
        }
    }
    
    private void registerUser(String username) {
        this.username = username;
        try {
            logNetwork("Registering user: " + username);
            outputStream.writeObject(username);
            outputStream.flush();
            logNetwork("User registration request sent");
            
            // Start listening for messages after registration
            startListeningForMessages();
        } catch (IOException e) {
            logError("Error registering user", e);
            showError("Error registering user: " + e.getMessage());
            System.exit(1);
        }
    }

    private void showError(String message) {
        logError("Showing error dialog: " + message, null);
        SwingUtilities.invokeLater(() -> 
            JOptionPane.showMessageDialog(frame, message, "Error", JOptionPane.ERROR_MESSAGE));
    }

    private void updateFollowerList(String followerListString) {
        logNetwork("Updating follower list: " + followerListString);
        // Clear the current follower list
        followerIps.clear();
        // Parse the new list (format: "ip1 * ip2 * ip3")
        if (followerListString != null && !followerListString.isEmpty()) {
            String[] ips = followerListString.split("\\s*\\*\\s*");
            for (String ip : ips) {
                if (!ip.trim().isEmpty()) {
                    followerIps.add(ip.trim());
                    logNetwork("Added follower IP: " + ip.trim());
                    
                    // Also add to known servers if not already there
                    if (!knownServerIps.contains(ip.trim())) {
                        knownServerIps.add(ip.trim());
                        logNetwork("Added to known servers: " + ip.trim());
                    }
                }
            }
        }
        
        // Add the current server to known servers if not already there
        String currentServer = socket.getInetAddress().getHostAddress();
        if (!knownServerIps.contains(currentServer)) {
            knownServerIps.add(currentServer);
            logNetwork("Added current server to known servers: " + currentServer);
        }
        
        logNetwork("Updated follower server list: " + followerIps);
        logNetwork("Known server list: " + knownServerIps);

        updateFollowerListUI();
    }

    private long getCurrentTime() {
        return System.currentTimeMillis() + virtualClockOffset;
    }
    
    
    public static void main(String[] args) {
        System.out.println("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + "] [INIT] SyncSpace client starting up");
        
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
                    System.out.println("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + 
                                      "] [INIT] Using default server address: localhost");
                } else {
                    System.out.println("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + 
                                      "] [INIT] Connecting to server at: " + serverAddress);
                }
                
                WhiteboardClient client = new WhiteboardClient(serverAddress, 12345);
                client.registerUser(username);
            } else {
                System.out.println("[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()) + 
                                  "] [INIT] No username provided. Exiting.");
                System.exit(0);
            }
        });
    }
}