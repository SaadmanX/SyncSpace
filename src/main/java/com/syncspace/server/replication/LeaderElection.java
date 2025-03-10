package com.syncspace.server.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LeaderElection {
    private List<String> servers;
    private String leader;
    private Random random;

    public LeaderElection(List<String> servers) {
        this.servers = new ArrayList<>(servers);
        this.random = new Random();
        this.leader = electLeader();
    }

    private String electLeader() {
        // Randomly select a server as the leader
        return servers.get(random.nextInt(servers.size()));
    }

    public String getLeader() {
        return leader;
    }

    public void updateLeader(String newLeader) {
        if (servers.contains(newLeader)) {
            leader = newLeader;
        }
    }

    public void addServer(String server) {
        servers.add(server);
        // Re-elect leader if the new server is added
        leader = electLeader();
    }

    public void removeServer(String server) {
        servers.remove(server);
        // Re-elect leader if the removed server was the leader
        if (server.equals(leader) && !servers.isEmpty()) {
            leader = electLeader();
        }
    }
}