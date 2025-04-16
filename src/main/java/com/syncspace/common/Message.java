package com.syncspace.common;

import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MessageType {
        TEXT,
        DRAW,
        CLEAR,
        USER_JOIN,
        USER_LEAVE,
        VOICE_CHAT
    }

    private MessageType type;
    private String content;
    private String senderId;
    private long timestamp;
    
    public Message(MessageType type, String content, String senderId, long timestamp) {
        this.type = type;
        this.content = content;
        this.senderId = senderId;
        this.timestamp = timestamp;
    }

    public Message(MessageType type, String content, String senderId) {
        this.type = type;
        this.content = content;
        this.senderId = senderId;
        this.timestamp = System.currentTimeMillis();
    }

    public MessageType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getSenderId() {
        return senderId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        // Get message type name without the "MessageType." prefix
        String typeName = type.name();
        
        // Format timestamp to make it more readable
        java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        String formattedTimestamp = dateFormat.format(new java.util.Date(timestamp));
        
        // Build the string representation
        StringBuilder sb = new StringBuilder();
        sb.append("Message{type=").append(typeName);
        sb.append(", content='").append(content).append('\'');
        sb.append(", senderId='").append(senderId).append('\'');
        sb.append(", timestamp=").append(formattedTimestamp);
        sb.append('}');
        
        return sb.toString();
    }

    public static Message fromString(String input) {
        if (input == null || !input.startsWith("Message{") || !input.endsWith("}")) {
            throw new IllegalArgumentException("Invalid message format");
        }
    
        try {
            String body = input.substring(8, input.length() - 1); // remove "Message{" and "}"
            String[] parts = body.split(", (?=[a-zA-Z]+=)"); // split by ", " only before each key=
    
            String typeStr = parts[0].split("=")[1];
            String content = parts[1].split("=")[1].replaceAll("^'(.*)'$", "$1");
            String senderId = parts[2].split("=")[1].replaceAll("^'(.*)'$", "$1");
            String timestampStr = parts[3].split("=")[1];
    
            MessageType type = MessageType.valueOf(typeStr);
    
            java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            long timestamp = dateFormat.parse(timestampStr).getTime();
    
            return new Message(type, content, senderId, timestamp);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse message: " + e.getMessage(), e);
        }
    }
    
}