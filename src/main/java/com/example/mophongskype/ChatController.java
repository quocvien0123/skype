package com.example.mophongskype;

import com.example.mophongskype.client.ChatClient;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javafx.application.HostServices;

import static java.lang.System.out;


public class ChatController {
    @FXML
    private Text titleText;

    @FXML
    private Label statusLabel;

    @FXML
    private Button logoutButton;

    @FXML
    private ListView<String> userListView;

    @FXML
    private Button removeUserButton;

    @FXML
    private ScrollPane chatScrollPane;

    @FXML
    private VBox chatContainer;

    @FXML
    private TextField messageField;

    @FXML
    private Button sendButton;

    @FXML
    private ComboBox<String> privateUserCombo;

    @FXML
    private TextField privateMessageField;

    @FXML
    private Button sendPrivateButton;

    @FXML
    private Button scrollToBottomButton;

    @FXML
    private TextField roomIdField;

    private ChatClient chatClient;
    private Stage primaryStage;
    private boolean isOfflineMode = false;
    private String currentUsername;
    private ObservableList<String> onlineUsers = FXCollections.observableArrayList();
    private List<String> offlineMessages = new ArrayList<>();

    private HostServices hostServices;

    public void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    public void initialize() {
        userListView.setItems(onlineUsers);
        privateUserCombo.setItems(onlineUsers);

        // Thiết lập selection model cho ListView
        userListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // Thiết lập placeholder cho ComboBox
        privateUserCombo.setPlaceholder(new Label("Chọn người dùng"));

        // Thiết lập scroll behavior
        setupScrollBehavior();
    }

