package BUEServiceSide;

import SubscriberSide.SecureMessageI;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class UserManagerImpl extends UnicastRemoteObject implements UserManagerI {
    // In‑memory stores
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToUser = new ConcurrentHashMap<>();
    private final Map<String, SecureMessageI> callbacks = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> rateLimiters = new ConcurrentHashMap<>();

    // Rate limit: 10 requests per minute per user
    private static final int RATE_LIMIT = 10;
    private static final long RATE_WINDOW_MS = 60_000;

    public UserManagerImpl() throws RemoteException {
        super();
    }

    private static class User {
        String username;
        byte[] passwordHash;
        byte[] salt;
        byte[] publicKeyEncoded;
        Queue<byte[]> oneTimePrekeys; // encoded public keys
        Queue<byte[]> offlineMessages; // each element is a serialized message payload
        long lastSeen;
        // for simplicity, we store the encoded keys as byte[]
    }

    private static class TokenBucket {
        int tokens;
        long lastRefill;
    }

    private synchronized boolean checkRateLimit(String username) {
        TokenBucket bucket = rateLimiters.computeIfAbsent(username, k -> {
            TokenBucket b = new TokenBucket();
            b.tokens = RATE_LIMIT;
            b.lastRefill = System.currentTimeMillis();
            return b;
        });
        long now = System.currentTimeMillis();
        long elapsed = now - bucket.lastRefill;
        if (elapsed >= RATE_WINDOW_MS) {
            bucket.tokens = RATE_LIMIT;
            bucket.lastRefill = now;
        }
        if (bucket.tokens > 0) {
            bucket.tokens--;
            return true;
        }
        return false;
    }

    private byte[] hashPassword(String password, byte[] salt) {
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 10000, 256);
            byte[] hash = skf.generateSecret(spec).getEncoded();
            return hash;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean register(String username, String passwordHash, byte[] publicKeyEncoded, List<byte[]> oneTimePrekeyEncoded) throws RemoteException {
        if (users.containsKey(username)) return false;
        if (!checkRateLimit(username)) return false;

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hashPassword(passwordHash, salt); // note: passwordHash is actually the plain password for demo
        User u = new User();
        u.username = username;
        u.passwordHash = hash;
        u.salt = salt;
        u.publicKeyEncoded = publicKeyEncoded;
        u.oneTimePrekeys = new ConcurrentLinkedQueue<>(oneTimePrekeyEncoded);
        u.offlineMessages = new ConcurrentLinkedQueue<>();
        users.put(username, u);
        return true;
    }

    @Override
    public String login(String username, String passwordHash) throws RemoteException {
        User u = users.get(username);
        if (u == null) return null;
        if (!checkRateLimit(username)) return null;
        byte[] hash = hashPassword(passwordHash, u.salt);
        if (!Arrays.equals(hash, u.passwordHash)) return null;
        String token = UUID.randomUUID().toString();
        sessionToUser.put(token, username);
        u.lastSeen = System.currentTimeMillis();
        return token;
    }

    @Override
    public void logout(String sessionToken) throws RemoteException {
        String username = sessionToUser.remove(sessionToken);
        if (username != null) {
            callbacks.remove(sessionToken);
        }
    }

    @Override
    public byte[] getUserPublicKey(String username, String sessionToken) throws RemoteException {
        if (!sessionToUser.containsKey(sessionToken)) return null;
        if (!checkRateLimit(sessionToUser.get(sessionToken))) return null;
        User u = users.get(username);
        if (u == null) return null;
        return u.publicKeyEncoded;
    }

    @Override
    public byte[] getOneTimePrekey(String username, String sessionToken) throws RemoteException {
        return null;
    }
    @Override
    public boolean userExists(String username) throws RemoteException {
        return users.containsKey(username);
    }
    @Override
    public boolean sendMessage(String sessionToken, String recipient, byte[] payload, byte[] signature) throws RemoteException {
        // 1. Validate session token
        String sender = sessionToUser.get(sessionToken);
        if (sender == null) return false;

        // 2. Rate limiting
        if (!checkRateLimit(sender)) return false;

        // 3. Retrieve sender's user record
        User senderUser = users.get(sender);
        if (senderUser == null) return false;

        // 4. Verify signature using sender's public key
        try {
            PublicKey pubKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(senderUser.publicKeyEncoded));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pubKey);
            sig.update(payload);
            if (!sig.verify(signature)) {
                return false; // signature invalid
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

        // 5. Look up recipient
        User recipientUser = users.get(recipient);
        if (recipientUser == null) return false;

        // 6. Wrap the original payload with the sender's username
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeUTF(sender);
            dos.writeInt(payload.length);
            dos.write(payload);
            dos.flush();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        byte[] wrappedPayload = baos.toByteArray();

        // 7. Check if recipient is online (has a registered callback)
        String recipientSession = null;
        for (Map.Entry<String, String> entry : sessionToUser.entrySet()) {
            if (entry.getValue().equals(recipient)) {
                recipientSession = entry.getKey();
                break;
            }
        }

        if (recipientSession != null && callbacks.containsKey(recipientSession)) {
            // Deliver immediately
            try {
                callbacks.get(recipientSession).receiveMessage(sender, wrappedPayload);
                return true;
            } catch (RemoteException e) {
                // If callback fails, fall back to offline storage
                recipientUser.offlineMessages.add(wrappedPayload);
                return true;
            }
        } else {
            // Offline: store in queue
            recipientUser.offlineMessages.add(wrappedPayload);
            return true;
        }
    }

    @Override
    public List<byte[]> fetchMessages(String sessionToken) throws RemoteException {
        String username = sessionToUser.get(sessionToken);
        if (username == null) return Collections.emptyList();
        if (!checkRateLimit(username)) return Collections.emptyList();
        User u = users.get(username);
        if (u == null) return Collections.emptyList();
        List<byte[]> messages = new ArrayList<>();
        byte[] msg;
        while ((msg = u.offlineMessages.poll()) != null) {
            messages.add(msg);
        }
        return messages;
    }

    @Override
    public void registerCallback(String sessionToken, SecureMessageI callback) throws RemoteException {
        String username = sessionToUser.get(sessionToken);
        if (username == null) return;
        callbacks.put(sessionToken, callback);

        // Flush offline messages for this user
        User u = users.get(username);
        if (u != null) {
            byte[] wrapped;
            while ((wrapped = u.offlineMessages.poll()) != null) {
                try {
                    // Parse the sender from the wrapped payload
                    ByteArrayInputStream bais = new ByteArrayInputStream(wrapped);
                    DataInputStream dis = new DataInputStream(bais);
                    String sender = dis.readUTF();
                    // The callback expects (sender, wrapped)
                    callback.receiveMessage(sender, wrapped);
                } catch (RemoteException e) {
                    // Put back and stop to avoid infinite loop
                    u.offlineMessages.add(wrapped);
                    break;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void unregisterCallback(String sessionToken) throws RemoteException {
        callbacks.remove(sessionToken);
    }
}