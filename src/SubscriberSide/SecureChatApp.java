package SubscriberSide;

import BUEServiceSide.UserManagerI;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.*;
import java.util.*;

public class SecureChatApp {

    private static UserManagerI userManager;
    private static String sessionToken;
    private static PrivateKey privateKey;
    private static PublicKey publicKey;

    public static void main(String[] args) {
        try {
            // Connect to RMI registry
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            userManager = (UserManagerI) registry.lookup("UserManager");

            Scanner scanner = new Scanner(System.in);
            System.out.print("Username: ");
            String username = scanner.nextLine();
            System.out.print("Password: ");
            String password = scanner.nextLine();

            // Generate RSA key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();

            // Try to register (if user already exists, registration fails)
            boolean registered = userManager.register(username, password, publicKey.getEncoded(), new ArrayList<>());
            if (!registered) {
                System.out.println("User exists, logging in...");
            }

            // Login
            sessionToken = userManager.login(username, password);
            if (sessionToken == null) {
                System.out.println("Login failed.");
                return;
            }
            System.out.println("Logged in. Session token: " + sessionToken);

            // Register callback for incoming messages
            SecureMessage callback = new SecureMessage(privateKey, (sender, message) -> {
                System.out.println("\n[Message from " + sender + "]: " + message);
                System.out.print("Enter recipient username (or 'quit'): ");
            });
            userManager.registerCallback(sessionToken, callback);

            // Flush any offline messages that were stored on the server
            List<byte[]> offlineMessages = userManager.fetchMessages(sessionToken);
            for (byte[] wrapped : offlineMessages) {
                // Parse the sender from the wrapped payload (the server stored it as UTF string + payload)
                ByteArrayInputStream bais = new ByteArrayInputStream(wrapped);
                DataInputStream dis = new DataInputStream(bais);
                String sender = dis.readUTF();
                callback.receiveMessage(sender, wrapped);
            }

            // Main loop: send messages
            while (true) {
                System.out.print("Enter recipient username (or 'quit'): ");
                String recipient = scanner.nextLine();
                if ("quit".equalsIgnoreCase(recipient)) break;

                System.out.print("Message: ");
                String messageText = scanner.nextLine();

                try {
                    // Get recipient's public key
                    byte[] recipientPubKeyBytes = userManager.getUserPublicKey(recipient, sessionToken);
                    if (recipientPubKeyBytes == null) {
                        System.out.println("Recipient not found.");
                        continue;
                    }
                    PublicKey recipientPubKey = KeyFactory.getInstance("RSA")
                            .generatePublic(new java.security.spec.X509EncodedKeySpec(recipientPubKeyBytes));

                    // Generate AES key
                    KeyGenerator aesGen = KeyGenerator.getInstance("AES");
                    aesGen.init(256);
                    SecretKey aesKey = aesGen.generateKey();

                    // Encrypt message with AES-GCM
                    Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
                    byte[] nonce = new byte[12];
                    new SecureRandom().nextBytes(nonce);
                    GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
                    aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
                    byte[] encryptedMessage = aesCipher.doFinal(messageText.getBytes());

                    // Encrypt AES key with RSA
                    Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    rsaCipher.init(Cipher.ENCRYPT_MODE, recipientPubKey);
                    byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

                    // Build payload: [nonceLen (int)] + [nonce] + [encAesKeyLen (int)] + [encAesKey] + [encMsgLen (int)] + [encMsg]
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

                    // Sign the payload with sender's private key
                    Signature sig = Signature.getInstance("SHA256withRSA");
                    sig.initSign(privateKey);
                    sig.update(payload);
                    byte[] signature = sig.sign();

                    // Send via RMI
                    boolean sent = userManager.sendMessage(sessionToken, recipient, payload, signature);
                    if (sent) {
                        System.out.println("Message sent.");
                    } else {
                        System.out.println("Failed to send.");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println("Error sending message: " + e.getMessage());
                }
            }

            // Cleanup
            userManager.unregisterCallback(sessionToken);
            userManager.logout(sessionToken);
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}