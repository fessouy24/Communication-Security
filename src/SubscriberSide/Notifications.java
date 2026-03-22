package SubscriberSide;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
public class Notifications extends UnicastRemoteObject implements NotificationsI{
    private ArrayList<String>mesaages;

    public Notifications() throws RemoteException {
        super();
        this.mesaages = new ArrayList<>();
    }
    @Override
    public ArrayList<String> getMesaages() {
        return mesaages;
    }

    @Override
    public void Recievemessage (String Message) throws RemoteException{
        mesaages.add(Message);
    }
}
