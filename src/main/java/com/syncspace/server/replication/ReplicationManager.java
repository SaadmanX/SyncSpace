package com.syncspace.server.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ReplicationManager {
    private final List<ServerInstance> followers;
    private ServerInstance leader;

    public ReplicationManager() {
        this.followers = new CopyOnWriteArrayList<>();
    }

    public void addFollower(ServerInstance follower) {
        followers.add(follower);
    }

    public void removeFollower(ServerInstance follower) {
        followers.remove(follower);
    }

    public void setLeader(ServerInstance leader) {
        this.leader = leader;
    }

    public void replicateData(Object data) {
        if (leader != null) {
            for (ServerInstance follower : followers) {
                follower.sendData(data);
            }
        }
    }

    public void receiveDataFromLeader(Object data) {
        // Handle data received from the leader
        // Update local state or notify clients as necessary
    }

    public ServerInstance getLeader() {
        return leader;
    }

    public List<ServerInstance> getFollowers() {
        return followers;
    }
}

class ServerInstance {
    private String address;

    public ServerInstance(String address) {
        this.address = address;
    }

    public void sendData(Object data) {
        // Logic to send data to this server instance
    }

    public String getAddress() {
        return address;
    }
}