package SubscriberSide;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.ArrayList;

public interface NotificationsI extends Remote{
    public void Recievemessage (String Message) throws RemoteException;
    public ArrayList<String> getMesaages() throws RemoteException;
}
