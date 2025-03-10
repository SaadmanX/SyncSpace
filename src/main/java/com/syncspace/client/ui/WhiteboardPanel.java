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

    public void setStrokeSize(int size) {
        this.strokeSize = size;
    }
}