package SubscriberSide;

import BUEServiceSide.PublisherI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;
import java.util.List;

public class SubscriberApp {
    // Instance variables
    private NotificationsI mynoti;
    private JFrame frame;
    private DefaultListModel<String> messageListModel;
    private JList<String> messageList;
    private JLabel statusLabel;
    private DefaultListModel<String> availablePublishersModel;
    private JList<String> availablePublishersList;
    private DefaultListModel<String> subscribedPublishersModel;
    private JList<String> subscribedPublishersList;
    private Registry subscriberRegistry;
    private Map<String, PublisherI> currentSubscriptions = new HashMap<>();

    public static void main(String[] args) {
        // Launch 3 subscriber instances with different ports
        launchSubscriber(1098, "Subscriber 1");
        launchSubscriber(1097, "Subscriber 2");
        launchSubscriber(1096, "Subscriber 3");
    }

    private static void launchSubscriber(int port, String title) {
        SwingUtilities.invokeLater(() -> {
            try {
                SubscriberApp subscriber = new SubscriberApp(port);
                subscriber.frame.setTitle(title);
                subscriber.frame.setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null,
                        "Failed to start subscriber: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public SubscriberApp(int port) throws RemoteException {
        this.subscriberRegistry = LocateRegistry.createRegistry(port);
        initializeGUI();
        try {
            setupRMI();
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup RMI", e);
        }
    }

    private void initializeGUI() {
        frame = new JFrame("BUE Subscriber Application");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(900, 600);
        frame.setLayout(new BorderLayout());

        // Initialize models
        messageListModel = new DefaultListModel<>();
        availablePublishersModel = new DefaultListModel<>();
        subscribedPublishersModel = new DefaultListModel<>();

        // Create main panels
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        JSplitPane leftSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

        // Panel 1: Available Publishers
        JPanel availablePublishersPanel = createPublisherPanel(
                "Available Publishers",
                "Subscribe",
                e -> subscribeToSelectedPublishers()
        );

        // Panel 2: Subscribed Publishers
        JPanel subscribedPublishersPanel = createPublisherPanel(
                "My Subscriptions",
                "Unsubscribe",
                e -> unsubscribeFromSelectedPublishers()
        );

        // Panel 3: Messages
        JPanel messagesPanel = new JPanel(new BorderLayout());
        JLabel messagesLabel = new JLabel("Received Notifications");
        messagesLabel.setFont(new Font("Arial", Font.BOLD, 16));
        messagesPanel.add(messagesLabel, BorderLayout.NORTH);

        messageList = new JList<>(messageListModel);
        messageList.setFont(new Font("Monospaced", Font.PLAIN, 14));
        JScrollPane messageScrollPane = new JScrollPane(messageList);

        JPanel messageButtonPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Status: Not connected");
        JButton refreshMessagesButton = new JButton("Refresh Messages");
        JButton clearButton = new JButton("Clear Messages");

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(refreshMessagesButton);
        buttonPanel.add(clearButton);

        messageButtonPanel.add(statusLabel, BorderLayout.WEST);
        messageButtonPanel.add(buttonPanel, BorderLayout.EAST);

        messagesPanel.add(messageScrollPane, BorderLayout.CENTER);
        messagesPanel.add(messageButtonPanel, BorderLayout.SOUTH);

        // Configure split panes
        leftSplitPane.setTopComponent(availablePublishersPanel);
        leftSplitPane.setBottomComponent(subscribedPublishersPanel);
        leftSplitPane.setDividerLocation(300);

        mainSplitPane.setLeftComponent(leftSplitPane);
        mainSplitPane.setRightComponent(messagesPanel);
        mainSplitPane.setDividerLocation(350);

        frame.add(mainSplitPane, BorderLayout.CENTER);

        // Add action listeners
        refreshMessagesButton.addActionListener(e -> refreshMessages());
        clearButton.addActionListener(e -> clearMessages());

        frame.setLocationRelativeTo(null);
    }

    private JPanel createPublisherPanel(String title, String buttonText, ActionListener buttonAction) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(title);
        label.setFont(new Font("Arial", Font.BOLD, 16));
        panel.add(label, BorderLayout.NORTH);

        DefaultListModel<String> model = title.equals("Available Publishers") ?
                availablePublishersModel : subscribedPublishersModel;

        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane scrollPane = new JScrollPane(list);

        if (title.equals("Available Publishers")) {
            availablePublishersList = list;
        } else {
            subscribedPublishersList = list;
        }

        JButton actionButton = new JButton(buttonText);
        JButton refreshButton = new JButton("Refresh");

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(refreshButton);
        buttonPanel.add(actionButton);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        actionButton.addActionListener(buttonAction);
        refreshButton.addActionListener(e -> refreshPublishers());

        return panel;
    }

    private void setupRMI() throws Exception {
        statusLabel.setText("Status: Initializing RMI...");

        mynoti = new Notifications() {
            @Override
            public void Recievemessage(String Message) throws RemoteException {
                super.Recievemessage(Message);
                SwingUtilities.invokeLater(() -> {
                    messageListModel.addElement(new Date() + ": " + Message);
                    statusLabel.setText("Status: New message received!");
                });
            }
        };

        subscriberRegistry.bind("Notifications", mynoti);
        statusLabel.setText("Status: Ready - waiting for subscriptions");

        refreshPublishers();
    }

    private void refreshPublishers() {
        try {
            Registry serviceRegistry = LocateRegistry.getRegistry("localhost", 1099);
            String[] publisherNames = serviceRegistry.list();

            availablePublishersModel.clear();
            for (String name : publisherNames) {
                availablePublishersModel.addElement(name);
            }

            updateSubscribedPublishersList();
            statusLabel.setText("Status: Found " + publisherNames.length + " publishers");
        } catch (Exception e) {
            statusLabel.setText("Status: Error refreshing publishers");
            JOptionPane.showMessageDialog(frame,
                    "Error refreshing publishers: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void updateSubscribedPublishersList() {
        subscribedPublishersModel.clear();
        subscribedPublishersModel.addAll(currentSubscriptions.keySet());
    }

    private void subscribeToSelectedPublishers() {
        try {
            List<String> selectedPublishers = availablePublishersList.getSelectedValuesList();

            if (selectedPublishers.isEmpty()) {
                JOptionPane.showMessageDialog(frame,
                        "Please select at least one publisher to subscribe to",
                        "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            Registry serviceRegistry = LocateRegistry.getRegistry("localhost", 1099);

            for (String publisherName : selectedPublishers) {
                if (!currentSubscriptions.containsKey(publisherName)) {
                    PublisherI publisher = (PublisherI) serviceRegistry.lookup(publisherName);
                    publisher.addSubscriber(subscriberRegistry);
                    currentSubscriptions.put(publisherName, publisher);
                    statusLabel.setText("Status: Subscribed to " + publisherName);
                }
            }

            updateSubscribedPublishersList();
        } catch (Exception e) {
            statusLabel.setText("Status: Error subscribing");
            JOptionPane.showMessageDialog(frame,
                    "Error subscribing: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void unsubscribeFromSelectedPublishers() {
        try {
            List<String> selectedPublishers = subscribedPublishersList.getSelectedValuesList();

            if (selectedPublishers.isEmpty()) {
                JOptionPane.showMessageDialog(frame,
                        "Please select at least one publisher to unsubscribe from",
                        "Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }

            Registry serviceRegistry = LocateRegistry.getRegistry("localhost", 1099);

            for (String publisherName : selectedPublishers) {
                PublisherI publisher = currentSubscriptions.get(publisherName);
                if (publisher != null) {
                    publisher.removeSubscriber(subscriberRegistry);
                    currentSubscriptions.remove(publisherName);
                    statusLabel.setText("Status: Unsubscribed from " + publisherName);
                }
            }

            updateSubscribedPublishersList();
        } catch (Exception e) {
            statusLabel.setText("Status: Error unsubscribing");
            JOptionPane.showMessageDialog(frame,
                    "Error unsubscribing: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void refreshMessages() {
        try {
            ArrayList<String> messages = mynoti.getMesaages();
            messageListModel.clear();
            for (String msg : messages) {
                messageListModel.addElement(msg);
            }
            statusLabel.setText("Status: Messages refreshed");
        } catch (RemoteException e) {
            statusLabel.setText("Status: Error refreshing messages");
            JOptionPane.showMessageDialog(frame,
                    "Error refreshing messages: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void clearMessages() {
        try {
            mynoti.getMesaages().clear();
            messageListModel.clear();
            statusLabel.setText("Status: Messages cleared");
        } catch (RemoteException e) {
            statusLabel.setText("Status: Error clearing messages");
            JOptionPane.showMessageDialog(frame,
                    "Error clearing messages: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}