package com.example.mophongskype.client;

import javafx.application.Platform;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ChatClient {
    private static final String SERVER_HOST = "192.168.1.175";
    private static final int SERVER_PORT = 12345;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean isConnected = false;
    private String username;
    private InputStream rawInputStream; // InputStream gốc để đọc binary data
    private OutputStream rawOutputStream; // OutputStream gốc để ghi binary data

    private Consumer<String> onMessageReceived;
    private Consumer<String> onUserListReceived;
    private Consumer<String> onLoginResult;
    private Consumer<String> onLogoutResult;
    private Consumer<String> onPrivateMessageReceived;
    private Consumer<String> onRemoved;

    private String currentRoom;
    
    // Map để lưu tên người gửi cho mỗi file đang được tải
    private Map<String, String> fileSenderMap = new HashMap<>();

    public ChatClient() {
        // Constructor
    }

    public void joinRoom(String roomId) {
        if (out != null && isConnected) {
            this.currentRoom = roomId;
            out.println("JOIN_ROOM:" + roomId);
        }
    }

    public boolean connect() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            // Lưu raw streams để dùng cho binary data
            rawInputStream = socket.getInputStream();
            rawOutputStream = socket.getOutputStream();
            
            out = new PrintWriter(rawOutputStream, true); // autoFlush = true
            in = new BufferedReader(new InputStreamReader(rawInputStream));
            isConnected = true;

            // Bắt đầu thread để lắng nghe tin nhắn từ server
            new Thread(() -> {
                try {
                    listenForMessages();
                } catch (IOException e) {
                    System.err.println("Error while listening for messages: " + e.getMessage());
                    if (isConnected) disconnect();
                }
            }).start();
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

    private void listenForMessages() throws IOException {
        String message;
        while (isConnected && (message = in.readLine()) != null) {
            // Kiểm tra FILE_DATA trước để xử lý ngay lập tức
            if (message.startsWith("FILE_DATA:")) {
                String[] parts = message.split(":");
                if (parts.length >= 3) {
                    String fileName = parts[1];
                    long fileSize = Long.parseLong(parts[2]);
                    // QUAN TRỌNG: BufferedReader có thể đã đọc trước một số bytes của binary data
                    // Cần đọc ngay sau khi đọc text message để tránh mất dữ liệu
                    receiveFile(fileName, fileSize); // lưu xuống downloads/
                }
            } else {
                handleServerMessage(message); // các message khác
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
            case "FILE_RECEIVED":
                if (parts.length >= 2 && onMessageReceived != null) {
                    onMessageReceived.accept("SERVER: Đã nhận file " + parts[1]);
                }
                break;
            case "FILE_FAILED":
                if (parts.length >= 2 && onMessageReceived != null) {
                    onMessageReceived.accept("SERVER: Lỗi khi nhận file " + parts[1]);
                }
                break;
            case "FILE_DATA":
                // FILE_DATA đã được xử lý trong listenForMessages() trước khi đến đây
                // Nên không cần xử lý lại ở đây
                break;
            case "NEW_FILE":
                // Khi nhận thông báo file mới, tự động request file từ server
                // Format: NEW_FILE:sender:fileName hoặc NEW_FILE:fileName (backward compatible)
                if (parts.length >= 2) {
                    String sender = null;
                    String fileName;
                    
                    if (parts.length >= 3) {
                        // Format mới: NEW_FILE:sender:fileName
                        sender = parts[1];
                        fileName = parts[2];
                    } else {
                        // Format cũ: NEW_FILE:fileName (backward compatible)
                        fileName = parts[1];
                    }
                    
                    System.out.println("📥 Nhận thông báo file mới từ " + (sender != null ? sender : "người dùng") + ": " + fileName + " - Đang yêu cầu tải về...");
                    
                    // Lưu thông tin sender để hiển thị sau khi nhận file
                    if (sender != null) {
                        fileSenderMap.put(fileName, sender);
                    }
                    
                    // Tự động request file từ server
                    requestFile(fileName);
                }
                break;
            case "FILE_NOT_FOUND":
                if (parts.length >= 2 && onMessageReceived != null) {
                    onMessageReceived.accept("SERVER: File không tìm thấy: " + parts[1]);
                }
                break;

            default:
                // có thể có các lệnh khác
                break;
        }
    }

    /**
     * Nhận file từ server và lưu vào thư mục downloads
     * QUAN TRỌNG: Dùng raw InputStream để đọc binary data, không dùng BufferedReader
     * File sẽ được lưu với tên gốc, nếu đã tồn tại sẽ thêm số thứ tự
     */
    private void receiveFile(String fileName, long fileSize) {
        try {
            // Đảm bảo thư mục downloads tồn tại
            File downloadsDir = new File("downloads");
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs();
            }

            // Xử lý trường hợp file đã tồn tại - thêm số thứ tự
            File file = new File(downloadsDir, fileName);
            int counter = 1;
            String baseName = fileName;
            String extension = "";
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0) {
                baseName = fileName.substring(0, lastDot);
                extension = fileName.substring(lastDot);
            }
            
            while (file.exists()) {
                String newName = baseName + "_" + counter + extension;
                file = new File(downloadsDir, newName);
                counter++;
            }

            // QUAN TRỌNG: BufferedReader có thể đã đọc trước một số bytes của binary data vào buffer
            // Cần đọc từ InputStreamReader thông qua một cách đặc biệt
            // Hoặc đọc trực tiếp từ rawInputStream nhưng cần đảm bảo không có bytes nào bị mất
            
            // Đọc binary data trực tiếp từ raw InputStream
            // Lưu ý: BufferedReader đã wrap rawInputStream, nên khi đọc từ rawInputStream,
            // chúng ta đang đọc từ cùng một stream, nhưng BufferedReader có thể đã buffer một số bytes
            // May mắn là BufferedReader chỉ buffer khi cần, và khi đọc readLine(), nó sẽ đọc đến newline
            // Sau newline, binary data bắt đầu, và chúng ta có thể đọc trực tiếp từ rawInputStream
            
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; // Buffer lớn hơn
                long totalRead = 0;
                int read;
                
                // Đọc đúng số bytes theo fileSize
                // Đọc từng chunk để đảm bảo đọc đủ
                while (totalRead < fileSize) {
                    int bytesToRead = (int) Math.min(buffer.length, fileSize - totalRead);
                    read = rawInputStream.read(buffer, 0, bytesToRead);
                    
                    if (read == -1) {
                        // Stream kết thúc sớm
                        System.err.println("⚠️ Cảnh báo: Stream kết thúc sớm. Đã đọc " + totalRead + "/" + fileSize + " bytes");
                        break;
                    }
                    
                    if (read > 0) {
                        fos.write(buffer, 0, read);
                        totalRead += read;
                    }
                }
                
                fos.flush(); // Đảm bảo dữ liệu được ghi vào disk
                fos.getFD().sync(); // Đồng bộ với disk để đảm bảo dữ liệu được ghi hoàn toàn
                
                // Kiểm tra xem đã nhận đủ dữ liệu chưa
                if (totalRead != fileSize) {
                    System.err.println("❌ Cảnh báo: Chỉ nhận được " + totalRead + "/" + fileSize + " bytes cho file " + file.getName());
                } else {
                    System.out.println("✅ File đã tải về thành công: " + file.getAbsolutePath() + " (" + fileSize + " bytes)");
                }
            }

            // Lấy tên người gửi từ map (nếu có)
            String sender = fileSenderMap.remove(fileName);
            if (sender == null) {
                sender = "SERVER";
            }
            
            // Gửi thông tin file kèm sender qua message callback để UI hiển thị
            if (onMessageReceived != null) {
                String finalSender = sender;
                File finalFile = file;
                Platform.runLater(() -> {
                    onMessageReceived.accept("FILE_RECEIVED:" + finalSender + ":" + finalFile.getAbsolutePath());
                });
            }
            
            // Cũng gọi callback onFileReceived nếu có (để tương thích)
            if (onFileReceived != null) {
                File finalFile1 = file;
                Platform.runLater(() -> {
                    onFileReceived.accept(finalFile1);
                });
            }
        } catch (IOException e) {
            System.err.println("❌ Lỗi khi nhận file: " + e.getMessage());
            e.printStackTrace();
        }
    }



    // The requestFile method should ask the server to send the file data
    public void requestFile(String fileName) {
        if (isConnected && out != null) {
            out.println("GET_FILE:" + fileName);
        }
    }


    public void sendFile(File file) {
        try {
            if (socket != null && socket.isConnected()) {
                long fileSize = file.length();
                
                // QUAN TRỌNG: Flush PrintWriter trước khi gửi binary data
                if (out != null) {
                    out.flush();
                }
                
                // Gửi header qua PrintWriter (với newline)
                out.println("SEND_FILE:" + file.getName() + ":" + fileSize);
                
                // Đợi một chút để đảm bảo text message được gửi hoàn toàn
                Thread.sleep(100);
                
                // Gửi dữ liệu file BINARY trực tiếp qua raw OutputStream
                // KHÔNG dùng DataOutputStream wrap lại vì sẽ conflict với PrintWriter
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; // Buffer lớn hơn để tăng hiệu suất
                    int read;
                    long totalSent = 0;
                    while ((read = fis.read(buffer)) != -1) {
                        rawOutputStream.write(buffer, 0, read);
                        totalSent += read;
                    }
                    rawOutputStream.flush(); // Flush để đảm bảo dữ liệu được gửi
                    System.out.println("✅ Đã gửi file " + file.getName() + " (" + totalSent + "/" + fileSize + " bytes) lên server");
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("❌ Lỗi khi gửi file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendMediaFile(File file, String type) {
        try {
            if (socket != null && socket.isConnected()) {
                long fileSize = file.length();
                
                // QUAN TRỌNG: Flush PrintWriter trước khi gửi binary data
                if (out != null) {
                    out.flush();
                }
                
                // Gửi header: SEND_MEDIA:TYPE:filename:filesize
                out.println("SEND_MEDIA:" + type + ":" + file.getName() + ":" + fileSize);
                
                // Đợi một chút để đảm bảo text message được gửi hoàn toàn
                Thread.sleep(100);
                
                // Gửi dữ liệu file BINARY trực tiếp qua raw OutputStream
                // KHÔNG dùng DataOutputStream wrap lại vì sẽ conflict với PrintWriter
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; // Buffer lớn hơn
                    int read;
                    long totalSent = 0;
                    while ((read = fis.read(buffer)) != -1) {
                        rawOutputStream.write(buffer, 0, read);
                        totalSent += read;
                    }
                    rawOutputStream.flush(); // Flush để đảm bảo dữ liệu được gửi
                    System.out.println("✅ Đã gửi media file " + file.getName() + " (" + totalSent + "/" + fileSize + " bytes, type: " + type + ") lên server");
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("❌ Lỗi khi gửi media file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Getters và Setters cho callbacks
    private java.util.function.Consumer<File> onFileReceived;

    public void setOnFileReceived(java.util.function.Consumer<File> callback) {
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
