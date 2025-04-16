package com.syncspace.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class ChatPanel extends JPanel {
    private JTextArea chatArea;
    private JTextField inputField;
    private JButton sendButton;

    public ChatPanel() {
        setLayout(new BorderLayout());

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(chatArea);
        add(scrollPane, BorderLayout.CENTER);

        // JPanel inputPanel = new JPanel();
        // inputPanel.setLayout(new BorderLayout());

        // inputField = new JTextField();
        // inputPanel.add(inputField, BorderLayout.CENTER);

        // sendButton = new JButton("Send");
        // sendButton.addActionListener(new ActionListener() {
        //     @Override
        //     public void actionPerformed(ActionEvent e) {
        //         sendMessage();
        //     }
        // });
        // inputPanel.add(sendButton, BorderLayout.EAST);

        // add(inputPanel, BorderLayout.SOUTH);
    }

    private void sendMessage() {
        String message = inputField.getText();
        if (!message.isEmpty()) {
            chatArea.append("You: " + message + "\n");
            inputField.setText("");
            // Here you would add the code to send the message to the server
        }
    }

    public void receiveMessage(String message) {
        chatArea.append(message + "\n");
    }
}