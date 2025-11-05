package com.example.mophongskype.server;

import java.io.*;
import java.net.*;

public class ClientHandler implements Runnable {
    private Socket clientSocket;
    private ChatServer server;
    private PrintWriter out;
    private BufferedReader in;
    private InputStream rawInputStream; // InputStream gốc để đọc binary data
    private OutputStream rawOutputStream; // OutputStream gốc để ghi binary data
    private String username;

    public ClientHandler(Socket socket, ChatServer server) {
        this.clientSocket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            // Lưu raw streams để dùng cho binary data
            rawInputStream = clientSocket.getInputStream();
            rawOutputStream = clientSocket.getOutputStream();
            
            // PrintWriter với autoFlush = true để luôn đẩy buffer ra
            out = new PrintWriter(rawOutputStream, true);
            in = new BufferedReader(new InputStreamReader(rawInputStream));

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                handleMessage(inputLine);
            }
        } catch (IOException e) {
            System.err.println("Lỗi xử lý client: " + e.getMessage());
        } finally {
            try {
                if (username != null) {
                    server.logout(username);
                }
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Lỗi đóng kết nối: " + e.getMessage());
            }
        }
    }

    private void handleMessage(String message) {
        String[] parts = message.split(":", 4); // mở rộng để chứa filename + filesize
        String command = parts[0];

        switch (command) {
            case "LOGIN":
                if (parts.length >= 3) {
                    username = parts[1];
                    String password = parts[2];
                    boolean success = server.login(username, password, this);
                    if (success) {
                        sendMessage("LOGIN_SUCCESS");
                    } else {
                        sendMessage("LOGIN_FAILED:Tài khoản hoặc mật khẩu không đúng");
                    }
                } else {
                    sendMessage("LOGIN_FAILED:Thiếu thông tin đăng nhập");
                }
                break;

            case "MESSAGE":
                if (parts.length >= 2 && username != null) {
                    // parts[1] chứa toàn bộ message (vì split limit 4)
                    server.broadcastMessage(username, parts[1]);
                }
                break;

            case "PRIVATE":
                if (parts.length >= 3 && username != null) {
                    String receiver = parts[1];
                    String privateMessage = parts[2];
                    server.sendPrivateMessage(username, receiver, privateMessage);
                }
                break;

            case "SEND_FILE":
            case "SEND_MEDIA":
                // Format SEND_FILE:filename:filesize
                // Format SEND_MEDIA:TYPE:filename:filesize  (TYPE=AUDIO|VIDEO|IMAGE)
                try {
                    if (command.equals("SEND_FILE") && parts.length >= 3) {
                        String fileName = parts[1];
                        long fileSize = Long.parseLong(parts[2]);
                        if (receiveFile(fileName, fileSize)) {
                            // File nhận thành công, thông báo cho người gửi
                            sendMessage("FILE_RECEIVED:" + fileName);
                            // Broadcast cho tất cả client khác (trừ người gửi) để họ tự động tải về
                            server.broadcastNewFile(username, fileName);
                            // Broadcast tin nhắn thông báo
                            server.broadcastMessage("SYSTEM", username + " đã gửi file: " + fileName);
                        } else {
                            sendMessage("FILE_FAILED:" + fileName);
                        }
                    } else if (command.equals("SEND_MEDIA") && parts.length >= 4) {
                        String mediaType = parts[1]; // e.g. AUDIO or VIDEO or IMAGE
                        String fileName = parts[2];
                        long fileSize = Long.parseLong(parts[3]);
                        if (receiveFile(fileName, fileSize)) {
                            // File nhận thành công
                            sendMessage("FILE_RECEIVED:" + fileName);
                            if ("IMAGE".equalsIgnoreCase(mediaType)) {
                                // Đọc bytes ảnh và broadcast inline cho các client khác
                                File f = new File("uploads", fileName);
                                try {
                                    byte[] imageBytes = readFileToBytes(f);
                                    server.broadcastImage(username, fileName, imageBytes);
                                } catch (IOException ex) {
                                    System.err.println("Lỗi đọc bytes ảnh: " + ex.getMessage());
                                }
                                // Tùy chọn: vẫn có thể gửi SYSTEM thông báo
                                server.broadcastMessage("SYSTEM", username + " đã gửi IMAGE: " + fileName);
                            } else {
                                // non-image media: giữ nguyên hành vi - để client tự tải
                                server.broadcastNewFile(username, fileName);
                                server.broadcastMessage("SYSTEM", username + " đã gửi " + mediaType + ": " + fileName);
                            }
                        } else {
                            sendMessage("FILE_FAILED:" + fileName);
                        }
                    }
                } catch (NumberFormatException e) {
                    sendMessage("FILE_FAILED:Sai định dạng filesize");
                }
                break;

            case "LOGOUT":
                if (username != null) {
                    server.logout(username);
                    sendMessage("LOGOUT_SUCCESS");
                }
                break;

            case "REMOVE_USER":
                if (parts.length >= 2 && username != null) {
                    String userToRemove = parts[1];
                    server.removeUser(userToRemove);
                }
                break;

            case "GET_FILE":
                if (parts.length >= 2) {
                    String fileName = parts[1];
                    sendFileToClient(fileName);
                }
                break;


            default:
                // Unknown command -> ignore or send back
                break;
        }
    }

    /**
     * Gửi file từ server đến client
     * QUAN TRỌNG: Phải flush PrintWriter và đảm bảo không có buffer còn lại
     */
    private void sendFileToClient(String fileName) {
        try {
            File file = new File("uploads", fileName); // file trên server
            if (!file.exists()) {
                sendMessage("FILE_NOT_FOUND:" + fileName);
                return;
            }

            long fileSize = file.length();
            
            // QUAN TRỌNG: Flush PrintWriter trước khi gửi binary data
            if (out != null) {
                out.flush();
            }
            
            // Gửi header trước qua PrintWriter (với newline)
            sendMessage("FILE_DATA:" + file.getName() + ":" + fileSize);
            
            // Đợi một chút để đảm bảo text message được gửi hoàn toàn
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

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
                System.out.println("✅ Đã gửi file " + fileName + " (" + totalSent + "/" + fileSize + " bytes) đến " + username);
            }
        } catch (IOException e) {
            e.printStackTrace();
            sendMessage("FILE_FAILED:" + fileName);
        }
    }


    /**
     * Nhận file từ client và lưu vào thư mục uploads trên server
     * QUAN TRỌNG: Dùng raw InputStream để đọc binary data, không dùng BufferedReader
     * @return true nếu nhận thành công, false nếu có lỗi
     */
    private boolean receiveFile(String fileName, long fileSize) {
        try {
            File uploadsDir = new File("uploads");
            if (!uploadsDir.exists()) {
                uploadsDir.mkdirs();
            }

            File file = new File(uploadsDir, fileName);
            
            // Đọc binary data trực tiếp từ raw InputStream
            // Lưu ý: BufferedReader đã wrap rawInputStream, nhưng khi đọc binary,
            // chúng ta đọc trực tiếp từ rawInputStream để tránh xử lý text encoding
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; // Buffer lớn hơn
                long totalRead = 0;
                int read;
                
                // Đọc đúng số bytes theo fileSize
                while (totalRead < fileSize) {
                    int bytesToRead = (int) Math.min(buffer.length, fileSize - totalRead);
                    read = rawInputStream.read(buffer, 0, bytesToRead);
                    
                    if (read == -1) {
                        // Stream kết thúc sớm
                        System.err.println("⚠️ Stream kết thúc sớm. Đã đọc " + totalRead + "/" + fileSize + " bytes");
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
                    System.err.println("❌ Cảnh báo: Chỉ nhận được " + totalRead + "/" + fileSize + " bytes cho file " + fileName);
                    return false;
                }
            }

            System.out.println("✅ Đã nhận file: " + fileName + " (" + fileSize + " bytes) từ " + username);
            return true;
        } catch (IOException e) {
            System.err.println("❌ Lỗi nhận file: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // Gửi text message cho client (luôn println + autoFlush)
    public synchronized void sendMessage(String message) {
        if (out != null) {
            out.println(message);
            // out.flush(); // autoFlush = true nên không bắt buộc, nhưng giữ nếu muốn
        }
    }
    /**
     * Gửi ảnh dưới dạng byte array cho client
     */
    public void sendImage(String sender, String fileName, byte[] imageBytes) {
        try {
            // Gửi header trước
            sendMessage("IMAGE_DATA:" + sender + ":" + fileName + ":" + imageBytes.length);

            // Gửi dữ liệu ảnh
            rawOutputStream.write(imageBytes);
            rawOutputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Helper: đọc toàn bộ file thành byte[]
    private byte[] readFileToBytes(File file) throws IOException {
        try (InputStream is = new FileInputStream(file); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            return baos.toByteArray();
        }
    }
}
