package com.syncspace.server.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LeaderElection {
    private List<String> servers;
    private String leader;
    private String currentServerId;
    private AtomicLong lastHeartbeatTime;
    private static final long HEARTBEAT_TIMEOUT = 10000; // 10 seconds timeout

    public LeaderElection(List<String> servers) {
        this.servers = new ArrayList<>(servers);
        this.lastHeartbeatTime = new AtomicLong(System.currentTimeMillis());
        
        // Initialize leader as the server with the highest ID
        if (!servers.isEmpty()) {
            this.leader = servers.stream().max(String::compareTo).orElse(servers.get(0));
            System.out.println("Initial leader set to: " + this.leader);
        }
    }
    
    public void setCurrentServerId(String serverId) {
        this.currentServerId = serverId;
    }

    public String getLeader() {
        return leader;
    }

    public void updateLeader(String newLeader) {
        if (servers.contains(newLeader)) {
            leader = newLeader;
            System.out.println("Leader updated to: " + leader);
            
            // Reset heartbeat time when leader changes
            heartbeatReceived();
        }
    }
    
    public boolean isLeader() {
        return currentServerId != null && currentServerId.equals(leader);
    }
    
    public void heartbeatReceived() {
        lastHeartbeatTime.set(System.currentTimeMillis());
    }
    
    public boolean shouldStartElection() {
        // Check if it's time to start an election (no heartbeat for too long)
        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime.get();
        return timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT;
    }

    public void addServer(String server) {
        if (!servers.contains(server)) {
            servers.add(server);
            System.out.println("Added server to election pool: " + server);
            
            // Re-evaluate leader if new server has higher priority
            if (server.compareTo(leader) > 0) {
                updateLeader(server);
            }
        }
    }

    public void removeServer(String server) {
        servers.remove(server);
        System.out.println("Removed server from election pool: " + server);
        
        // Re-elect leader if the removed server was the leader
        if (server.equals(leader) && !servers.isEmpty()) {
            String newLeader = servers.stream().max(String::compareTo).orElse(servers.get(0));
            updateLeader(newLeader);
        }
    }
    
    public String electLeader() {
        if (!servers.isEmpty()) {
            // Use a simple approach - highest server ID becomes leader
            String newLeader = servers.stream().max(String::compareTo).orElse(servers.get(0));
            updateLeader(newLeader);
            return newLeader;
        }
        return null;
    }
}