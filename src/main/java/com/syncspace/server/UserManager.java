package com.syncspace.server;

import com.syncspace.common.User;

import java.util.HashMap;
import java.util.Map;

public class UserManager {
    private Map<String, User> activeUsers;

    public UserManager() {
        activeUsers = new HashMap<>();
    }

    public boolean registerUser(String username) {
        if (activeUsers.containsKey(username)) {
            return false; // User already exists
        }
        activeUsers.put(username, new User(username, username));
        return true; // User registered successfully
    }

    public boolean authenticateUser(String username) {
        return activeUsers.containsKey(username); // Check if user is registered
    }

    public void removeUser(String username) {
        activeUsers.remove(username); // Remove user from active users
    }

    public Map<String, User> getActiveUsers() {
        return new HashMap<>(activeUsers); // Return a copy of active users
    }
}