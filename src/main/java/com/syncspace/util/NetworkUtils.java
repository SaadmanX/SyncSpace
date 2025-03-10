package com.syncspace.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class NetworkUtils {

    public static Socket establishConnection(String host, int port) throws IOException {
        return new Socket(host, port);
    }

    public static void sendData(Socket socket, byte[] data) throws IOException {
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(data);
        outputStream.flush();
    }

    public static byte[] receiveData(Socket socket) throws IOException {
        InputStream inputStream = socket.getInputStream();
        byte[] buffer = new byte[1024];
        int bytesRead = inputStream.read(buffer);
        if (bytesRead == -1) {
            return null; // End of stream
        }
        byte[] data = new byte[bytesRead];
        System.arraycopy(buffer, 0, data, 0, bytesRead);
        return data;
    }
}