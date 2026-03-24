package SubscriberSide;

import BUEServiceSide.UserManagerI;
import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;

public class SecureChatFX extends Application {

    private UserManagerI userManager;
    private String sessionToken;
    private PrivateKey privateKey;
    private PublicKey publicKey;
    private WebEngine webEngine;
    private SecureMessage callback;

    @Override
    public void start(Stage primaryStage) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            userManager = (UserManagerI) registry.lookup("UserManager");
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }

        WebView webView = new WebView();
        webEngine = webView.getEngine();
        String url = getClass().getResource("/ui/index.html").toExternalForm();
        webEngine.load(url);

        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                JSObject window = (JSObject) webEngine.executeScript("window");
                window.setMember("java", new JavaBridge());
            }
        });

        Scene scene = new Scene(webView, 1000, 700);
        primaryStage.setTitle("Secure Chat");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public class JavaBridge {
        public void requestOtp(String username, String email, String password) {
            try {
                if (!userManager.userExistsByUsername(username)) {
                    // Stripped unused empty array list
                    boolean registered = userManager.register(username, email, password, publicKey.getEncoded());
                    if (!registered) {
                        callJS("otpRequestFailed", "Registration failed (username or email already exists).");
                        return;
                    }
                }
                boolean success = userManager.requestOtp(username, password);
                if (success) {
                    callJS("otpRequestSuccess");
                } else {
                    callJS("otpRequestFailed", "Invalid username or password.");
                }
            } catch (Exception e) {
                e.printStackTrace();
                callJS("otpRequestFailed", "Server error: " + e.getMessage());
            }
        }

        public void verifyOtp(String username, String otp) {
            try {
                sessionToken = userManager.loginWithOtp(username, otp);
                if (sessionToken == null) {
                    callJS("otpVerificationFailed", "Invalid or expired OTP.");
                    return;
                }

                callback = new SecureMessage(privateKey, (sender, message) -> {
                    javafx.application.Platform.runLater(() -> {
                        callJS("addMessage", sender, message, false);
                    });
                });
                userManager.registerCallback(sessionToken, callback);

                callJS("loginSuccess", username);
            } catch (Exception e) {
                e.printStackTrace();
                callJS("otpVerificationFailed", "Error: " + e.getMessage());
            }
        }

        public boolean sendMessage(String recipient, String plainMessage) {
            try {
                if (sessionToken == null) {
                    callJS("showError", "Not logged in.");
                    return false;
                }

                byte[] recipientPubKeyBytes = userManager.getUserPublicKey(recipient, sessionToken);
                if (recipientPubKeyBytes == null) {
                    callJS("showError", "Recipient not found or request rate-limited.");
                    return false;
                }
                PublicKey recipientPubKey = KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(recipientPubKeyBytes));

                KeyGenerator aesGen = KeyGenerator.getInstance("AES");
                aesGen.init(256);
                SecretKey aesKey = aesGen.generateKey();

                Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
                byte[] nonce = new byte[12];
                new SecureRandom().nextBytes(nonce);
                GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
                aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
                byte[] encryptedMessage = aesCipher.doFinal(plainMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8));

                // Upgraded to OAEP for better security [cite: 568, 660, 1339]
                Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
                rsaCipher.init(Cipher.ENCRYPT_MODE, recipientPubKey);
                byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeInt(nonce.length);
                dos.write(nonce);
                dos.writeInt(encryptedAesKey.length);
                dos.write(encryptedAesKey);
                dos.writeInt(encryptedMessage.length);
                dos.write(encryptedMessage);
                dos.flush();
                byte[] payload = baos.toByteArray();

                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(privateKey);
                sig.update(payload);
                byte[] signature = sig.sign();

                boolean sent = userManager.sendMessage(sessionToken, recipient, payload, signature);
                if (!sent) {
                    callJS("showError", "Failed to send message.");
                }
                return sent;
            } catch (Exception e) {
                e.printStackTrace();
                callJS("showError", "Error sending message: " + e.getMessage());
                return false;
            }
        }

        public boolean userExists(String username) {
            try {
                return userManager.userExistsByUsername(username);
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
        }

        public void logout() {
            try {
                if (sessionToken != null) {
                    userManager.unregisterCallback(sessionToken);
                    userManager.logout(sessionToken);
                    sessionToken = null;
                }
                callJS("logout");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void callJS(String function, Object... args) {
            javafx.application.Platform.runLater(() -> {
                StringBuilder sb = new StringBuilder(function).append("(");
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(",");
                    if (args[i] instanceof String) {
                        sb.append("'").append(args[i].toString().replace("'", "\\'")).append("'");
                    } else if (args[i] instanceof Boolean) {
                        sb.append(args[i]);
                    } else {
                        sb.append(args[i]);
                    }
                }
                sb.append(");");
                webEngine.executeScript(sb.toString());
            });
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}