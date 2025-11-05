package com.example.mophongskype.server;

import java.io.*;
import java.net.*;

public class ClientHandler implements Runnable {
    private Socket clientSocket;
    private ChatServer server;
    private PrintWriter out;
    private BufferedReader in;
    private String username;

    public ClientHandler(Socket socket, ChatServer server) {
        this.clientSocket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            // PrintWriter với autoFlush = true để luôn đẩy buffer ra
            out = new PrintWriter(clientSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

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
                            // Broadcast cho tất cả client khác (trừ người gửi)
                            server.broadcastNewFile(username, fileName);
                            // Broadcast tin nhắn thông báo
                            server.broadcastMessage("SYSTEM", username + " đã gửi " + mediaType + ": " + fileName);
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
     * Lưu ý: Phải flush PrintWriter trước khi dùng DataOutputStream
     */
    private void sendFileToClient(String fileName) {
        try {
            File file = new File("uploads", fileName); // file trên server
            if (!file.exists()) {
                sendMessage("FILE_NOT_FOUND:" + fileName);
                return;
            }

            long fileSize = file.length();
            
            // QUAN TRỌNG: Flush PrintWriter trước khi dùng DataOutputStream
            // để đảm bảo header message được gửi trước
            if (out != null) {
                out.flush();
            }
            
            // Gửi header trước qua PrintWriter
            sendMessage("FILE_DATA:" + file.getName() + ":" + fileSize);
            
            // Đợi một chút để đảm bảo message được gửi
            Thread.sleep(50);
            
            // Gửi dữ liệu file qua DataOutputStream
            DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[4096];
                int read;
                long totalSent = 0;
                while ((read = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, read);
                    totalSent += read;
                }
                dos.flush();
                System.out.println("✅ Đã gửi file " + fileName + " (" + totalSent + " bytes) đến " + username);
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            sendMessage("FILE_FAILED:" + fileName);
        }
    }


    /**
     * Nhận file từ client và lưu vào thư mục uploads trên server
     * @return true nếu nhận thành công, false nếu có lỗi
     */
    private boolean receiveFile(String fileName, long fileSize) {
        try {
            File uploadsDir = new File("uploads");
            if (!uploadsDir.exists()) {
                uploadsDir.mkdirs();
            }

            File file = new File(uploadsDir, fileName);
            DataInputStream dataIn = new DataInputStream(clientSocket.getInputStream());
            try (FileOutputStream fos = new FileOutputStream(file)) {
                byte[] buffer = new byte[4096];
                long totalRead = 0;
                int read;
                while (totalRead < fileSize && (read = dataIn.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                    fos.write(buffer, 0, read);
                    totalRead += read;
                }
                
                // Kiểm tra xem đã nhận đủ dữ liệu chưa
                if (totalRead != fileSize) {
                    System.err.println("Cảnh báo: Chỉ nhận được " + totalRead + "/" + fileSize + " bytes cho file " + fileName);
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
}
