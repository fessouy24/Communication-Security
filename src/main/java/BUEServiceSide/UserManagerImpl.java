package BUEServiceSide;

import SubscriberSide.SecureMessageI;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.mail.*;
import javax.mail.internet.*;

public class UserManagerImpl extends UnicastRemoteObject implements UserManagerI {

    private final Map<String, User> usersByUsername = new ConcurrentHashMap<>();
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToUsername = new ConcurrentHashMap<>();
    private final Map<String, SecureMessageI> callbacks = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> rateLimiters = new ConcurrentHashMap<>();
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();

    private static final int RATE_LIMIT = 10;
    private static final long RATE_WINDOW_MS = 60000;
    private static final long OTP_EXPIRY_MS = 5 * 60 * 1000;

    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "587";
    private static final String EMAIL_FROM = "SQAGROUP7@gmail.com";
    private static final String EMAIL_PASSWORD = "ezes rucv hewq hfno";

    public UserManagerImpl() throws RemoteException {
        super();
    }

    private static class User {
        String username;
        String email;
        byte[] passwordHash;
        byte[] salt;
        byte[] publicKeyEncoded;
        Queue<byte[]> offlineMessages; // lastSeen and oneTimePrekeys removed
    }

    private static class OtpEntry {
        String otp;
        long expiry;
    }

    private static class TokenBucket {
        int tokens;
        long lastRefill;
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.toLowerCase();
    }

    private synchronized boolean checkRateLimit(String username) {
        String key = normalizeUsername(username);
        TokenBucket bucket = rateLimiters.computeIfAbsent(key, k -> {
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
            return skf.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendOtpEmail(String email, String otp) {
        Properties props = new Properties();
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", SMTP_PORT);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(EMAIL_FROM, EMAIL_PASSWORD);
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(EMAIL_FROM));
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(email));
            message.setSubject("Your Secure Chat OTP");
            message.setText("Your one-time password is: " + otp + "\nIt is valid for 5 minutes.");

            Transport.send(message);
        } catch (MessagingException e) {
            System.out.println("Failed to send email, OTP printed to console.");
        }
    }

    @Override
    public boolean register(String username, String email, String passwordHash, byte[] publicKeyEncoded) throws RemoteException {
        String norm = normalizeUsername(username);
        if (usersByUsername.containsKey(norm) || usersByEmail.containsKey(email)) {
            return false;
        }
        if (!checkRateLimit(username)) return false;

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = hashPassword(passwordHash, salt);

        User u = new User();
        u.username = username;
        u.email = email;
        u.passwordHash = hash;
        u.salt = salt;
        u.publicKeyEncoded = publicKeyEncoded;
        u.offlineMessages = new ConcurrentLinkedQueue<>();

        usersByUsername.put(norm, u);
        usersByEmail.put(email, u);
        return true;
    }

    @Override
    public boolean userExistsByUsername(String username) throws RemoteException {
        return usersByUsername.containsKey(normalizeUsername(username));
    }

    @Override
    public boolean userExistsByEmail(String email) throws RemoteException {
        return usersByEmail.containsKey(email);
    }

    @Override
    public boolean requestOtp(String username, String password) throws RemoteException {
        String norm = normalizeUsername(username);
        User u = usersByUsername.get(norm);
        if (u == null) return false;
        if (!checkRateLimit(username)) return false;

        byte[] hash = hashPassword(password, u.salt);
        if (!Arrays.equals(hash, u.passwordHash)) return false;

        String otp = String.format("%06d", new SecureRandom().nextInt(1000000));
        OtpEntry entry = new OtpEntry();
        entry.otp = otp;
        entry.expiry = System.currentTimeMillis() + OTP_EXPIRY_MS;
        otpStore.put(norm, entry);

        sendOtpEmail(u.email, otp);
        System.out.println("=== OTP for " + username + ": " + otp + " ===");
        return true;
    }

    @Override
    public String loginWithOtp(String username, String otp) throws RemoteException {
        String norm = normalizeUsername(username);
        OtpEntry entry = otpStore.get(norm);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expiry) {
            otpStore.remove(norm);
            return null;
        }
        if (!entry.otp.equals(otp)) return null;

        otpStore.remove(norm);
        String token = UUID.randomUUID().toString();
        sessionToUsername.put(token, norm);
        return token;
    }

    @Override
    public void logout(String sessionToken) throws RemoteException {
        String username = sessionToUsername.remove(sessionToken);
        if (username != null) callbacks.remove(sessionToken);
    }

    @Override
    public byte[] getUserPublicKey(String username, String sessionToken) throws RemoteException {
        if (!sessionToUsername.containsKey(sessionToken)) return null;
        String requester = sessionToUsername.get(sessionToken);

        // Anti-Depletion Side-Channel Protection: Check Rate Limiting for the requester
        if (!checkRateLimit(requester)) return null;

        String norm = normalizeUsername(username);
        User u = usersByUsername.get(norm);
        return u == null ? null : u.publicKeyEncoded;
    }

    @Override
    public boolean sendMessage(String sessionToken, String recipientUsername, byte[] payload, byte[] signature) throws RemoteException {
        String senderNorm = sessionToUsername.get(sessionToken);
        if (senderNorm == null) return false;
        if (!checkRateLimit(senderNorm)) return false;

        User senderUser = usersByUsername.get(senderNorm);
        if (senderUser == null) return false;

        try {
            PublicKey pubKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(senderUser.publicKeyEncoded));
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pubKey);
            sig.update(payload);
            if (!sig.verify(signature)) return false;
        } catch (Exception e) {
            return false;
        }

        String recipientNorm = normalizeUsername(recipientUsername);
        User recipientUser = usersByUsername.get(recipientNorm);
        if (recipientUser == null) return false;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeUTF(senderUser.username);
            dos.writeInt(payload.length);
            dos.write(payload);
            dos.flush();
        } catch (IOException e) {
            return false;
        }
        byte[] wrappedPayload = baos.toByteArray();

        String recipientSession = null;
        for (Map.Entry<String, String> entry : sessionToUsername.entrySet()) {
            if (entry.getValue().equals(recipientNorm)) {
                recipientSession = entry.getKey();
                break;
            }
        }

        if (recipientSession != null && callbacks.containsKey(recipientSession)) {
            try {
                callbacks.get(recipientSession).receiveMessage(senderUser.username, wrappedPayload);
                return true;
            } catch (RemoteException e) {
                recipientUser.offlineMessages.add(wrappedPayload);
                return true;
            }
        } else {
            recipientUser.offlineMessages.add(wrappedPayload);
            return true;
        }
    }

    @Override
    public void registerCallback(String sessionToken, SecureMessageI callback) throws RemoteException {
        String username = sessionToUsername.get(sessionToken);
        if (username == null) return;
        callbacks.put(sessionToken, callback);

        User u = usersByUsername.get(username);
        if (u != null) {
            byte[] wrapped;
            while ((wrapped = u.offlineMessages.poll()) != null) {
                try {
                    ByteArrayInputStream bais = new ByteArrayInputStream(wrapped);
                    DataInputStream dis = new DataInputStream(bais);
                    String sender = dis.readUTF();
                    callback.receiveMessage(sender, wrapped);
                } catch (RemoteException e) {
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