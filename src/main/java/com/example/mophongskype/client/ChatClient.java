package com.example.mophongskype.client;

import javafx.application.Platform;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class ChatClient {
    private static final String SERVER_HOST = "192.168.1.175";
    private static final int SERVER_PORT = 12345;

    private Socket socket;
    private PrintWriter out;
    // removed BufferedReader 'in' and PushbackInputStream to avoid read-ahead issues
    private boolean isConnected = false;
    private String username;
    private InputStream rawInputStream; // InputStream gốc để đọc binary data
    private BufferedInputStream bufferedIn; // single buffered input used for both headers and binary
    private OutputStream rawOutputStream; // OutputStream gốc để ghi binary data

    private Consumer<String> onMessageReceived;
    private Consumer<String> onUserListReceived;
    private Consumer<String> onLoginResult;
    private Consumer<String> onLogoutResult;
    private Consumer<String> onPrivateMessageReceived;
    private Consumer<String> onRemoved;

    // New callback for inline images
    public static class ImageMessage {
        public final String sender;
        public final String fileName;
        public final byte[] bytes;

        public ImageMessage(String sender, String fileName, byte[] bytes) {
            this.sender = sender;
            this.fileName = fileName;
            this.bytes = bytes;
        }
    }

    private Consumer<ImageMessage> onImageReceived;

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
            bufferedIn = new BufferedInputStream(rawInputStream);
            rawOutputStream = socket.getOutputStream();

            // PrintWriter for sending headers/text (explicit charset)
            out = new PrintWriter(new OutputStreamWriter(rawOutputStream, StandardCharsets.UTF_8), true); // autoFlush = true

            isConnected = true;

            // Bắt đầu thread để lắng nghe tin nhắn từ server
            new Thread(() -> {
                try {
                    listenForMessages();
                } catch (IOException e) {
                    System.err.println("Error while listening for messages: " + e.getMessage());
                    if (isConnected) disconnect();
                }
            }, "ChatClient-Listener").start();
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
        try {
            while (isConnected && (message = readLine(bufferedIn)) != null) {
                // Handle inline image header
                if (message.startsWith("IMAGE_DATA:")) {
                    String[] parts = message.split(":", 4);
                    if (parts.length >= 4) {
                        String sender = parts[1];
                        String fileName = parts[2];
                        long fileSize = Long.parseLong(parts[3]);
                        System.out.println("📥 Bắt đầu nhận ảnh inline: " + fileName + " (" + fileSize + " bytes) từ " + sender);
                        byte[] imageBytes = readFully(bufferedIn, fileSize);

                        // Lưu ảnh vào thư mục downloads để tránh trường hợp UI chỉ thấy tên file
                        try {
                            File downloadsDir = new File("downloads");
                            if (!downloadsDir.exists()) downloadsDir.mkdirs();

                            File outFile = new File(downloadsDir, fileName);
                            int counter = 1;
                            String base = fileName;
                            String ext = "";
                            int d = fileName.lastIndexOf('.');
                            if (d > 0) {
                                base = fileName.substring(0, d);
                                ext = fileName.substring(d);
                            }
                            while (outFile.exists()) {
                                outFile = new File(downloadsDir, base + "_" + counter + ext);
                                counter++;
                            }
                            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                                fos.write(imageBytes);
                                fos.flush();
                                fos.getFD().sync();
                            }
                            System.out.println("✅ Lưu ảnh inline vào: " + outFile.getAbsolutePath());
                        } catch (IOException ex) {
                            System.err.println("❌ Lỗi lưu ảnh inline: " + ex.getMessage());
                        }

                        if (onImageReceived != null) {
                            ImageMessage im = new ImageMessage(sender, fileName, imageBytes);
                            // Ensure UI update on JavaFX thread
                            Platform.runLater(() -> onImageReceived.accept(im));
                        }
                        continue;
                    }
                }

                // Kiểm tra FILE_DATA trước để xử lý ngay lập tức
                if (message.startsWith("FILE_DATA:")) {
                    String[] parts = message.split(":");
                    if (parts.length >= 3) {
                        String fileName = parts[1];
                        long fileSize = Long.parseLong(parts[2]);
                        // QUAN TRỌNG: Đọc ngay lập tức để tránh mất dữ liệu
                        // Binary data bắt đầu ngay sau header
                        System.out.println("📥 Bắt đầu nhận file: " + fileName + " (" + fileSize + " bytes)");
                        receiveFile(fileName, fileSize); // lưu xuống downloads/
                    }
                } else {
                    handleServerMessage(message); // các message khác
                }
            }
        } catch (IOException e) {
            if (isConnected) {
                System.err.println("❌ Lỗi khi đọc message: " + e.getMessage());
                e.printStackTrace();
            }
            throw e;
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

            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; // Buffer lớn hơn
                long totalRead = 0;
                int read;

                // Đọc đúng số bytes theo fileSize
                while (totalRead < fileSize) {
                    int bytesToRead = (int) Math.min(buffer.length, fileSize - totalRead);
                    read = bufferedIn.read(buffer, 0, bytesToRead);

                    if (read == -1) {
                        // Stream kết thúc sớm
                        System.err.println("⚠️ Stream kết thúc sớm. Đã đọc " + totalRead + "/" + fileSize + " bytes");
                        break;
                    }

                    if (read > 0) {
                        fos.write(buffer, 0, read);
                        totalRead += read;
                        // Log tiến trình cho file lớn (mỗi 100KB)
                        if (totalRead % 102400 == 0) {
                            System.out.println("  Đã đọc: " + (totalRead * 100 / fileSize) + "% (" + totalRead + "/" + fileSize + " bytes)");
                        }
                    }
                }

                fos.flush(); // Đảm bảo dữ liệu được ghi vào disk
                fos.getFD().sync(); // Đồng bộ với disk để đảm bảo dữ liệu được ghi hoàn toàn

                // Kiểm tra xem đã nhận đủ dữ liệu chưa
                if (totalRead != fileSize) {
                    System.err.println("❌ LỖI: Chỉ nhận được " + totalRead + "/" + fileSize + " bytes cho file " + file.getName());
                    System.err.println("   Thiếu: " + (fileSize - totalRead) + " bytes");
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
            // Đảm bảo gọi callback để hiển thị ảnh trong chat
            if (onMessageReceived != null) {
                String finalSender = sender;
                File finalFile = file;
                Platform.runLater(() -> {
                    try {
                        // Gửi thông báo file đã nhận để hiển thị trong chat
                        onMessageReceived.accept("FILE_RECEIVED:" + finalSender + ":" + finalFile.getAbsolutePath());
                    } catch (Exception e) {
                        System.err.println("❌ Lỗi khi gọi callback file: " + e.getMessage());
                        e.printStackTrace();
                    }
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

                // Ensure atomic send: synchronize on rawOutputStream to avoid interleaving
                synchronized (rawOutputStream) {
                    // QUAN TRỌNG: Flush PrintWriter before sending binary data
                    if (out != null) {
                        out.flush();
                    }

                    // Gửi header qua PrintWriter (với newline)
                    out.println("SEND_FILE:" + file.getName() + ":" + fileSize);

                    // Immediately flush underlying stream so header bytes go out before binary
                    rawOutputStream.flush();

                    // Gửi dữ liệu file BINARY trực tiếp qua raw OutputStream
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        long totalSent = 0;
                        while ((read = fis.read(buffer)) != -1) {
                            rawOutputStream.write(buffer, 0, read);
                            totalSent += read;
                        }
                        rawOutputStream.flush();
                        System.out.println("✅ Đã gửi file " + file.getName() + " (" + totalSent + "/" + fileSize + " bytes) lên server");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("❌ Lỗi khi gửi file: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Lỗi khi gửi file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendMediaFile(File file, String type) {
        try {
            if (socket != null && socket.isConnected()) {
                long fileSize = file.length();

                synchronized (rawOutputStream) {
                    if (out != null) out.flush();

                    // Gửi header: SEND_MEDIA:TYPE:filename:filesize
                    out.println("SEND_MEDIA:" + type + ":" + file.getName() + ":" + fileSize);

                    // Ensure header bytes are flushed
                    rawOutputStream.flush();

                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        long totalSent = 0;
                        while ((read = fis.read(buffer)) != -1) {
                            rawOutputStream.write(buffer, 0, read);
                            totalSent += read;
                        }
                        rawOutputStream.flush();
                        System.out.println("✅ Đã gửi media file " + file.getName() + " (" + totalSent + "/" + fileSize + " bytes, type: " + type + ") lên server");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("❌ Lỗi khi gửi media file: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
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

    // New setter for image callback
    public void setOnImageReceived(Consumer<ImageMessage> callback) {
        this.onImageReceived = callback;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public String getUsername() {
        return username;
    }

    // Helper to read exact number of bytes from InputStream
    private static byte[] readFully(InputStream in, long size) throws IOException {
        if (size > Integer.MAX_VALUE) throw new IOException("File too large");
        int remaining = (int) size;
        byte[] data = new byte[remaining];
        int offset = 0;
        while (remaining > 0) {
            int r = in.read(data, offset, remaining);
            if (r == -1) throw new EOFException("Stream ended prematurely while reading binary data");
            offset += r;
            remaining -= r;
        }
        return data;
    }

    // Helper to read a line (bytes until '\n') from bufferedIn using UTF-8. Returns null on EOF.
    private static String readLine(BufferedInputStream bis) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        boolean seenCR = false;
        while ((b = bis.read()) != -1) {
            if (b == '\r') {
                seenCR = true;
                continue; // peek for \n next
            }
            if (b == '\n') {
                break;
            }
            if (seenCR) {
                // previous was CR but not followed by LF, we should treat CR as part of line end -> push it
                baos.write('\r');
                seenCR = false;
            }
            baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
