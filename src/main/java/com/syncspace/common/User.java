package com.syncspace.common;

public class User {

    private String username;
    private String userId;
    private boolean isActive;

    public User(String username, String userId) {
        this.username = username;
        this.userId = userId;
        this.isActive = true;
    }

    public String getUsername() {
        return username;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    @Override
    public String toString() {
        return (
        "User{" +
        "username='" +
        username +
        '\'' +
        ", userId='" +
        userId +
        '\'' +
        ", isActive=" +
        isActive +
        '}'
        );
    }
}
