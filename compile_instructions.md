***Make sure you CD into the SyncSpace directory first***

**Step 1: Build project**
mvn clean package

**Step 2: Run Client(s) and Server**
***In terminal 1:***
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.server.Server

***In as many client terminals you want:***
java -cp target/syncspace-collaborative-whiteboard-1.0-SNAPSHOT.jar com.syncspace.client.WhiteboardClient
