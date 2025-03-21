package com.syncspace.client.ui;

import javax.swing.*;
import java.awt.*;
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
    private String currentDrawingUser = "";
    private boolean isDrawing = false;
    private Color currentColor = Color.BLACK;
    private int strokeSize = 2;
    // Map to assign different colors to different users
    private Map<String, Color> userColors;
    private static final Color[] USER_COLOR_PALETTE = {
        Color.BLACK, Color.BLUE, Color.RED, Color.GREEN, 
        Color.MAGENTA, Color.ORANGE, Color.CYAN, Color.PINK
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
        this.setBackground(Color.WHITE);
        
        // Initialize with white background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g2d.setColor(currentColor);
    }

    public void startDrawing(Point point, String userId) {
        isDrawing = true;
        currentDrawingUser = userId;
        
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
        if (isDrawing && userId.equals(currentDrawingUser)) {
            List<Point> points = userPoints.get(userId);
            if (points != null) {
                points.add(point);
                drawLine(point, userId);
                repaint();
            }
        }
    }

    public void endDrawing(String userId) {
        if (userId.equals(currentDrawingUser)) {
            isDrawing = false;
            currentDrawingUser = "";
            // Keep the points for the finished stroke
        }
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
        g.drawImage(canvas, 0, 0, null);
    }

    public BufferedImage getCanvas() {
        return canvas;
    }

    public void clearCanvas() {
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g2d.setColor(currentColor);
        userPoints.clear();
        currentDrawingUser = "";
        isDrawing = false;
        repaint();
    }

    public void setColor(Color color) {
        this.currentColor = color;
    }

    public void setStrokeSize(int size) {
        this.strokeSize = size;
    }
}