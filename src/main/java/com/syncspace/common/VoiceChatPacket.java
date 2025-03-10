package com.syncspace.common;

import java.io.Serializable;

public class VoiceChatPacket implements Serializable {
    private static final long serialVersionUID = 1L;

    private String userId;
    private byte[] audioData;
    private long timestamp;

    public VoiceChatPacket(String userId, byte[] audioData, long timestamp) {
        this.userId = userId;
        this.audioData = audioData;
        this.timestamp = timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public byte[] getAudioData() {
        return audioData;
    }

    public long getTimestamp() {
        return timestamp;
    }
}