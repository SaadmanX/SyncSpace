package com.syncspace.server.replication;

import com.syncspace.common.Message;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ReplicationManager {
    private final List<ServerInstance> followers;
    private ServerInstance leader;
    private long lastHeartbeatReceived;
    private static final long HEARTBEAT_TIMEOUT = 10000; // 10 seconds

    public ReplicationManager() {
        this.followers = new CopyOnWriteArrayList<>();
        this.lastHeartbeatReceived = System.currentTimeMillis();
    }

    public void addFollower(ServerInstance follower) {
        if (!followers.contains(follower)) {
            followers.add(follower);
            System.out.println("Added follower: " + follower.getAddress() + ":" + follower.getPort());
        }
    }

    public void removeFollower(ServerInstance follower) {
        followers.remove(follower);
        System.out.println("Removed follower: " + follower.getAddress() + ":" + follower.getPort());
    }

    public void setLeader(ServerInstance leader) {
        this.leader = leader;
        System.out.println("Set leader to: " + leader.getAddress() + ":" + leader.getPort());
    }

    public void replicateData(Object data) {
        if (leader != null) {
            System.out.println("Replicating data to " + followers.size() + " followers");
            for (ServerInstance follower : followers) {
                try {
                    follower.sendData(data);
                } catch (Exception e) {
                    System.err.println("Failed to replicate data to follower " + follower.getAddress() + ":" + follower.getPort() + ": " + e.getMessage());
                    // Consider removing unresponsive follower after multiple failures
                }
            }
        }
    }

    public void receiveDataFromLeader(Object data) {
        // Update last heartbeat time
        this.lastHeartbeatReceived = System.currentTimeMillis();
        
        // Process the received data
        if (data instanceof Message) {
            Message message = (Message) data;
            // Forward to local server for processing
            System.out.println("Received replicated data from leader: " + message.getType());
        }
    }
    
    public void receiveHeartbeat() {
        this.lastHeartbeatReceived = System.currentTimeMillis();
    }
    
    public boolean isLeaderActive() {
        return (System.currentTimeMillis() - lastHeartbeatReceived) < HEARTBEAT_TIMEOUT;
    }

    public ServerInstance getLeader() {
        return leader;
    }

    public List<ServerInstance> getFollowers() {
        return followers;
    }
}