package com.syncspace.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SynchronizationUtils {

    private static final List<String> whiteboardState = new CopyOnWriteArrayList<>();

    // Method to synchronize the whiteboard state across clients
    public static synchronized void synchronizeWhiteboardState(String state) {
        whiteboardState.add(state);
        // Notify all clients about the updated state
        notifyClients(state);
    }

    // Method to get the current whiteboard state
    public static List<String> getWhiteboardState() {
        return whiteboardState;
    }

    // Method to notify clients about the updated state
    private static void notifyClients(String state) {
        // Implementation for notifying clients goes here
        // This could involve sending messages over a network socket
    }
}