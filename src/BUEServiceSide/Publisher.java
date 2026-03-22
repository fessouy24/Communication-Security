package BUEServiceSide;

import SubscriberSide.NotificationsI;

import javax.swing.*;
import java.rmi.RemoteException;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;

public class Publisher extends UnicastRemoteObject implements PublisherI {
    private final String name;
    private List<String> announcementHistory = new ArrayList<>();
    private Map<String, Registry> subscribers;
    private String lastAnnouncement;

    public Publisher(String name) throws RemoteException {
        super(); // Important for RMI
        this.name = name;
        this.subscribers = new HashMap<>();  // Changed from ArrayList to HashMap
    }

    @Override
    public String getName() throws RemoteException {
        return name;
    }

    @Override
    public ArrayList<Registry> getSubscribers() throws RemoteException {
        return new ArrayList<>(subscribers.values());  // Return values from the map
    }

    @Override
    public int getSubscriberCount() throws RemoteException {
        return subscribers.size();
    }

    @Override
    public void addSubscriber(Registry subscriberReg) throws RemoteException {
        // In a real app, you'd need to get the username from the subscriber
        // For simplicity, we'll just use a generic "user" identifier
        subscribers.put("user_" + System.currentTimeMillis(), subscriberReg);
        System.out.println("Subscriber added. Total subscribers: " + subscribers.size());
    }

    @Override
    public void removeSubscriber(Registry subscriberReg) throws RemoteException {
        subscribers.values().remove(subscriberReg);
        System.out.println("Subscriber removed. Total subscribers: " + subscribers.size());
    }

    @Override
    public Void makeannouncement(String message) throws Exception {
        // Iterate through the values of the map (which are Registry objects)
        for (Registry sub : subscribers.values()) {
            NotificationsI subnoti = (NotificationsI) sub.lookup("Notifications");
            subnoti.Recievemessage(message);
        }
        lastAnnouncement = message;
        announcementHistory.add(new Date() + ": " + message);
        return null;
    }

    @Override
    public String getLastAnnouncement() throws RemoteException {
        return lastAnnouncement;
    }

    @Override
    public List<String> getAnnouncementHistory() throws RemoteException {
        return new ArrayList<>(announcementHistory);
    }
}