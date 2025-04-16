package com.syncspace.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced WhiteboardPanel component for the SyncSpace application.
 * Provides drawing functionality with multiple tools, colors, and collaborative features.
 */
public class WhiteboardPanel extends JPanel {
    // Drawing canvas
    private BufferedImage canvas;
    private Graphics2D g2d;

    // Tracking user drawing data
    private Map<String, List<Point>> userPoints;
    private Map<String, Boolean> activeDrawers;
    private Map<String, Color> userColors;
    private Map<String, Integer> userStrokeSizes;
    private Map<String, String> userTools;

    // Tool settings
    private String currentTool = "PEN";
    private Color currentColor = Color.BLACK;
    private int strokeSize = 2;
    private boolean isAntialiasingEnabled = true;
    private boolean isGridEnabled = false;
    private int gridSize = 20;

    // Shape drawing support
    private Point startPoint = null;
    private Point endPoint = null;
    private boolean isDrawingShape = false;
    private BufferedImage tempCanvas;
    
    // Zoom and pan support
    private float zoomFactor = 1.0f;
    private int offsetX = 0;
    private int offsetY = 0;
    private boolean isPanning = false;
    private Point lastPanPoint;

    // Color palette for user identification
    private static final Color[] USER_COLOR_PALETTE = {
        Color.BLACK, Color.BLUE, Color.RED, Color.GREEN, 
        Color.MAGENTA, Color.ORANGE, Color.CYAN, Color.PINK,
        new Color(128, 0, 0), new Color(0, 128, 0), new Color(0, 0, 128),
        new Color(128, 128, 0), new Color(128, 0, 128), new Color(0, 128, 128)
    };
    private int nextColorIndex = 0;
    
    // Constants
    private static final int DEFAULT_WIDTH = 800;
    private static final int DEFAULT_HEIGHT = 600;

