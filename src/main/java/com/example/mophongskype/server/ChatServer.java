package com.example.mophongskype.server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChatServer {
    private static final int PORT = 12345;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private List<String> onlineUsers = new ArrayList<>();

    public void start() {
        try {
            serverSocket = new ServerSocket(PORT);
            isRunning = true;
            System.out.println("Server đang chạy trên port " + PORT);

            while (isRunning) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                new Thread(clientHandler).start();
            }
        } catch (IOException e) {
            if (isRunning) {
                System.err.println("Lỗi server: " + e.getMessage());
            }
        }
    }

    public void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi khi đóng server: " + e.getMessage());
        }
    }

    public synchronized boolean login(String username, String password, ClientHandler clientHandler) {
        if (connectedClients.containsKey(username)) {
            return false; // Username đã tồn tại
        }

        // Kiểm tra mật khẩu (trong thực tế nên kiểm tra với database)
        if (!isValidCredentials(username, password)) {
            return false; // Sai mật khẩu
        }

        connectedClients.put(username, clientHandler);
        onlineUsers.add(username);
        broadcastUserList();
        broadcastMessage("SYSTEM", username + " đã tham gia chat");
        return true;
    }

    private boolean isValidCredentials(String username, String password) {
        // Trong thực tế, nên kiểm tra với database
        // Ở đây tôi sẽ tạo một số tài khoản mẫu
        Map<String, String> validUsers = new HashMap<>();
        validUsers.put("admin", "123456");
        validUsers.put("user1", "password");
        validUsers.put("test", "test");
        validUsers.put("vien", "vien");
        validUsers.put("tuna","tuna");

        return validUsers.containsKey(username) && validUsers.get(username).equals(password);
    }

    public synchronized void logout(String username) {
        if (connectedClients.containsKey(username)) {
            connectedClients.remove(username);
            onlineUsers.remove(username);
            broadcastUserList();
            broadcastMessage("SYSTEM", username + " đã rời khỏi chat");
        }
    }

    public synchronized void removeUser(String username) {
        if (connectedClients.containsKey(username)) {
            ClientHandler clientHandler = connectedClients.get(username);
            clientHandler.sendMessage("REMOVED: Bạn đã bị xóa khỏi danh sách");
            connectedClients.remove(username);
            onlineUsers.remove(username);
            broadcastUserList();
            broadcastMessage("SYSTEM", username + " đã bị xóa khỏi danh sách");
        }
    }

    public synchronized void broadcastMessage(String sender, String message) {
        for (ClientHandler client : connectedClients.values()) {
            client.sendMessage("MESSAGE:" + sender + ":" + message);
        }
    }

    public synchronized void broadcastUserList() {
        String userList = "USERLIST:" + String.join(",", onlineUsers);
        for (ClientHandler client : connectedClients.values()) {
            client.sendMessage(userList);
        }
    }

    public synchronized void sendPrivateMessage(String sender, String receiver, String message) {
        ClientHandler receiverHandler = connectedClients.get(receiver);
        if (receiverHandler != null) {
            receiverHandler.sendMessage("PRIVATE:" + sender + ":" + message);
        }
    }

    /**
     * Broadcast ảnh inline tới tất cả client (trừ người gửi)
     * Format header do ClientHandler đảm nhiệm: IMAGE_DATA:sender:fileName:size + [bytes]
     */
    public synchronized void broadcastImage(String sender, String fileName, byte[] imageBytes) {
        for (Map.Entry<String, ClientHandler> entry : connectedClients.entrySet()) {
            if (!entry.getKey().equals(sender)) {
                entry.getValue().sendImage(sender, fileName, imageBytes);
            }
        }
    }

    /**
     * Broadcast thông báo file mới (để các client tự GET_FILE về)
     * Format: NEW_FILE:sender:fileName
     */
    public synchronized void broadcastNewFile(String sender, String fileName) {
        for (Map.Entry<String, ClientHandler> entry : connectedClients.entrySet()) {
            if (!entry.getKey().equals(sender)) {
                entry.getValue().sendMessage("NEW_FILE:" + sender + ":" + fileName);
            }
        }
    }



    public static void main(String[] args) {
        ChatServer server = new ChatServer();
        server.start();
    }
}
