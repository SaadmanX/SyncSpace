# SyncSpace
A Web-Hosted Distributed Collaborative Workspace

## Setup Instructions  

1. **Navigate to the Project Directory**  
   Ensure that you **CD into the SyncSpace directory** before proceeding and make sure you have **Maven Java Compiler**.

2. **Build the Project**  
   Run the following command to clean and build the project:  
   ```sh
   mvn clean package
   ```

3. **Start the Server**  
   - **As a Leader (Initial Server Instance)**  
     Run this command in **Terminal 1**:  
     ```sh
     java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server
     ```
   - **As a Follower (Connect to an Existing Leader)**  
     Replace `<IP_ADDRESS_OF_LEADER>` with the leader’s IP and run:  
     ```sh
     java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server <IP_ADDRESS_OF_LEADER>
     ```

4. **Start the Clients**  
   In as many additional terminals as needed, run:  
   ```sh
   java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.client.WhiteboardClient
   ```
