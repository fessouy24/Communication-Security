package SubscriberSide;

import BUEServiceSide.UserManagerI;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.*;
import java.util.*;
import java.util.List;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

public class SecureChatGUI extends JFrame {
    // RMI objects
    private static UserManagerI userManager;
    private String sessionToken;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    // GUI components
    private DefaultListModel<String> contactsModel;
    private JList<String> contactsList;
    private JTextArea chatArea;
    private JTextField inputField;
    private JTextArea encryptedArea;
    private JLabel statusLabel;

    // Data
    private Map<String, List<ChatMessage>> messagesMap = new HashMap<>();
    private String currentContact;
    private String loggedInUser;

    // Callback
    private SecureMessage callback;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Connect to RMI registry
                Registry registry = LocateRegistry.getRegistry("localhost", 1099);
                userManager = (UserManagerI) registry.lookup("UserManager");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Cannot connect to server: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
            new SecureChatGUI();
        });
    }

    public SecureChatGUI() {
        // Show login dialog first
        if (!login()) {
            System.exit(0);
        }

        // After successful login, setup GUI and start
        setupGUI();
        loadChatHistory(); // load saved messages from file
        registerCallback(); // register with server to receive messages
        fetchOfflineMessages(); // get any pending messages from server
        setVisible(true);
    }

    private boolean login() {
        // Simple dialog for username/password
        JPanel panel = new JPanel(new GridLayout(2, 2));
        JTextField usernameField = new JTextField();
        JPasswordField passwordField = new JPasswordField();
        panel.add(new JLabel("Username:"));
        panel.add(usernameField);
        panel.add(new JLabel("Password:"));
        panel.add(passwordField);

        int result = JOptionPane.showConfirmDialog(null, panel, "Login",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return false;

        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        try {
            // Generate RSA keys (new for each run – in a real app you'd store them)
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();
            privateKey = keyPair.getPrivate();
            publicKey = keyPair.getPublic();

            // Try to register (if user exists, registration will fail)
            boolean registered = userManager.register(username, password, publicKey.getEncoded(), new ArrayList<>());
            if (!registered) {
                // Already exists, try login
                sessionToken = userManager.login(username, password);
                if (sessionToken == null) {
                    JOptionPane.showMessageDialog(this, "Invalid username or password",
                            "Login Failed", JOptionPane.ERROR_MESSAGE);
                    return false;
                }
            } else {
                // New user, login after registration
                sessionToken = userManager.login(username, password);
                if (sessionToken == null) {
                    JOptionPane.showMessageDialog(this, "Registration succeeded but login failed!",
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return false;
                }
            }
            loggedInUser = username;
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Login error: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void setupGUI() {
        setTitle("Secure Chat - " + loggedInUser);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(900, 600);
        setLayout(new BorderLayout());

        // Left panel: contacts
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Contacts"));
        contactsModel = new DefaultListModel<>();
        contactsList = new JList<>(contactsModel);
        contactsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        contactsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = contactsList.getSelectedValue();
                if (selected != null) {
                    currentContact = selected;
                    displayChatHistory();
                }
            }
        });
        JScrollPane contactScroll = new JScrollPane(contactsList);
        leftPanel.add(contactScroll, BorderLayout.CENTER);

        JButton addContactBtn = new JButton("Add Contact");
        addContactBtn.addActionListener(e -> addContact());
        leftPanel.add(addContactBtn, BorderLayout.SOUTH);

        // Right panel: chat area
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("Chat"));

        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane chatScroll = new JScrollPane(chatArea);
        rightPanel.add(chatScroll, BorderLayout.CENTER);

        // Bottom panel: input and encrypted area
        JPanel bottomPanel = new JPanel(new BorderLayout());

        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        JButton sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendBtn, BorderLayout.EAST);

        // Encrypted area (for debugging)
        encryptedArea = new JTextArea(3, 40);
        encryptedArea.setEditable(false);
        encryptedArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
        JScrollPane encryptedScroll = new JScrollPane(encryptedArea);
        encryptedScroll.setBorder(BorderFactory.createTitledBorder("Last Encrypted Payload (hex)"));

        bottomPanel.add(inputPanel, BorderLayout.NORTH);
        bottomPanel.add(encryptedScroll, BorderLayout.SOUTH);

        rightPanel.add(bottomPanel, BorderLayout.SOUTH);

        // Status bar
        statusLabel = new JLabel("Ready");
        add(statusLabel, BorderLayout.SOUTH);

        // Split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setDividerLocation(250);
        add(splitPane, BorderLayout.CENTER);

        // Add window listener to save messages on exit
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveChatHistory();
                try {
                    if (callback != null) userManager.unregisterCallback(sessionToken);
                    userManager.logout(sessionToken);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                System.exit(0);
            }
        });
    }

    private void addContact() {
        String contact = JOptionPane.showInputDialog(this, "Enter username to add:",
                "Add Contact", JOptionPane.PLAIN_MESSAGE);
        if (contact == null || contact.trim().isEmpty()) return;
        contact = contact.trim();
        if (contact.equals(loggedInUser)) {
            JOptionPane.showMessageDialog(this, "You cannot add yourself as a contact",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (!userManager.userExists(contact)) {
                JOptionPane.showMessageDialog(this, "User does not exist",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Add to contacts model if not already present
            if (!contactsModel.contains(contact)) {
                contactsModel.addElement(contact);
                // Ensure we have a message list for this contact
                messagesMap.putIfAbsent(contact, new ArrayList<>());
            } else {
                JOptionPane.showMessageDialog(this, "Contact already exists");
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Error checking user: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void displayChatHistory() {
        if (currentContact == null) return;
        List<ChatMessage> messages = messagesMap.get(currentContact);
        if (messages == null) {
            messages = new ArrayList<>();
            messagesMap.put(currentContact, messages);
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            sb.append(msg.toString()).append("\n");
        }
        chatArea.setText(sb.toString());
        chatArea.setCaretPosition(chatArea.getDocument().getLength()); // auto-scroll
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        if (currentContact == null) {
            JOptionPane.showMessageDialog(this, "Select a contact first");
            return;
        }

        try {
            // Get recipient's public key
            byte[] recipientPubKeyBytes = userManager.getUserPublicKey(currentContact, sessionToken);
            if (recipientPubKeyBytes == null) {
                JOptionPane.showMessageDialog(this, "Recipient not found");
                return;
            }
            PublicKey recipientPubKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(recipientPubKeyBytes));

            // Generate AES key
            KeyGenerator aesGen = KeyGenerator.getInstance("AES");
            aesGen.init(256);
            SecretKey aesKey = aesGen.generateKey();

            // Encrypt message with AES
            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = new byte[12];
            SecureRandom random = new SecureRandom();
            random.nextBytes(nonce);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, nonce);
            aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
            byte[] encryptedMessage = aesCipher.doFinal(text.getBytes());

            // Encrypt AES key with RSA
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, recipientPubKey);
            byte[] encryptedAesKey = rsaCipher.doFinal(aesKey.getEncoded());

            // Build payload (concatenate nonce + encryptedAesKey + encryptedMessage)
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

            // Sign payload with private key
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(payload);
            byte[] signature = sig.sign();

            // Send via RMI
            boolean sent = userManager.sendMessage(sessionToken, currentContact, payload, signature);
            if (!sent) {
                JOptionPane.showMessageDialog(this, "Send failed");
                return;
            }

            // Show encrypted data in hex
            encryptedArea.setText(bytesToHex(payload));

            // Add message to local history
            ChatMessage msg = new ChatMessage(loggedInUser, text, true);
            messagesMap.computeIfAbsent(currentContact, k -> new ArrayList<>()).add(msg);
            displayChatHistory();

            inputField.setText("");
            statusLabel.setText("Message sent");

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Send error: " + e.getMessage());
        }
    }

    private void registerCallback() {
        try {
            callback = new SecureMessage(privateKey, (sender, plaintext) -> {
                // This lambda runs on the RMI callback thread – we must update GUI on EDT
                SwingUtilities.invokeLater(() -> {
                    // Add message to the appropriate contact's history
                    List<ChatMessage> msgs = messagesMap.computeIfAbsent(sender, k -> new ArrayList<>());
                    msgs.add(new ChatMessage(sender, plaintext, false));

                    // If the currently selected contact is this sender, refresh the chat display
                    if (sender.equals(currentContact)) {
                        displayChatHistory();
                    } else {
                        // Optionally show a notification
                        statusLabel.setText("New message from " + sender);
                        // You could also highlight the contact in the list
                    }
                    statusLabel.setText("Message received from " + sender);
                });
            });
            userManager.registerCallback(sessionToken, callback);
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to register callback: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void onMessageReceived(String plaintext) {
        // This is called from the callback thread (RMI). Need to update GUI.
        SwingUtilities.invokeLater(() -> {
            // Determine the sender: the message was sent to us, so we need to know who sent it.
            // Our SecureMessage.receiveMessage has the sender? Actually we don't have the sender info.
            // We need to modify the callback to include sender. Let's fix: In receiveMessage, we have the payload,
            // but we don't know the sender. However, the server doesn't send the sender in the payload.
            // We'll need to include the sender's username in the payload. Let's modify: When the server sends the message,
            // it should include the sender's username in the payload. We'll change the message format.
            // For now, we'll assume we can store the sender in the payload.
            // Let's redesign: The server should send a wrapper that includes sender and the encrypted data.
            // But that's more complex. For simplicity, we'll pass the sender as a separate parameter in the callback? But RMI interface can't be changed easily.
            // Alternative: modify SecureMessage.receiveMessage to accept an additional parameter (String sender). We'll change the interface and implementation.
            // We'll do that.
        });
    }

    private void fetchOfflineMessages() {
        try {
            List<byte[]> msgs = userManager.fetchMessages(sessionToken);
            for (byte[] wrapped : msgs) {
                // Parse the sender from the wrapped payload (the same format used in sendMessage)
                ByteArrayInputStream bais = new ByteArrayInputStream(wrapped);
                DataInputStream dis = new DataInputStream(bais);
                String sender = dis.readUTF();
                callback.receiveMessage(sender, wrapped);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveChatHistory() {
        // Save messagesMap to a file named "messages_<username>.dat"
        String filename = "messages_" + loggedInUser + ".ser";
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(filename))) {
            oos.writeObject(messagesMap);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void loadChatHistory() {
        String filename = "messages_" + loggedInUser + ".ser";
        File f = new File(filename);
        if (f.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(filename))) {
                messagesMap = (Map<String, List<ChatMessage>>) ois.readObject();
                // Populate contacts model
                for (String contact : messagesMap.keySet()) {
                    if (!contact.equals(loggedInUser)) {
                        contactsModel.addElement(contact);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}