    /**
     * Default constructor.
     * Creates a new WhiteboardPanel with default settings.
     */
    public WhiteboardPanel() {
        // Initialize the canvas
        initializeCanvas(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        
        // Initialize data structures
        userPoints = new HashMap<>();
        activeDrawers = new HashMap<>();
        userColors = new HashMap<>();
        userStrokeSizes = new HashMap<>();
        userTools = new HashMap<>();
        
        // Set panel properties
        setBackground(Color.LIGHT_GRAY);
        setBorder(BorderFactory.createLoweredBevelBorder());
        
        // Add mouse listeners for advanced interactions
        setupMouseListeners();
        
        // Make the panel focusable to receive keyboard events
        setFocusable(true);
    }

    /**
     * Initialize the drawing canvas with specified dimensions.
     * 
     * @param width The width of the canvas
     * @param height The height of the canvas
     */
    private void initializeCanvas(int width, int height) {
        canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        g2d = canvas.createGraphics();
        setupGraphics(g2d);
        
        // Fill with white background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, width, height);
        g2d.setColor(currentColor);
        
        // Create temp canvas for shape preview
        tempCanvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Configure graphics context with current rendering settings.
     * 
     * @param g The Graphics2D context to configure
     */
    private void setupGraphics(Graphics2D g) {
        // Set rendering hints
        if (isAntialiasingEnabled) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        } else {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        }
        
        // Set default drawing properties
        g.setColor(currentColor);
        g.setStroke(createStroke(strokeSize));
    }

    /**
     * Create a stroke with specified size and rounded caps/joins.
     * 
     * @param size The stroke width
     * @return A BasicStroke configured with the specified size
     */
    private BasicStroke createStroke(int size) {
        return new BasicStroke(size, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /**
     * Set up mouse listeners for handling drawing and interaction.
     */
    private void setupMouseListeners() {
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow(); // Ensure the panel has focus
                
                // Convert screen coordinates to canvas coordinates based on zoom and offset
                Point canvasPoint = screenToCanvas(e.getPoint());
                
                // If middle mouse button is pressed, start panning
                if (e.getButton() == MouseEvent.BUTTON2) {
                    isPanning = true;
                    lastPanPoint = e.getPoint();
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                
                // Handle right-click for context menu
                if (e.getButton() == MouseEvent.BUTTON3) {
                    showContextMenu(e.getPoint());
                    return;
                }

                // Handle left-click for drawing
                if (e.getButton() == MouseEvent.BUTTON1) {
                    // Store the start point for shape drawing
                    startPoint = canvasPoint;
                    endPoint = canvasPoint;
                    
                    switch (currentTool) {
                        case "PEN":
                        case "ERASER":
                            // Start drawing at this point
                            String localUser = "LOCAL_USER"; // This would be passed from the client
                            startDrawing(canvasPoint, localUser);
                            break;
                        
                        case "LINE":
                        case "RECTANGLE":
                        case "CIRCLE":
                            // Just store the start point and flag
                            isDrawingShape = true;
                            
                            // Create a copy of the current canvas for preview
                            Graphics2D tempG2d = tempCanvas.createGraphics();
                            tempG2d.drawImage(canvas, 0, 0, null);
                            tempG2d.dispose();
                            break;
                        
                        case "TEXT":
                            // Show text input dialog
                            String text = JOptionPane.showInputDialog(WhiteboardPanel.this, 
                                "Enter text:", "Add Text", JOptionPane.PLAIN_MESSAGE);
                            
                            if (text != null && !text.isEmpty()) {
                                // Draw text directly on canvas
                                g2d.setColor(currentColor);
                                g2d.setFont(new Font("Arial", Font.PLAIN, strokeSize * 5));
                                g2d.drawString(text, canvasPoint.x, canvasPoint.y);
                                repaint();
                            }
                            break;
                        
                        case "SELECT":
                            // Implementation for selection tool would go here
                            // For simplicity, just show a message
                            JOptionPane.showMessageDialog(WhiteboardPanel.this, 
                                "Selection feature coming soon!", 
                                "Feature Preview", JOptionPane.INFORMATION_MESSAGE);
                            break;
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                // Stop panning if it was active
                if (isPanning && e.getButton() == MouseEvent.BUTTON2) {
                    isPanning = false;
                    setCursor(Cursor.getDefaultCursor());
                    return;
                }
                
                // Handle shape completion when drawing shapes
                if (isDrawingShape && e.getButton() == MouseEvent.BUTTON1) {
                    isDrawingShape = false;
                    endPoint = screenToCanvas(e.getPoint());
                    
                    // Draw the final shape on the canvas
                    drawShapeOnCanvas();
                    
                    startPoint = null;
                    endPoint = null;
                    repaint();
                }
                
                // Handle pen/eraser completion
                if ((currentTool.equals("PEN") || currentTool.equals("ERASER")) 
                    && e.getButton() == MouseEvent.BUTTON1) {
                    String localUser = "LOCAL_USER"; // This would be passed from the client
                    endDrawing(localUser);
                }
            }
        };
        
        MouseMotionAdapter motionAdapter = new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                // Handle panning
                if (isPanning) {
                    int dx = e.getX() - lastPanPoint.x;
                    int dy = e.getY() - lastPanPoint.y;
                    offsetX += dx;
                    offsetY += dy;
                    lastPanPoint = e.getPoint();
                    repaint();
                    return;
                }
                
                // Convert to canvas coordinates
                Point canvasPoint = screenToCanvas(e.getPoint());
                
                // Handle shape preview
                if (isDrawingShape) {
                    endPoint = canvasPoint;
                    repaint();
                    return;
                }
                
                // Handle pen/eraser dragging
                if (currentTool.equals("PEN") || currentTool.equals("ERASER")) {
                    String localUser = "LOCAL_USER"; // This would be passed from the client
                    continueDraw(canvasPoint, localUser);
                }
            }
        };
        
        MouseWheelListener wheelListener = new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                // Handle zoom with mouse wheel
                int rotation = e.getWheelRotation();
                if (rotation < 0) {
                    // Zoom in
                    setZoom(zoomFactor * 1.1f);
                } else {
                    // Zoom out
                    setZoom(zoomFactor / 1.1f);
                }
            }
        };
        
        addMouseListener(mouseAdapter);
        addMouseMotionListener(motionAdapter);
        addMouseWheelListener(wheelListener);
    }

