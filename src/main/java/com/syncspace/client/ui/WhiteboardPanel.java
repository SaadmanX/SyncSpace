package com.syncspace.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WhiteboardPanel extends JPanel {
    private BufferedImage canvas;
    private Graphics2D g2d;
    // Map to track drawing points per user
    private Map<String, List<Point>> userPoints;
    // Map to track which users are currently drawing
    private Map<String, Boolean> activeDrawers;
    private Color currentColor = Color.BLACK;
    private int strokeSize = 2;
    // Map to assign different colors to different users
    private Map<String, Color> userColors;
    private static final Color[] USER_COLOR_PALETTE = {
        Color.BLACK, Color.BLUE, Color.RED, Color.GREEN, 
        Color.MAGENTA, Color.ORANGE, Color.CYAN, Color.PINK
    };
    private int nextColorIndex = 0;
    
    // Status message display
    private String statusMessage = null;
    private long statusMessageTime = 0;
    private static final long STATUS_DISPLAY_DURATION = 3000; // 3 seconds
    
    // Last active time for users
    private Map<String, Long> lastUserActivityTime = new HashMap<>();
    private static final long USER_ACTIVITY_TIMEOUT = 5000; // 5 seconds

    public WhiteboardPanel() {
        this.setPreferredSize(new Dimension(800, 600));
        this.canvas = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        this.g2d = canvas.createGraphics();
        this.g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.g2d.setPaint(currentColor);
        this.g2d.setStroke(new BasicStroke(strokeSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        this.userPoints = new HashMap<>();
        this.userColors = new HashMap<>();
        this.activeDrawers = new HashMap<>();
        
        // Add border and visual improvements
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 220), 1),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        
        // Initialize with white background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g2d.setColor(currentColor);
        
        // Start animation timer for status messages and activity indicators
        new Timer(100, e -> repaint()).start();
    }

    public void startDrawing(Point point, String userId) {
        // Set this user as actively drawing
        activeDrawers.put(userId, true);
        
        // Assign color to user if not already assigned
        if (!userColors.containsKey(userId)) {
            userColors.put(userId, USER_COLOR_PALETTE[nextColorIndex % USER_COLOR_PALETTE.length]);
            nextColorIndex++;
            
            // Show welcome message for new user
            showStatus("User " + userId + " joined the whiteboard");
        }
        
        // Update activity time
        lastUserActivityTime.put(userId, System.currentTimeMillis());
        
        // Initialize or clear points for this user
        userPoints.put(userId, new ArrayList<>());
        userPoints.get(userId).add(point);
        repaint();
    }

    public void continueDraw(Point point, String userId) {
        // Check if this user is actively drawing
        if (activeDrawers.getOrDefault(userId, false)) {
            List<Point> points = userPoints.get(userId);
            if (points != null) {
                points.add(point);
                drawLine(point, userId);
                
                // Update activity time
                lastUserActivityTime.put(userId, System.currentTimeMillis());
                
                repaint();
            }
        }
    }

    public void endDrawing(String userId) {
        // Mark this user as no longer drawing
        activeDrawers.put(userId, false);
        
        // Update activity time
        lastUserActivityTime.put(userId, System.currentTimeMillis());
        
        // Keep the points for the finished stroke
    }

    private void drawLine(Point point, String userId) {
        List<Point> points = userPoints.get(userId);
        if (points != null && points.size() > 1) {
            Point lastPoint = points.get(points.size() - 2);
            Color userColor = userColors.getOrDefault(userId, currentColor);
            
            g2d.setColor(userColor);
            g2d.setStroke(new BasicStroke(strokeSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.drawLine(lastPoint.x, lastPoint.y, point.x, point.y);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Draw a subtle grid pattern in the background
        drawBackgroundGrid(g2);
        
        // Draw canvas content
        g.drawImage(canvas, 0, 0, null);
        
        // Draw active user indicators
        drawActiveUserIndicators(g2);
        
        // Draw status message if active
        drawStatusMessage(g2);
    }
    
    private void drawBackgroundGrid(Graphics2D g) {
        int gridSize = 20;
        
        // Fill with soft background color
        g.setColor(new Color(248, 248, 252));
        g.fillRect(0, 0, getWidth(), getHeight());
        
        // Draw grid lines
        g.setColor(new Color(240, 240, 245));
        
        // Draw vertical lines
        for (int x = 0; x < getWidth(); x += gridSize) {
            g.drawLine(x, 0, x, getHeight());
        }
        
        // Draw horizontal lines
        for (int y = 0; y < getHeight(); y += gridSize) {
            g.drawLine(0, y, getWidth(), y);
        }
    }
    
    private void drawActiveUserIndicators(Graphics2D g) {
        long currentTime = System.currentTimeMillis();
        int yOffset = 10;
        int xOffset = getWidth() - 150; // Position in top-right corner
        int indicatorSize = 10;
        int textOffset = 5;
        
        Font font = new Font("Arial", Font.BOLD, 12);
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        
        // Create a semi-transparent background panel for active users list
        boolean hasActiveUsers = false;
        for (Map.Entry<String, Color> entry : userColors.entrySet()) {
            String userId = entry.getKey();
            Long lastActivityTime = lastUserActivityTime.get(userId);
            if (lastActivityTime != null && currentTime - lastActivityTime <= USER_ACTIVITY_TIMEOUT) {
                hasActiveUsers = true;
                break;
            }
        }
        
        if (hasActiveUsers) {
            // Draw panel header
            g.setColor(new Color(0, 0, 0, 100));
            g.fillRoundRect(xOffset - 10, yOffset - 5, 140, 25, 10, 10);
            g.setColor(new Color(255, 255, 255));
            g.drawString("Active Users", xOffset, yOffset + 12);
            
            yOffset += 30;
            
            // Draw semi-transparent background for user list
            int userCount = 0;
            for (Map.Entry<String, Color> entry : userColors.entrySet()) {
                String userId = entry.getKey();
                Long lastActivityTime = lastUserActivityTime.get(userId);
                if (lastActivityTime != null && currentTime - lastActivityTime <= USER_ACTIVITY_TIMEOUT) {
                    userCount++;
                }
            }
            
            if (userCount > 0) {
                g.setColor(new Color(0, 0, 0, 60));
                g.fillRoundRect(xOffset - 10, yOffset - 5, 140, userCount * (metrics.getHeight() + 5) + 5, 10, 10);
            }
            
            // Show active users with their assigned colors
            for (Map.Entry<String, Color> entry : userColors.entrySet()) {
                String userId = entry.getKey();
                Color userColor = entry.getValue();
                
                // Skip if user hasn't been active recently
                Long lastActivityTime = lastUserActivityTime.get(userId);
                if (lastActivityTime == null || currentTime - lastActivityTime > USER_ACTIVITY_TIMEOUT) {
                    continue;
                }
                
                // Show filled circle for actively drawing users, outline for inactive
                boolean isActive = activeDrawers.getOrDefault(userId, false);
                
                // Draw user indicator
                if (isActive) {
                    // Filled circle for active users
                    g.setColor(userColor);
                    g.fillOval(xOffset, yOffset, indicatorSize, indicatorSize);
                    
                    // Draw "drawing" text
                    g.setColor(new Color(255, 255, 255, 200));
                    g.drawString("(drawing)", xOffset + 15 + metrics.stringWidth(userId), 
                                yOffset + metrics.getAscent() - metrics.getHeight()/4);
                } else {
                    // Outlined circle for inactive users
                    g.setColor(userColor);
                    g.drawOval(xOffset, yOffset, indicatorSize, indicatorSize);
                }
                
                // Draw username
                g.setColor(new Color(255, 255, 255, 220));
                g.drawString(userId, xOffset + indicatorSize + textOffset, 
                            yOffset + metrics.getAscent() - metrics.getHeight()/4);
                
                // Move to next position
                yOffset += metrics.getHeight() + 5;
            }
        }
    }
    
    private void drawStatusMessage(Graphics2D g) {
        if (statusMessage != null) {
            long currentTime = System.currentTimeMillis();
            
            // Check if message should still be displayed
            if (currentTime - statusMessageTime <= STATUS_DISPLAY_DURATION) {
                // Calculate fade effect (1.0 to 0.0)
                float alpha = 1.0f - (float)(currentTime - statusMessageTime) / STATUS_DISPLAY_DURATION;
                
                // Draw status message with semi-transparent background
                String message = statusMessage;
                Font font = new Font("Arial", Font.BOLD, 14);
                g.setFont(font);
                FontMetrics metrics = g.getFontMetrics();
                
                int messageWidth = metrics.stringWidth(message) + 40;
                int messageHeight = metrics.getHeight() + 16;
                
                int x = (getWidth() - messageWidth) / 2;
                int y = getHeight() - messageHeight - 20;
                
                // Draw drop shadow
                g.setColor(new Color(0, 0, 0, (int)(50 * alpha)));
                g.fillRoundRect(x + 2, y + 2, messageWidth, messageHeight, 15, 15);
                
                // Draw background with rounded corners
                g.setColor(new Color(40, 40, 40, (int)(220 * alpha)));
                g.fillRoundRect(x, y, messageWidth, messageHeight, 15, 15);
                
                // Draw highlight border
                g.setColor(new Color(255, 255, 255, (int)(100 * alpha)));
                g.drawRoundRect(x, y, messageWidth, messageHeight, 15, 15);
                
                // Draw text
                g.setColor(new Color(255, 255, 255, (int)(255 * alpha)));
                g.drawString(message, x + 20, y + messageHeight/2 + metrics.getAscent()/2);
            } else {
                // Message expired
                statusMessage = null;
            }
        }
    }
    
    /**
     * Show a status message on the whiteboard
     */
    public void showStatus(String message) {
        this.statusMessage = message;
        this.statusMessageTime = System.currentTimeMillis();
        repaint();
    }

    public BufferedImage getCanvas() {
        return canvas;
    }

    public void clearCanvas() {
        // Show confirmation dialog
        int option = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to clear the entire canvas for all users?",
            "Clear Canvas",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        
        if (option == JOptionPane.YES_OPTION) {
            // Perform the clear operation
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            g2d.setColor(currentColor);
            userPoints.clear();
            activeDrawers.clear();
            
            // Show status message
            showStatus("Canvas cleared successfully");
            
            repaint();
        }
    }

    public void setColor(Color color) {
        this.currentColor = color;
        showStatus("Brush color changed");
    }

    public void setStrokeSize(int size) {
        this.strokeSize = size;
        showStatus("Brush size changed to " + size + "px");
    }
}