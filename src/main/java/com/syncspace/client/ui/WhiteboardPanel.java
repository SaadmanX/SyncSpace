package com.syncspace.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class WhiteboardPanel extends JPanel {
    private BufferedImage canvas;
    private Graphics2D g2d;
    private List<Point> points;
    private boolean isDrawing = false;
    private Color currentColor = Color.BLACK;
    private int strokeSize = 2;

    public WhiteboardPanel() {
        this.setPreferredSize(new Dimension(800, 600));
        this.canvas = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        this.g2d = canvas.createGraphics();
        this.g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.g2d.setPaint(currentColor);
        this.g2d.setStroke(new BasicStroke(strokeSize, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        this.points = new ArrayList<>();
        this.setBackground(Color.WHITE);
        
        // Fill canvas with white initially
        this.g2d.setColor(Color.WHITE);
        this.g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        this.g2d.setColor(currentColor);
    }

    public void startDrawing(Point point) {
        isDrawing = true;
        points.clear();
        points.add(point);
        repaint();
    }

    public void continueDraw(Point point) {
        if (isDrawing) {
            points.add(point);
            drawLine(point);
            repaint();
        }
    }

    public void endDrawing() {
        isDrawing = false;
        points.clear();
    }

    private void drawLine(Point point) {
        if (points.size() > 1) {
            Point lastPoint = points.get(points.size() - 2);
            g2d.setColor(currentColor);
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
        points.clear();
        repaint();
    }

    public void setColor(Color color) {
        this.currentColor = color;
    }
    
    public Color getCurrentColor() {
        return currentColor;
    }

    public void setStrokeSize(int size) {
        this.strokeSize = size;
    }
    
    // Method to get the current state of the canvas as a byte array
    // This can be used for state synchronization
    public byte[] getCanvasState() {
        // This is a simplified example - in a real app you'd use compression
        int[] pixels = new int[canvas.getWidth() * canvas.getHeight()];
        canvas.getRGB(0, 0, canvas.getWidth(), canvas.getHeight(), pixels, 0, canvas.getWidth());
        
        // Convert int[] to byte[]
        byte[] result = new byte[pixels.length * 4];
        for (int i = 0; i < pixels.length; i++) {
            result[i * 4] = (byte) ((pixels[i] >> 24) & 0xff); // alpha
            result[i * 4 + 1] = (byte) ((pixels[i] >> 16) & 0xff); // red
            result[i * 4 + 2] = (byte) ((pixels[i] >> 8) & 0xff); // green
            result[i * 4 + 3] = (byte) (pixels[i] & 0xff); // blue
        }
        
        return result;
    }
    
    // Method to restore the canvas from a byte array
    public void restoreCanvasState(byte[] state) {
        if (state == null || state.length != canvas.getWidth() * canvas.getHeight() * 4) {
            return;
        }
        
        int[] pixels = new int[canvas.getWidth() * canvas.getHeight()];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = ((state[i * 4] & 0xff) << 24) | // alpha
                         ((state[i * 4 + 1] & 0xff) << 16) | // red
                         ((state[i * 4 + 2] & 0xff) << 8) | // green
                         (state[i * 4 + 3] & 0xff); // blue
        }
        
        canvas.setRGB(0, 0, canvas.getWidth(), canvas.getHeight(), pixels, 0, canvas.getWidth());
        repaint();
    }
}