    /**
     * Draw a shape on the canvas based on the current tool and points.
     */
    private void drawShapeOnCanvas() {
        if (startPoint == null || endPoint == null) return;
        
        // Ensure the graphics context is properly configured
        g2d.setColor(currentColor);
        g2d.setStroke(createStroke(strokeSize));
        
        switch (currentTool) {
            case "LINE":
                g2d.draw(new Line2D.Float(startPoint.x, startPoint.y, endPoint.x, endPoint.y));
                break;
                
            case "RECTANGLE":
                Rectangle2D.Float rect = createRectangle(startPoint, endPoint);
                g2d.draw(rect);
                break;
                
            case "CIRCLE":
                Ellipse2D.Float ellipse = createEllipse(startPoint, endPoint);
                g2d.draw(ellipse);
                break;
        }
    }

    /**
     * Create a rectangle from two points.
     * 
     * @param start The starting point
     * @param end The ending point
     * @return A Rectangle2D.Float representing the rectangle
     */
    private Rectangle2D.Float createRectangle(Point start, Point end) {
        int x = Math.min(start.x, end.x);
        int y = Math.min(start.y, end.y);
        int width = Math.abs(end.x - start.x);
        int height = Math.abs(end.y - start.y);
        return new Rectangle2D.Float(x, y, width, height);
    }
    
    /**
     * Create an ellipse from two points.
     * 
     * @param start The starting point
     * @param end The ending point
     * @return An Ellipse2D.Float representing the ellipse
     */
    private Ellipse2D.Float createEllipse(Point start, Point end) {
        int x = Math.min(start.x, end.x);
        int y = Math.min(start.y, end.y);
        int width = Math.abs(end.x - start.x);
        int height = Math.abs(end.y - start.y);
        return new Ellipse2D.Float(x, y, width, height);
    }

