package BUEServiceSide;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class SecureChatServer {
    public static void main(String[] args) {
        try {
            // Create RMI registry on port 1099 (plain TCP)
            Registry registry = LocateRegistry.createRegistry(1099);
            System.out.println("RMI registry started on port 1099");

            // Create and bind the UserManager service
            UserManagerImpl userManager = new UserManagerImpl();
            registry.rebind("UserManager", userManager);
            System.out.println("UserManager service registered");

            System.out.println("Secure Chat Server is running...");
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}