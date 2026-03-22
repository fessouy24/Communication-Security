package BUEServiceSide;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Timer;

public class BueServiceApp {
    private static JFrame frame;
    private static DefaultTableModel publishersTableModel;
    private static DefaultTableModel announcementsTableModel;
    private static JLabel totalPublishersLabel;
    private static JLabel totalSubscribersLabel;
    private static JTextArea logArea;
    private static Map<String, PublisherI> services = new HashMap<>();
    private static Timer updateTimer;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                initializeGUI();
                setupRMI();
                startAutoUpdate();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame,
                        "Error initializing application: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
            }
        });
    }

    private static void initializeGUI() {
        frame = new JFrame("BUE Service Monitor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 700);
        frame.setLayout(new BorderLayout());

        // Create tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();

        // Tab 1: Summary Dashboard
        JPanel dashboardPanel = new JPanel(new BorderLayout());

        // Stats panel at top
        JPanel statsPanel = new JPanel(new GridLayout(1, 2));
        totalPublishersLabel = new JLabel("Total Publishers: 0", JLabel.CENTER);
        totalPublishersLabel.setFont(new Font("Arial", Font.BOLD, 24));
        totalSubscribersLabel = new JLabel("Total Subscribers: 0", JLabel.CENTER);
        totalSubscribersLabel.setFont(new Font("Arial", Font.BOLD, 24));

        statsPanel.add(totalPublishersLabel);
        statsPanel.add(totalSubscribersLabel);
        dashboardPanel.add(statsPanel, BorderLayout.NORTH);

        // Publishers table in center
        String[] publisherColumns = {"Publisher Name", "Subscriber Count", "Last Announcement"};
        publishersTableModel = new DefaultTableModel(publisherColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable publishersTable = new JTable(publishersTableModel);
        JScrollPane publishersScrollPane = new JScrollPane(publishersTable);
        dashboardPanel.add(publishersScrollPane, BorderLayout.CENTER);

        // Add dashboard tab
        tabbedPane.addTab("Dashboard", dashboardPanel);

        // Tab 2: Announcements
        JPanel announcementsPanel = new JPanel(new BorderLayout());
        String[] announcementColumns = {"Publisher", "Announcement", "Timestamp"};
        announcementsTableModel = new DefaultTableModel(announcementColumns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable announcementsTable = new JTable(announcementsTableModel);
        JScrollPane announcementsScrollPane = new JScrollPane(announcementsTable);
        announcementsPanel.add(announcementsScrollPane, BorderLayout.CENTER);

        // Add announcements tab
        tabbedPane.addTab("Announcements", announcementsPanel);

        // Bottom panel with log and controls
        JPanel bottomPanel = new JPanel(new BorderLayout());

        // Log area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setPreferredSize(new Dimension(0, 120));

        // Control panel
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refreshButton = new JButton("Refresh Now");
        JButton clearLogButton = new JButton("Clear Log");

        controlPanel.add(refreshButton);
        controlPanel.add(clearLogButton);

        bottomPanel.add(logScrollPane, BorderLayout.CENTER);
        bottomPanel.add(controlPanel, BorderLayout.SOUTH);

        // Add components to frame
        frame.add(tabbedPane, BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        // Add action listeners
        refreshButton.addActionListener(e -> updateStats());
        clearLogButton.addActionListener(e -> logArea.setText(""));

        // Display the frame
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void setupRMI() throws Exception {
        Registry registry = LocateRegistry.createRegistry(1099);
        addServiceToRegistry(registry, "Academic Services");
        addServiceToRegistry(registry, "Administrative Services");
        addServiceToRegistry(registry, "Student Services");

        updateStats();
    }

    private static void addServiceToRegistry(Registry registry, String serviceName) throws RemoteException {
        Publisher service = new Publisher(serviceName);
        registry.rebind(serviceName, service);
        services.put(serviceName, service);
        log("Service '" + serviceName + "' registered");
    }

    private static void startAutoUpdate() {
        updateTimer = new Timer();
        updateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                SwingUtilities.invokeLater(() -> updateStats());
            }
        }, 0, 5000); // Update every 5 seconds
    }

    private static void updateStats() {
        try {
            Registry registry = LocateRegistry.getRegistry(1099);
            String[] serviceNames = registry.list();

            int totalSubscribers = 0;
            publishersTableModel.setRowCount(0);

            for (String name : serviceNames) {
                PublisherI service = (PublisherI) registry.lookup(name);
                int subscribers = service.getSubscriberCount();
                totalSubscribers += subscribers;

                // Get last announcement (you'll need to add this method to your Publisher class)
                String lastAnnouncement = service.getLastAnnouncement();

                publishersTableModel.addRow(new Object[]{
                        name,
                        subscribers,
                        lastAnnouncement != null ? lastAnnouncement : "No announcements yet"
                });
            }

            totalPublishersLabel.setText("Total Publishers: " + serviceNames.length);
            totalSubscribersLabel.setText("Total Subscribers: " + totalSubscribers);

            log("Stats updated - " + serviceNames.length + " publishers, " +
                    totalSubscribers + " total subscribers");

        } catch (Exception e) {
            log("Error updating stats: " + e.getMessage());
        }
    }

    private static void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logArea.append("[" + timestamp + "] " + message + "\n");
    }
}