    /**
     * Show the context menu at the specified point.
     * 
     * @param point The point at which to show the menu
     */
    private void showContextMenu(Point point) {
        JPopupMenu contextMenu = new JPopupMenu();
        
        JMenuItem clearItem = new JMenuItem("Clear Canvas");
        clearItem.addActionListener(e -> clearCanvas());
        contextMenu.add(clearItem);
        
        contextMenu.addSeparator();
        
        JMenu toolsMenu = new JMenu("Tools");
        String[] toolNames = {"PEN", "LINE", "RECTANGLE", "CIRCLE", "ERASER", "TEXT", "SELECT"};
        for (String toolName : toolNames) {
            JMenuItem toolItem = new JMenuItem(toolName);
            toolItem.addActionListener(e -> setTool(toolName));
            toolsMenu.add(toolItem);
        }
        contextMenu.add(toolsMenu);
        
        JMenu colorsMenu = new JMenu("Colors");
        addColorMenuItem(colorsMenu, "Black", Color.BLACK);
        addColorMenuItem(colorsMenu, "Blue", Color.BLUE);
        addColorMenuItem(colorsMenu, "Red", Color.RED);
        addColorMenuItem(colorsMenu, "Green", Color.GREEN);
        addColorMenuItem(colorsMenu, "Yellow", Color.YELLOW);
        addColorMenuItem(colorsMenu, "Orange", Color.ORANGE);
        addColorMenuItem(colorsMenu, "Pink", Color.PINK);
        addColorMenuItem(colorsMenu, "Custom...", null);
        contextMenu.add(colorsMenu);
        
        JMenu strokeMenu = new JMenu("Stroke Size");
        for (int size : new int[]{1, 2, 3, 5, 8, 12}) {
            JMenuItem sizeItem = new JMenuItem(size + "px");
            sizeItem.addActionListener(e -> setStrokeSize(size));
            strokeMenu.add(sizeItem);
        }
        contextMenu.add(strokeMenu);
        
        contextMenu.addSeparator();
        
        JCheckBoxMenuItem gridItem = new JCheckBoxMenuItem("Show Grid", isGridEnabled);
        gridItem.addActionListener(e -> {
            isGridEnabled = gridItem.isSelected();
            repaint();
        });
        contextMenu.add(gridItem);
        
        JCheckBoxMenuItem antialiasItem = new JCheckBoxMenuItem("Antialiasing", isAntialiasingEnabled);
        antialiasItem.addActionListener(e -> {
            isAntialiasingEnabled = antialiasItem.isSelected();
            setupGraphics(g2d);
            repaint();
        });
        contextMenu.add(antialiasItem);
        
        contextMenu.addSeparator();
        
        JMenuItem zoomInItem = new JMenuItem("Zoom In");
        zoomInItem.addActionListener(e -> setZoom(zoomFactor * 1.2f));
        contextMenu.add(zoomInItem);
        
        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        zoomOutItem.addActionListener(e -> setZoom(zoomFactor / 1.2f));
        contextMenu.add(zoomOutItem);
        
        JMenuItem resetZoomItem = new JMenuItem("Reset Zoom");
        resetZoomItem.addActionListener(e -> {
            zoomFactor = 1.0f;
            offsetX = 0;
            offsetY = 0;
            repaint();
        });
        contextMenu.add(resetZoomItem);
        
        contextMenu.show(this, point.x, point.y);
    }
    
    /**
     * Add a color menu item to the specified menu.
     * 
     * @param menu The menu to add the item to
     * @param name The name of the color
     * @param color The color to set, or null for custom color chooser
     */
    private void addColorMenuItem(JMenu menu, String name, Color color) {
        JMenuItem item = new JMenuItem(name);
        if (color != null) {
            item.setIcon(createColorIcon(color, 16, 16));
            item.addActionListener(e -> setColor(color));
        } else {
            item.addActionListener(e -> {
                Color newColor = JColorChooser.showDialog(this, "Choose Color", currentColor);
                if (newColor != null) {
                    setColor(newColor);
                }
            });
        }
        menu.add(item);
    }

    /**
     * Create a colored icon.
     * 
     * @param color The color of the icon
     * @param width The width of the icon
     * @param height The height of the icon
     * @return An Icon with the specified color and dimensions
     */
    private Icon createColorIcon(Color color, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setColor(color);
        g2d.fillRect(0, 0, width, height);
        g2d.setColor(Color.DARK_GRAY);
        g2d.drawRect(0, 0, width - 1, height - 1);
        g2d.dispose();
        return new ImageIcon(image);
    }

    /**
     * Convert screen coordinates to canvas coordinates.
     * 
     * @param screenPoint The point in screen coordinates
     * @return The corresponding point in canvas coordinates
     */
    private Point screenToCanvas(Point screenPoint) {
        int canvasX = (int)((screenPoint.x - offsetX) / zoomFactor);
        int canvasY = (int)((screenPoint.y - offsetY) / zoomFactor);
        return new Point(canvasX, canvasY);
    }

    /**
     * Convert canvas coordinates to screen coordinates.
     * 
     * @param canvasPoint The point in canvas coordinates
     * @return The corresponding point in screen coordinates
     */
    private Point canvasToScreen(Point canvasPoint) {
        int screenX = (int)(canvasPoint.x * zoomFactor) + offsetX;
        int screenY = (int)(canvasPoint.y * zoomFactor) + offsetY;
        return new Point(screenX, screenY);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        Graphics2D g2 = (Graphics2D) g.create();
        
        // Apply zoom and pan transformations
        g2.translate(offsetX, offsetY);
        g2.scale(zoomFactor, zoomFactor);
        
        // Draw the canvas
        g2.drawImage(canvas, 0, 0, this);
        
        // Draw grid if enabled
        if (isGridEnabled) {
            drawGrid(g2);
        }
        
        // Draw shape preview if currently drawing a shape
        if (isDrawingShape && startPoint != null && endPoint != null) {
            drawShapePreview(g2);
        }
        
        g2.dispose();
    }

