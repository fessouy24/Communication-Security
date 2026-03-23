package BUEServiceSide;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.security.PublicKey;
import java.util.List;

public interface UserManagerI extends Remote {
    boolean userExists(String username) throws RemoteException;
    boolean register(String username, String passwordHash, byte[] publicKeyEncoded, List<byte[]> oneTimePrekeyEncoded) throws RemoteException;
    String login(String username, String passwordHash) throws RemoteException;
    void logout(String sessionToken) throws RemoteException;
    byte[] getUserPublicKey(String username, String sessionToken) throws RemoteException;
    byte[] getOneTimePrekey(String username, String sessionToken) throws RemoteException;
    boolean sendMessage(String sessionToken, String recipient, byte[] payload, byte[] signature) throws RemoteException;
    List<byte[]> fetchMessages(String sessionToken) throws RemoteException;
    void registerCallback(String sessionToken, SubscriberSide.SecureMessageI callback) throws RemoteException;
    void unregisterCallback(String sessionToken) throws RemoteException;
}