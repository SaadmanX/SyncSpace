package com.syncspace.server;

import java.io.IOException;
import com.syncspace.common.Message;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;



public class Database { // code to handle the database from incomming server connections

    public static void main(String[] args){ // run the database thread here and keep listening for messages from the
        ServerSocket serverServerSocket; 
        ObjectInputStream instream;
        ObjectOutputStream outstream;
            try (ServerSocket serverSocket = new ServerSocket(1500)) {
                serverServerSocket = serverSocket;
                Socket sock = serverServerSocket.accept();
                // serverServerSocket.accept();
                if(sock.isConnected()){
                    System.out.println("CONNECTED");
                }else{
                    System.out.println("NOT CONNECTED");
                }
                outstream = new ObjectOutputStream(sock.getOutputStream());
                outstream.writeObject("DB_READY");
                outstream.flush();
                                instream = new ObjectInputStream(sock.getInputStream());
                // Read from a file and send its contents over the socket
                try (java.io.FileReader fr = new java.io.FileReader("/Users/smitster1403/Desktop/559/database_log.txt");
                    java.io.BufferedReader br = new java.io.BufferedReader(fr)) {
                    StringBuilder fileContents = new StringBuilder();
                    String line;
                    fileContents.append("ALLDRAW:");
                    while ((line = br.readLine()) != null) {
                        System.out.println("Sending: "+line);
                        fileContents.append(line).append("\n");
                    }
                    // Create a message with the file contents and send it
                    System.out.println("Sending: \n"+fileContents.toString() + "\n-----\n");
                    outstream.writeObject(fileContents.toString());
                    outstream.flush();
                    System.out.println("Sent file contents to client");
                    
                } catch (IOException e) {
                    System.err.println("Error reading from file: " + e.getMessage());
                }

                while(sock.isConnected()){
                    try{
                        Object message = instream.readObject();
                        if(message instanceof Message){
                            Message msg = (Message) message;
                            System.out.println("Received: \n" + msg.getContent());
                            try (java.io.FileWriter fw = new java.io.FileWriter("/Users/smitster1403/Desktop/559/database_log.txt", true)) {
                                fw.write(msg.getContent() + "\n");
                            } catch (IOException e) {
                                System.err.println("Error writing to file: " + e.getMessage());
                            }
                        }
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                    // Save to text file

                }
                try{

                    // Socket followerSocket = serverSocket.accept();
                }catch (Exception e){
                    e.printStackTrace();
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
    }
}
