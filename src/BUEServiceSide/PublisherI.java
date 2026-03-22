package BUEServiceSide;

import javax.swing.*;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public interface PublisherI extends Remote {
    String getName() throws RemoteException;
    ArrayList<Registry> getSubscribers() throws RemoteException;
    int getSubscriberCount() throws RemoteException;
    void addSubscriber(Registry subscriberReg) throws RemoteException;
    Void makeannouncement(String message) throws Exception;
    void removeSubscriber(Registry subscriberReg) throws RemoteException;
    String getLastAnnouncement() throws RemoteException;
    List<String> getAnnouncementHistory() throws RemoteException;
}