    private void setupScrollBehavior() {
        // Thiết lập scroll wheel behavior
        chatScrollPane.setOnScroll(event -> {
            double deltaY = event.getDeltaY();
            double vValue = chatScrollPane.getVvalue();

            // Scroll mượt mà với wheel
            if (deltaY < 0) {
                chatScrollPane.setVvalue(vValue + 0.1);
            } else {
                chatScrollPane.setVvalue(vValue - 0.1);
            }
        });

        // Thiết lập keyboard shortcuts
        chatScrollPane.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case HOME:
                    chatScrollPane.setVvalue(0.0);
                    break;
                case END:
                    scrollToBottom();
                    break;
                case PAGE_UP:
                    chatScrollPane.setVvalue(chatScrollPane.getVvalue() - 0.3);
                    break;
                case PAGE_DOWN:
                    chatScrollPane.setVvalue(chatScrollPane.getVvalue() + 0.3);
                    break;
            }
        });

        // Focus vào ScrollPane để có thể sử dụng keyboard
        chatScrollPane.setFocusTraversable(true);
    }

    public void setChatClient(ChatClient client) {
        this.chatClient = client;
        this.currentUsername = client.getUsername();
        setupClientCallbacks();
    }

    public void setOfflineMode(String username) {
        this.isOfflineMode = true;
        this.currentUsername = username;
        this.statusLabel.setText("Offline");
        this.titleText.setText("Skype - " + username + " (Offline)");

        // Vô hiệu hóa các chức năng cần server
        removeUserButton.setDisable(true);
        sendPrivateButton.setDisable(true);
        privateUserCombo.setDisable(true);
        privateMessageField.setDisable(true);
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
    }

    private void setupClientCallbacks() {
        if (chatClient == null) return;

        chatClient.setOnFileReceived(file -> {
            Platform.runLater(() -> {
                boolean isImage = file.getName().toLowerCase().matches(".*\\.(png|jpg|jpeg|gif)$");
                showFileMessage(file, isImage);  // dùng luôn hàm hiện file
            });
        });



        chatClient.setOnMessageReceived(message -> {
            Platform.runLater(() -> {
                if (message.startsWith("FILE_SAVED:")) {
                    String[] parts = message.substring("FILE_SAVED:".length()).split(":", 2);
                    File file = new File(parts[0]);
                    boolean isImage = parts.length > 1 && parts[1].equals("IMAGE");
                    showFileMessage(file, isImage);
                } else {
                    String[] parts = message.split(":", 2);
                    if (parts.length == 2) {
                        addMessageToChat(parts[0], parts[1], false);
                    }
                }
            });
        });




        chatClient.setOnPrivateMessageReceived(message -> {
            Platform.runLater(() -> {
                String[] parts = message.split(":", 2);
                if (parts.length == 2) {
                    addMessageToChat(parts[0], parts[1], true);
                }
            });
        });

        chatClient.setOnUserListReceived(userList -> {
            Platform.runLater(() -> {
                String[] users = userList.split(",");
                onlineUsers.clear();
                for (String user : users) {
                    if (!user.isEmpty() && !user.equals(currentUsername)) {
                        onlineUsers.add(user);
                    }
                }
            });
        });

        chatClient.setOnLogoutResult(result -> {
            Platform.runLater(() -> {
                if (result.equals("SUCCESS")) {
                    returnToLogin();
                }
            });
        });

        chatClient.setOnRemoved(message -> {
            Platform.runLater(() -> {
                addSystemMessage(message);
                // Sau 3 giây tự động đăng xuất
                new Thread(() -> {
                    try {
                        Thread.sleep(3000);
                        Platform.runLater(this::returnToLogin);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            });
        });

        chatClient.setOnFileReceived(file -> {
            Platform.runLater(() -> {
                boolean isImage = file.getName().toLowerCase().matches(".*\\.(png|jpg|jpeg|gif)$");
                showFileMessage(file, isImage);
            });
        });


    }

    @FXML
    private void handleSendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) return;

        if (isOfflineMode) {
            // Chế độ offline - chỉ lưu tin nhắn local
            addMessageToChat(currentUsername, message, false);
            offlineMessages.add(currentUsername + ": " + message);
        } else if (chatClient != null && chatClient.isConnected()) {
            chatClient.sendMessage(message);
            addMessageToChat(currentUsername, message, false);
        }

        messageField.clear();
    }
    @FXML
    private void handleJoinRoom() {
        String roomId = roomIdField.getText().trim();
        if (roomId.isEmpty()) {
            statusLabel.setText("Vui lòng nhập ID phòng!");
            return;
        }

        if (chatClient != null && chatClient.isConnected()) {
            chatClient.joinRoom(roomId);
            statusLabel.setText("Đã tham gia phòng: " + roomId);
        } else {
            statusLabel.setText("Chưa kết nối tới server!");
        }
    }

    private void showFileMessage(File file, boolean isImage) {
        VBox fileBox = new VBox();
        fileBox.setSpacing(5);
        fileBox.setStyle("-fx-background-color: #e1f5fe; -fx-background-radius: 8; -fx-padding: 8;");

        Label fileLabel = new Label("File: " + file.getName());
        fileBox.getChildren().add(fileLabel);

        if (isImage && file.exists()) {
            javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(file.toURI().toString(), 200, 0, true, true)
            );
            imageView.setPreserveRatio(true);
            fileBox.getChildren().add(imageView);
        } else {
            Hyperlink link = new Hyperlink("Tải xuống: " + file.getName());
            link.setOnAction(e -> hostServices.showDocument(file.toURI().toString()));
            fileBox.getChildren().add(link);
        }

        chatContainer.getChildren().add(fileBox);
        scrollToBottom();
    }


    @FXML
    private void handleSendPrivateMessage() {
        String selectedUser = privateUserCombo.getSelectionModel().getSelectedItem();
        String message = privateMessageField.getText().trim();

        if (selectedUser == null || message.isEmpty()) {
            return;
        }

        if (chatClient != null && chatClient.isConnected()) {
            chatClient.sendPrivateMessage(selectedUser, message);
            addMessageToChat(currentUsername + " → " + selectedUser, message, true);
        }

        privateMessageField.clear();
    }

    @FXML
    private void handleRemoveUser() {
        String selectedUser = userListView.getSelectionModel().getSelectedItem();
        if (selectedUser == null) {
            showAlert("Vui lòng chọn người dùng cần xóa");
            return;
        }

        if (chatClient != null && chatClient.isConnected()) {
            chatClient.removeUser(selectedUser);
        }
    }

    @FXML
    private void handleLogout() {
        if (isOfflineMode) {
            returnToLogin();
        } else if (chatClient != null) {
            chatClient.logout();
        }
    }

    @FXML
    private void handleScrollToBottom() {
        scrollToBottom();
    }

    private boolean isImageFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".gif");
    }


    private void addMessageToChat(String sender, String message, boolean isPrivate) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        // Tạo container cho tin nhắn với style đẹp hơn
        VBox messageBox = new VBox();
        messageBox.setSpacing(2);
        messageBox.setStyle("-fx-background-color: white; -fx-background-radius: 8; -fx-padding: 8; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 2, 0, 0, 1);");

        // Tạo header với timestamp và sender
        HBox headerBox = new HBox();
        headerBox.setSpacing(5);

        Text timeText = new Text("[" + timestamp + "]");
        timeText.setStyle("-fx-fill: #999; -fx-font-size: 10px;");

        Text senderText = new Text(sender);
        if (isPrivate) {
            senderText.setStyle("-fx-fill: #107c10; -fx-font-weight: bold; -fx-font-size: 12px;");
        } else {
            senderText.setStyle("-fx-fill: #0078d4; -fx-font-weight: bold; -fx-font-size: 12px;");
        }

        headerBox.getChildren().addAll(timeText, senderText);

        // Tạo nội dung tin nhắn
        Text messageText = new Text(message);
        messageText.setStyle("-fx-fill: #333; -fx-font-size: 13px;");
        messageText.setWrappingWidth(chatScrollPane.getWidth() - 50); // Wrap text theo chiều rộng

        messageBox.getChildren().addAll(headerBox, messageText);

        // Thêm vào container
        chatContainer.getChildren().add(messageBox);

        // Scroll mượt mà xuống cuối
        Platform.runLater(() -> {
            scrollToBottom();
        });
    }
    @FXML
    private void handleChooseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Chọn file để gửi");
        File file = fileChooser.showOpenDialog(primaryStage);

        if (file != null) {
            if (chatClient != null && chatClient.isConnected()) {
                chatClient.sendFile(file);

                // Hiển thị ngay trong chat
                addFileMessageToChat(currentUsername, file);
            } else {
                showAlert("Lỗi: Chưa kết nối server");
            }
        }
    }

    private void addFileMessageToChat(String sender, File file) {
        VBox fileBox = new VBox();
        fileBox.setSpacing(5);
        fileBox.setStyle("-fx-background-color: #e1f5fe; -fx-background-radius: 8; -fx-padding: 8;");

        Label senderLabel = new Label(sender);
        senderLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #0078d4;");

        Label fileLabel = new Label("File: " + file.getName());

        fileBox.getChildren().add(senderLabel);

        if (isImageFile(file.getName())) {
            out.println("IMAGE_FILE:" + file.getName() + ":" + file.length());
            javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(
                    new javafx.scene.image.Image(file.toURI().toString(), 200, 0, true, true)
            );
            imageView.setPreserveRatio(true);
            fileBox.getChildren().addAll(fileLabel, imageView);
        } else {
            out.println("FILE:" + file.getName() + ":" + file.length());
            Hyperlink link = new Hyperlink("Tải xuống: " + file.getName());
            link.setOnAction(e -> {
                if (hostServices != null) {
                    hostServices.showDocument(file.toURI().toString());
                }
            });

            fileBox.getChildren().addAll(fileLabel, link);
        }

        chatContainer.getChildren().add(fileBox);
        scrollToBottom();
    }



    private void scrollToBottom() {
        // Scroll mượt mà xuống cuối
        chatScrollPane.setVvalue(1.0);

        // Đảm bảo scroll hoàn toàn xuống cuối
        chatScrollPane.requestLayout();
        chatScrollPane.layout();
        chatScrollPane.setVvalue(1.0);
    }

    private void addSystemMessage(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        // Tạo container cho tin nhắn hệ thống
        VBox messageBox = new VBox();
        messageBox.setSpacing(2);
        messageBox.setStyle("-fx-background-color: #fff3cd; -fx-background-radius: 8; -fx-padding: 8; -fx-border-color: #ffeaa7; -fx-border-radius: 8; -fx-border-width: 1;");

        // Tạo header với timestamp
        HBox headerBox = new HBox();
        headerBox.setSpacing(5);

        Text timeText = new Text("[" + timestamp + "]");
        timeText.setStyle("-fx-fill: #856404; -fx-font-size: 10px;");

        Text systemText = new Text("SYSTEM");
        systemText.setStyle("-fx-fill: #d13438; -fx-font-weight: bold; -fx-font-size: 12px;");

        headerBox.getChildren().addAll(timeText, systemText);

        // Tạo nội dung tin nhắn
        Text messageText = new Text(message);
        messageText.setStyle("-fx-fill: #856404; -fx-font-size: 13px; -fx-font-style: italic;");
        messageText.setWrappingWidth(chatScrollPane.getWidth() - 50);

        messageBox.getChildren().addAll(headerBox, messageText);

        // Thêm vào container
        chatContainer.getChildren().add(messageBox);

        // Scroll xuống cuối
        Platform.runLater(() -> {
            scrollToBottom();
        });
    }

    private void returnToLogin() {
        try {
            if (chatClient != null) {
                chatClient.disconnect();
            }

            FXMLLoader loader = new FXMLLoader(getClass().getResource("login-view.fxml"));
            Scene loginScene = new Scene(loader.load(), 400, 300);

            LoginController loginController = loader.getController();
            loginController.setPrimaryStage(primaryStage);


            primaryStage.setTitle("Skype - Đăng nhập");
            primaryStage.setScene(loginScene);
            primaryStage.setResizable(false);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Thông báo");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
