package BUEServiceSide;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface UserManagerI extends Remote {
    // Dead code 'oneTimePrekeyEncoded' and 'fetchMessages' removed
    boolean register(String username, String email, String passwordHash, byte[] publicKeyEncoded) throws RemoteException;

    boolean requestOtp(String username, String password) throws RemoteException;

    String loginWithOtp(String username, String otp) throws RemoteException;

    void logout(String sessionToken) throws RemoteException;

    boolean userExistsByUsername(String username) throws RemoteException;
    boolean userExistsByEmail(String email) throws RemoteException;

    byte[] getUserPublicKey(String username, String sessionToken) throws RemoteException;

    boolean sendMessage(String sessionToken, String recipientUsername, byte[] payload, byte[] signature) throws RemoteException;

    void registerCallback(String sessionToken, SubscriberSide.SecureMessageI callback) throws RemoteException;
    void unregisterCallback(String sessionToken) throws RemoteException;
}