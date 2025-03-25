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
}