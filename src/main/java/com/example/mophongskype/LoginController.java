package com.example.mophongskype;

import com.example.mophongskype.client.ChatClient;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class LoginController {
    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Button loginButton;

    @FXML
    private Button registerButton;

    @FXML
    private Label statusLabel;

    private ChatClient chatClient;
    private Stage primaryStage;
    private HostServices hostServices;

    // Lưu tài khoản mẫu (chỉ cho demo)
    private static Map<String, String> registeredUsers = new HashMap<>();

    public void initialize() {
        chatClient = new ChatClient();
        setupClientCallbacks();

        // Thêm user mẫu
        registeredUsers.put("admin", "123456");
        registeredUsers.put("user1", "password");
        registeredUsers.put("test", "test");
        registeredUsers.put("vien","vien");
        registeredUsers.put("tuna","tuna");

        setupKeyboardShortcuts();
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }
    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    private void setupClientCallbacks() {
        chatClient.setOnLoginResult(result -> {
            Platform.runLater(() -> {
                if (result.equals("SUCCESS")) {
                    openChatWindow();
                } else {
                    statusLabel.setText("Đăng nhập thất bại: " + result);
                    resetButtons();
                }
            });
        });
    }

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty()) {
            statusLabel.setText("Vui lòng nhập tên người dùng");
            return;
        }
        if (password.isEmpty()) {
            statusLabel.setText("Vui lòng nhập mật khẩu");
            return;
        }

        statusLabel.setText("Đang kết nối...");
        loginButton.setDisable(true);
        registerButton.setDisable(true);

        // Kết nối server
        if (chatClient.connect()) {
            chatClient.login(username, password);
        } else {
            statusLabel.setText("Không thể kết nối server");
            resetButtons();
        }
    }

    @FXML
    private void handleRegister() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Vui lòng nhập đủ thông tin");
            return;
        }
        if (registeredUsers.containsKey(username)) {
            statusLabel.setText("Tài khoản đã tồn tại");
            return;
        }

        registeredUsers.put(username, password);
        statusLabel.setText("Đăng ký thành công! Bạn có thể đăng nhập.");
        passwordField.clear();
        usernameField.requestFocus();
    }

    private void resetButtons() {
        loginButton.setDisable(false);
        registerButton.setDisable(false);
    }

    private void openChatWindow() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("chat-view.fxml"));
            Scene chatScene = new Scene(loader.load(), 800, 600);

            ChatController chatController = loader.getController();
            chatController.setChatClient(chatClient);
            chatController.setPrimaryStage(primaryStage);

            primaryStage.setTitle("Skype - " + chatClient.getUsername());
            primaryStage.setScene(chatScene);
            primaryStage.setResizable(true);
        } catch (IOException e) {
            statusLabel.setText("Lỗi mở cửa sổ chat: " + e.getMessage());
            resetButtons();
        }
    }

    private void setupKeyboardShortcuts() {
        usernameField.setOnAction(event -> passwordField.requestFocus());
        passwordField.setOnAction(event -> handleLogin());
    }
}
