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
        new Color(0, 102, 204),    // Blue
        new Color(204, 0, 0),      // Red
        new Color(0, 153, 51),     // Green
        new Color(153, 51, 255),   // Purple
        new Color(255, 102, 0),    // Orange
        new Color(0, 204, 204),    // Cyan
        new Color(255, 51, 153),   // Pink
        new Color(76, 76, 76)      // Dark Gray
    };
    private int nextColorIndex = 0;

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
        
        // Set panel appearance
        this.setBackground(Color.WHITE);
        this.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(220, 220, 220)),
            new EmptyBorder(5, 5, 5, 5)
        ));
        
        // Initialize with white background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g2d.setColor(currentColor);
    }

    public void startDrawing(Point point, String userId) {
        // Set this user as actively drawing
        activeDrawers.put(userId, true);
        
        // Assign color to user if not already assigned
        if (!userColors.containsKey(userId)) {
            userColors.put(userId, USER_COLOR_PALETTE[nextColorIndex % USER_COLOR_PALETTE.length]);
            nextColorIndex++;
        }
        
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
                repaint();
            }
        }
    }

    public void endDrawing(String userId) {
        // Mark this user as no longer drawing
        activeDrawers.put(userId, false);
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
        g2.drawImage(canvas, 0, 0, null);
        
        // Draw a subtle grid pattern for better visual reference
        g2.setColor(new Color(240, 240, 240));
        int gridSpacing = 20;
        for (int x = 0; x < getWidth(); x += gridSpacing) {
            g2.drawLine(x, 0, x, getHeight());
        }
        for (int y = 0; y < getHeight(); y += gridSpacing) {
            g2.drawLine(0, y, getWidth(), y);
        }
        
        // Redraw the canvas on top of the grid
        g2.drawImage(canvas, 0, 0, null);
    }

    public BufferedImage getCanvas() {
        return canvas;
    }

    public void clearCanvas() {
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g2d.setColor(currentColor);
        userPoints.clear();
        activeDrawers.clear();
        repaint();
    }

    public void setColor(Color color) {
        this.currentColor = color;
    }

    public void setStrokeSize(int size) {
        this.strokeSize = size;
    }
}