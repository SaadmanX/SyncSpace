package com.syncspace.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ChatPanel extends JPanel {
    private JTextPane chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private Font messageFont;
    private Font systemFont;
    private Font inputFont;
    private Color systemMessageColor = new Color(100, 100, 100);
    private Color incomingMessageColor = new Color(30, 70, 150);
    private Color outgoingMessageColor = new Color(10, 120, 10);
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");

    public ChatPanel() {
        // Load fonts
        messageFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        systemFont = new Font(Font.SANS_SERIF, Font.ITALIC, 12);
        inputFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        
        // Set layout manager with better spacing
        setLayout(new BorderLayout(0, 5));
        setBorder(new EmptyBorder(10, 10, 10, 10));
        setBackground(new Color(245, 245, 245));
        setPreferredSize(new Dimension(270, 0));
        
        // Create a titled border for the chat area
        JLabel titleLabel = new JLabel("Chat");
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        titleLabel.setBorder(new EmptyBorder(0, 0, 10, 0));
        
        // Create the chat area with styled text capabilities
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setFont(messageFont);
        chatArea.setBackground(Color.WHITE);
        
        // Enable automatic word wrapping for the chat area
        StyledDocument doc = chatArea.getStyledDocument();
        SimpleAttributeSet center = new SimpleAttributeSet();
        StyleConstants.setAlignment(center, StyleConstants.ALIGN_LEFT);
        doc.setParagraphAttributes(0, doc.getLength(), center, false);
        
        // Add chat area to a scroll pane with improved styling
        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(220, 220, 220), 1),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        
        // Create a panel for the chat area with a title
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.add(titleLabel, BorderLayout.NORTH);
        chatPanel.add(scrollPane, BorderLayout.CENTER);
        chatPanel.setBackground(new Color(245, 245, 245));
        add(chatPanel, BorderLayout.CENTER);

        // Create a styled input field
        inputField = new JTextField();
        inputField.setFont(inputFont);
        inputField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1, true),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        
        // Add key listener for Enter key
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    sendMessage();
                }
            }
        });

        // Style the send button with a modern look
        sendButton = new JButton("Send");
        sendButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        sendButton.setBackground(new Color(66, 134, 244));
        sendButton.setForeground(Color.WHITE);
        sendButton.setBorder(BorderFactory.createEmptyBorder(8, 15, 8, 15));
        sendButton.setFocusPainted(false);
        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        // Create the input panel with improved layout
        JPanel inputPanel = new JPanel();
        inputPanel.setLayout(new BorderLayout(5, 0));
        inputPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        inputPanel.setBackground(new Color(245, 245, 245));
        
        add(inputPanel, BorderLayout.SOUTH);
        
        // Add welcome message
        appendSystemMessage("Welcome to SyncSpace Chat");
    }

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (!message.isEmpty()) {
            appendOutgoingMessage("You", message);
            inputField.setText("");
            // Here you would add the code to send the message to the server
        }
    }

    public void receiveMessage(String message) {
        // Check if this is a system message
        if (message.startsWith("***") && message.endsWith("***")) {
            appendSystemMessage(message.substring(3, message.length() - 3).trim());
        } 
        // Check if this is an incoming message from another user
        else if (message.contains(": ")) {
            String[] parts = message.split(": ", 2);
            if (parts.length == 2) {
                String sender = parts[0];
                String content = parts[1];
                
                // Don't show "You:" prefix for outgoing messages
                if (!sender.equals("You")) {
                    appendIncomingMessage(sender, content);
                }
            } else {
                // Just a regular message
                appendText(message, messageFont, Color.BLACK);
            }
        } 
        // For messages without clear formatting, just append as normal text
        else {
            appendText(message, messageFont, Color.BLACK);
        }
    }
    
    private void appendSystemMessage(String message) {
        String timestamp = timeFormat.format(new Date());
        appendText("[" + timestamp + "] " + message, systemFont, systemMessageColor);
    }
    
    private void appendIncomingMessage(String sender, String message) {
        String timestamp = timeFormat.format(new Date());
        appendText("[" + timestamp + "] " + sender + ":", 
                new Font(messageFont.getFamily(), Font.BOLD, messageFont.getSize()), 
                incomingMessageColor);
        appendText(" " + message, messageFont, Color.BLACK);
    }
    
    private void appendOutgoingMessage(String sender, String message) {
        String timestamp = timeFormat.format(new Date());
        appendText("[" + timestamp + "] " + sender + ":", 
                new Font(messageFont.getFamily(), Font.BOLD, messageFont.getSize()), 
                outgoingMessageColor);
        appendText(" " + message, messageFont, Color.BLACK);
    }
    
    private void appendText(String text, Font font, Color color) {
        StyledDocument doc = chatArea.getStyledDocument();
        Style style = chatArea.addStyle("Style", null);
        StyleConstants.setFontFamily(style, font.getFamily());
        StyleConstants.setFontSize(style, font.getSize());
        StyleConstants.setBold(style, font.isBold());
        StyleConstants.setItalic(style, font.isItalic());
        StyleConstants.setForeground(style, color);
        
        try {
            doc.insertString(doc.getLength(), text + "\n", style);
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }
}