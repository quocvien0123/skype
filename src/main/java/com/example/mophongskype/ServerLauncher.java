package com.example.mophongskype;

import com.example.mophongskype.server.ChatServer;

public class ServerLauncher {
    public static void main(String[] args) {
        System.out.println("=== SKYPE SERVER ===");
        System.out.println("Đang khởi động server...");
        
        ChatServer server = new ChatServer();
        
        // Thêm shutdown hook để đóng server khi tắt ứng dụng
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nĐang tắt server...");
            server.stop();
            System.out.println("Server đã tắt.");
        }));
        
        server.start();
    }
}