    /**
     * Draw the grid on the canvas.
     * 
     * @param g The graphics context
     */
    private void drawGrid(Graphics2D g) {
        g.setColor(new Color(200, 200, 200, 100));
        g.setStroke(new BasicStroke(1));
        
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        
        // Draw vertical lines
        for (int x = 0; x < width; x += gridSize) {
            g.drawLine(x, 0, x, height);
        }
        
        // Draw horizontal lines
        for (int y = 0; y < height; y += gridSize) {
            g.drawLine(0, y, width, y);
        }
    }

    /**
     * Draw a preview of the shape being drawn.
     * 
     * @param g The graphics context
     */
    private void drawShapePreview(Graphics2D g) {
        g.setColor(currentColor);
        g.setStroke(createStroke(strokeSize));
        
        switch (currentTool) {
            case "LINE":
                g.draw(new Line2D.Float(startPoint.x, startPoint.y, endPoint.x, endPoint.y));
                break;
                
            case "RECTANGLE":
                g.draw(createRectangle(startPoint, endPoint));
                break;
                
            case "CIRCLE":
                g.draw(createEllipse(startPoint, endPoint));
                break;
        }
    }

    // Public methods for client access

    /**
     * Start drawing at the specified point for the given user.
     * 
     * @param point The starting point
     * @param userId The ID of the user who is drawing
     */
    public void startDrawing(Point point, String userId) {
        // Set this user as actively drawing
        activeDrawers.put(userId, true);
        
        // Assign color to user if not already assigned
        if (!userColors.containsKey(userId)) {
            userColors.put(userId, USER_COLOR_PALETTE[nextColorIndex % USER_COLOR_PALETTE.length]);
            nextColorIndex++;
        }
        
        // Store the user's current tool and stroke size
        userTools.put(userId, currentTool);
        userStrokeSizes.put(userId, strokeSize);
        
        // Initialize or clear points for this user
        userPoints.put(userId, new ArrayList<>());
        userPoints.get(userId).add(point);
        
        repaint();
    }

    /**
     * Continue drawing at the specified point for the given user.
     * 
     * @param point The next point in the drawing
     * @param userId The ID of the user who is drawing
     */
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

    /**
     * End drawing for the given user.
     * 
     * @param userId The ID of the user who is drawing
     */
    public void endDrawing(String userId) {
        // Mark this user as no longer drawing
        activeDrawers.put(userId, false);
        // Keep the points for the finished stroke
    }

    /**
     * Draw a line from the previous point to the current point for the given user.
     * 
     * @param point The current point
     * @param userId The ID of the user who is drawing
     */
    private void drawLine(Point point, String userId) {
        List<Point> points = userPoints.get(userId);
        if (points != null && points.size() > 1) {
            Point lastPoint = points.get(points.size() - 2);
            
            // Get the user's current tool, stroke size, and color
            String userTool = userTools.getOrDefault(userId, "PEN");
            Color userColor = userColors.getOrDefault(userId, currentColor);
            int userStrokeSize = userStrokeSizes.getOrDefault(userId, strokeSize);
            
            // If the user is using eraser, change the color to white
            if ("ERASER".equals(userTool)) {
                userColor = Color.WHITE;
                userStrokeSize *= 2;  // Make eraser a bit larger
            }
            
            g2d.setColor(userColor);
            g2d.setStroke(createStroke(userStrokeSize));
            g2d.drawLine(lastPoint.x, lastPoint.y, point.x, point.y);
        }
    }

