package NotificationSide;

import BUEServiceSide.Publisher;
import BUEServiceSide.PublisherI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class NotificationApp extends JFrame {
    private PublisherI pub;
    private JTextArea messageArea;
    private JTextField inputField;
    private JButton sendButton;
    private JButton registerPublisherButton;
    private JComboBox<String> serviceComboBox;

    public static void main(String[] args) {
        NotificationApp not1 = new NotificationApp();
        not1.setVisible(true);
        NotificationApp not2 = new NotificationApp();
        not2.setVisible(true);
        NotificationApp not3 = new NotificationApp();
        not3.setVisible(true);
    }

    public NotificationApp()  {

        try {
            initializeGUI();
            setupRMI();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void initializeGUI() {
        setTitle("BUE Notification Sender");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(600, 400);
        setLayout(new BorderLayout());

        // Top panel with service selection
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        serviceComboBox = new JComboBox<>();
        registerPublisherButton = new JButton("Register New Publisher");
        topPanel.add(new JLabel("Select Service:"));
        topPanel.add(serviceComboBox);
        topPanel.add(registerPublisherButton);

        // Message area
        messageArea = new JTextArea();
        messageArea.setEditable(false);
        messageArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(messageArea);

        // Input panel
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputField = new JTextField();
        sendButton = new JButton("Send Announcement");
        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        // Add components to frame
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(inputPanel, BorderLayout.SOUTH);

        // Add action listeners
        sendButton.addActionListener(this::sendAnnouncement);
        inputField.addActionListener(this::sendAnnouncement);
        registerPublisherButton.addActionListener(this::registerNewPublisher);
        setLocationRelativeTo(null);
    }

    private void setupRMI() throws Exception {
        Registry reg = LocateRegistry.getRegistry("localhost", 1099);

        // Get list of available services
        String[] serviceNames = reg.list();
        for (String name : serviceNames) {
            serviceComboBox.addItem(name);
        }

        if (serviceNames.length > 0) {
            pub = (PublisherI) reg.lookup(serviceNames[0]);
            log("Connected to service: " + serviceNames[0]);
        }

        serviceComboBox.addActionListener(e -> {
            try {
                String selectedService = (String) serviceComboBox.getSelectedItem();
                pub = (PublisherI) reg.lookup(selectedService);
                log("Switched to service: " + selectedService);
            } catch (Exception ex) {
                log("Error switching service: " + ex.getMessage());
            }
        });
    }

    private void sendAnnouncement(ActionEvent e) {
        String message = inputField.getText();
        if (!message.trim().isEmpty()) {
            try {
                pub.makeannouncement("Announcement sent by "+ pub.getName()+": "+ message);
                System.out.println(pub.getName());
                log("Announcement sent by "+ pub.getName()+": "+ message);
                inputField.setText("");
            } catch (Exception ex) {
                log("Error sending announcement: " + ex.getMessage());
                JOptionPane.showMessageDialog(this,
                        "Error sending announcement: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    private void registerNewPublisher(ActionEvent e) {
        String publisherName = JOptionPane.showInputDialog(this,
                "Enter name for new publisher:",
                "Register New Publisher",
                JOptionPane.PLAIN_MESSAGE);

        if (publisherName != null && !publisherName.trim().isEmpty()) {
            try {
                Registry reg = LocateRegistry.getRegistry("localhost", 1099);

                // Check if publisher already exists
                try {
                    PublisherI existing = (PublisherI) reg.lookup(publisherName);
                    JOptionPane.showMessageDialog(this,
                            "Publisher with this name already exists",
                            "Error",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                } catch (Exception ex) {

                }

                // Create and register new publisher
                PublisherI newPublisher = new Publisher(publisherName);
                reg.rebind(publisherName, newPublisher);

                // Add to combo box
                serviceComboBox.addItem(publisherName);
                serviceComboBox.setSelectedItem(publisherName);

                log("New publisher registered: " + publisherName);
            } catch (Exception ex) {
                log("Error registering publisher: " + ex.getMessage());
                JOptionPane.showMessageDialog(this,
                        "Error registering publisher: " + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    private void log(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        messageArea.append("[" + timestamp + "] " + message + "\n");
    }
}