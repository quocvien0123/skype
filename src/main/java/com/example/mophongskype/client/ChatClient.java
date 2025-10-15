package com.example.mophongskype.client;

import javafx.application.Platform;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

public class ChatClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 12345;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isConnected = false;
    private String username;

    private Consumer<String> onMessageReceived;
    private Consumer<String> onUserListReceived;
    private Consumer<String> onLoginResult;
    private Consumer<String> onLogoutResult;
    private Consumer<String> onPrivateMessageReceived;
    private Consumer<String> onRemoved;

    public ChatClient() {
        // Constructor
    }
    private String currentRoom;

    public void joinRoom(String roomId) {
        if (out != null && isConnected) {
            this.currentRoom = roomId;
            out.println("JOIN_ROOM:" + roomId);
        }
    }


    public boolean connect() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            isConnected = true;

            // Bắt đầu thread để lắng nghe tin nhắn từ server
            new Thread(this::listenForMessages).start();
            return true;
        } catch (IOException e) {
            System.err.println("Không thể kết nối đến server: " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        isConnected = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Lỗi khi đóng kết nối: " + e.getMessage());
        }
    }

    public boolean login(String username, String password) {
        if (!isConnected) {
            return false;
        }

        this.username = username;
        out.println("LOGIN:" + username + ":" + password);
        return true;
    }

    public void logout() {
        if (isConnected && username != null) {
            out.println("LOGOUT");
        }
    }

    public void sendMessage(String message) {
        if (isConnected && username != null) {
            out.println("MESSAGE:" + message);
        }
    }

    public void sendPrivateMessage(String receiver, String message) {
        if (isConnected && username != null) {
            out.println("PRIVATE:" + receiver + ":" + message);
        }
    }

    public void removeUser(String userToRemove) {
        if (isConnected && username != null) {
            out.println("REMOVE_USER:" + userToRemove);
        }
    }

    private void listenForMessages() {
        try {
            String message;
            while (isConnected && (message = in.readLine()) != null) {
                handleServerMessage(message);
            }
        } catch (IOException e) {
            if (isConnected) {
                System.err.println("Lỗi nhận tin nhắn: " + e.getMessage());
            }
        }
    }

    private void handleServerMessage(String message) {
        String[] parts = message.split(":", 3);
        String command = parts[0];

        switch (command) {
            case "LOGIN_SUCCESS":
                if (onLoginResult != null) onLoginResult.accept("SUCCESS");
                break;
            case "LOGIN_FAILED":
                if (onLoginResult != null)
                    onLoginResult.accept("FAILED:" + (parts.length > 1 ? parts[1] : "Lỗi đăng nhập"));
                break;
            case "LOGOUT_SUCCESS":
                if (onLogoutResult != null) onLogoutResult.accept("SUCCESS");
                break;
            case "MESSAGE":
                if (parts.length >= 3 && onMessageReceived != null) {
                    onMessageReceived.accept(parts[1] + ":" + parts[2]);
                }
                break;
            case "PRIVATE":
                if (parts.length >= 3 && onPrivateMessageReceived != null) {
                    onPrivateMessageReceived.accept(parts[1] + ":" + parts[2]);
                }
                break;
            case "USERLIST":
                if (parts.length >= 2 && onUserListReceived != null) {
                    onUserListReceived.accept(parts[1]);
                }
                break;
            case "REMOVED":
                if (onRemoved != null) onRemoved.accept("Bạn đã bị xóa khỏi danh sách");
                break;

            case "FILE_RECEIVED": // server báo đã nhận file
                if (parts.length >= 2 && onMessageReceived != null) {
                    onMessageReceived.accept("SERVER: Đã nhận file " + parts[1]);
                }
                break;

            case "FILE_FAILED": // server báo lỗi khi nhận file
                if (parts.length >= 2 && onMessageReceived != null) {
                    onMessageReceived.accept("SERVER: Lỗi khi nhận file " + parts[1]);
                }
                break;

            case "FILE_DATA": // server bắt đầu gửi file
                if (parts.length >= 3) {
                    String fileName = parts[1];
                    long fileSize = Long.parseLong(parts[2]);
                    receiveFile(fileName, fileSize);
                }
                break;
            case "IMAGE_FILE":
                if (parts.length >= 2) {
                    String fileName = parts[1];
                    File file = new File("downloads/" + fileName);

                    if (!file.exists()) {
                        requestFile(fileName);
                    } else if (onFileReceived != null) {
                        Platform.runLater(() -> onFileReceived.accept(file));
                    }
                }
                break;
            case "FILE_SAVED": // server báo file đã gửi xong
                if (parts.length >= 3 && onFileReceived != null) {
                    File file = new File(parts[1]);
                    boolean isImage = "IMAGE".equals(parts[2]);
                    File finalFile = file;
                    Platform.runLater(() -> onFileReceived.accept(finalFile));
                }
                break;


        }
    }

    private void receiveFile(String fileName, long fileSize) {
        try {
            File downloadsDir = new File("downloads");
            if (!downloadsDir.exists()) downloadsDir.mkdir();

            File file = new File(downloadsDir, fileName);
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            FileOutputStream fos = new FileOutputStream(file);

            byte[] buffer = new byte[4096];
            long totalRead = 0;
            int read;
            while (totalRead < fileSize &&
                    (read = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                fos.write(buffer, 0, read);
                totalRead += read;
            }
            fos.close();

            if (onFileReceived != null) {
                Platform.runLater(() -> onFileReceived.accept(file));
            }


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void requestFile(String fileName) {
        if (socket != null && socket.isConnected()) {
            out.println("GET_FILE:" + fileName);
        }
    }

    public void sendFile(File file) {
        try {
            if (socket != null && socket.isConnected()) {
                long fileSize = file.length();
                // Gửi header
                out.println("SEND_FILE:" + file.getName() + ":" + fileSize);

                // Gửi dữ liệu file
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                FileInputStream fis = new FileInputStream(file);

                byte[] buffer = new byte[4096];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, read);
                }
                dos.flush();
                fis.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }





    // Getters và Setters cho callbacks
    private Consumer<File> onFileReceived;

    public void setOnFileReceived(Consumer<File> callback) {
        this.onFileReceived = callback;
    }

    public void setOnMessageReceived(Consumer<String> callback) {
        this.onMessageReceived = callback;
    }

    public void setOnUserListReceived(Consumer<String> callback) {
        this.onUserListReceived = callback;
    }

    public void setOnLoginResult(Consumer<String> callback) {
        this.onLoginResult = callback;
    }

    public void setOnLogoutResult(Consumer<String> callback) {
        this.onLogoutResult = callback;
    }

    public void setOnPrivateMessageReceived(Consumer<String> callback) {
        this.onPrivateMessageReceived = callback;
    }

    public void setOnRemoved(Consumer<String> callback) {
        this.onRemoved = callback;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getUsername() {
        return username;
    }
}