    /**
     * Add a shape to the canvas.
     * 
     * @param shapeType The type of shape ("LINE", "RECTANGLE", "CIRCLE")
     * @param start The starting point
     * @param end The ending point
     * @param color The color of the shape
     * @param size The stroke size
     * @param userId The ID of the user who added the shape
     */
    public void addShape(String shapeType, Point start, Point end, Color color, int size, String userId) {
        g2d.setColor(color);
        g2d.setStroke(createStroke(size));
        
        switch (shapeType) {
            case "LINE":
                g2d.draw(new Line2D.Float(start.x, start.y, end.x, end.y));
                break;
                
            case "RECTANGLE":
                g2d.draw(createRectangle(start, end));
                break;
                
            case "CIRCLE":
                g2d.draw(createEllipse(start, end));
                break;
        }
        
        repaint();
    }

    /**
     * Add text to the canvas.
     * 
     * @param text The text to add
     * @param position The position of the text
     * @param color The color of the text
     * @param size The size of the text
     * @param userId The ID of the user who added the text
     */
    public void addText(String text, Point position, Color color, int size, String userId) {
        g2d.setColor(color);
        g2d.setFont(new Font("Arial", Font.PLAIN, size * 5));
        g2d.drawString(text, position.x, position.y);
        repaint();
    }

    /**
     * Get the canvas image.
     * 
     * @return The BufferedImage representing the canvas
     */
    public BufferedImage getCanvas() {
        return canvas;
    }

    /**
     * Clear the canvas.
     */
    public void clearCanvas() {
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g2d.setColor(currentColor);
        userPoints.clear();
        activeDrawers.clear();
        repaint();
    }

    /**
     * Set the current drawing color.
     * 
     * @param color The color to set
     */
    public void setColor(Color color) {
        this.currentColor = color;
    }

    /**
     * Get the current drawing color.
     * 
     * @return The current color
     */
    public Color getColor() {
        return currentColor;
    }

    /**
     * Set the stroke size.
     * 
     * @param size The stroke size to set
     */
    public void setStrokeSize(int size) {
        this.strokeSize = size;
    }
    
    /**
     * Get the current stroke size.
     * 
     * @return The current stroke size
     */
    public int getStrokeSize() {
        return strokeSize;
    }
    
    /**
     * Set the current drawing tool.
     * 
     * @param tool The tool to set ("PEN", "LINE", "RECTANGLE", "CIRCLE", "ERASER", "TEXT", "SELECT")
     */
    public void setTool(String tool) {
        this.currentTool = tool;
    }
    
    /**
     * Get the current drawing tool.
     * 
     * @return The current tool
     */
    public String getTool() {
        return currentTool;
    }
    
    /**
     * Get the current zoom factor.
     * 
     * @return The zoom factor
     */
    public float getZoom() {
        return zoomFactor;
    }
    
    /**
     * Set the zoom factor.
     * 
     * @param zoom The zoom factor to set
     */
    public void setZoom(float zoom) {
        // Limit zoom range to avoid extreme values
        zoomFactor = Math.max(0.1f, Math.min(5.0f, zoom));
        repaint();
    }
    
    /**
     * Resize the canvas.
     * 
     * @param width The new width
     * @param height The new height
     */
    public void resizeCanvas(int width, int height) {
        // Create a new canvas with the new dimensions
        BufferedImage newCanvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D newG2d = newCanvas.createGraphics();
        setupGraphics(newG2d);
        
        // Fill the new canvas with white
        newG2d.setColor(Color.WHITE);
        newG2d.fillRect(0, 0, width, height);
        
        // Copy the old canvas onto the new one
        newG2d.drawImage(canvas, 0, 0, null);
        
        // Update canvas references
        canvas = newCanvas;
        g2d = newG2d;
        
        // Update temporary canvas as well
        tempCanvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        
        repaint();
    }
}