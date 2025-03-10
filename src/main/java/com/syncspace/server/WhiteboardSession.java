package com.syncspace.server;

import com.syncspace.common.User;
import com.syncspace.common.WhiteboardAction;

import java.util.ArrayList;
import java.util.List;

public class WhiteboardSession {
    private List<User> users;
    private List<WhiteboardAction> actions;

    public WhiteboardSession() {
        this.users = new ArrayList<>();
        this.actions = new ArrayList<>();
    }

    public void addUser(User user) {
        users.add(user);
        // Notify all users about the new user
        notifyUsers(user, "joined");
    }

    public void removeUser(User user) {
        users.remove(user);
        // Notify all users about the user leaving
        notifyUsers(user, "left");
    }

    public void addAction(WhiteboardAction action) {
        actions.add(action);
        // Broadcast the action to all users
        broadcastAction(action);
    }

    public List<User> getUsers() {
        return users;
    }

    public List<WhiteboardAction> getActions() {
        return actions;
    }

    private void notifyUsers(User user, String action) {
        // Logic to notify users about the user joining or leaving
        System.out.println("User " + user.getUsername() + " has " + action + " the session");
    }

    private void broadcastAction(WhiteboardAction action) {
        // Logic to send the action to all connected users
        System.out.println("Broadcasting action: " + action);
    }
    
    // Method to broadcast message to all clients in the session
    public void broadcastMessage(String message) {
        System.out.println("Broadcasting message to all users: " + message);
        // In a real implementation, this would send the message to all connected clients
    }
}