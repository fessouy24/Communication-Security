package SubscriberSide;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface SecureMessageI extends Remote {
    void receiveMessage(String sender, byte[] wrappedPayload) throws RemoteException;
}