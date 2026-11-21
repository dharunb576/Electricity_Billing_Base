import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.*;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public class EBMS_FileSystem {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginPage());
    }
}

// -----------------------------------------------------------
// File utilities for reading/writing CSVs and helper methods
// -----------------------------------------------------------
class FileUtils {
    static final String USERS_FILE = "users.csv";
    static final String BILLS_FILE = "bills.csv";
    static final DecimalFormat DEC = new DecimalFormat("#.00");
    // Due period constant (15 days)
    static final long DUE_PERIOD_DAYS = 15;
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Ensure files exist (create if missing)
    static void ensureFiles() {
        try {
            File uf = new File(USERS_FILE);
            if (!uf.exists()) uf.createNewFile();

            File bf = new File(BILLS_FILE);
            if (!bf.exists()) bf.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Read users.csv -> map username -> password
    static Map<String, String> readUsers() {
        ensureFiles();
        Map<String, String> users = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(USERS_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.strip().isEmpty()) continue;
                String[] parts = line.split(",", 2);
                if (parts.length == 2) users.put(parts[0], parts[1]);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return users;
    }

    // Append user to users.csv (username,password)
    static boolean appendUser(String username, String password) {
        ensureFiles();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(USERS_FILE, true))) {
            bw.write(username + "," + password);
            bw.newLine();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Bill class (Updated to include creationTime)
    static class Bill {
        int id;
        String username;
        int units;
        double amount;
        String status; // "PAID" or "NOT_PAID"
        long creationTime; // Milliseconds since epoch

        Bill(int id, String username, int units, double amount, String status, long creationTime) {
            this.id = id;
            this.username = username;
            this.units = units;
            this.amount = amount;
            this.status = status;
            this.creationTime = creationTime;
        }
    }

    // Read bills.csv -> list of Bills (Updated to read creationTime)
    static List<Bill> readBills() {
        ensureFiles();
        List<Bill> bills = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(BILLS_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.strip().isEmpty()) continue;
                // Bills now have 6 fields: id, user, units, amt, status, creationTime
                String[] p = line.split(",", 6);
                // Added compatibility for old bills missing the creationTime field
                if (p.length < 5) continue; 
                try {
                    int id = Integer.parseInt(p[0]);
                    String user = p[1];
                    int units = Integer.parseInt(p[2]);
                    double amt = Double.parseDouble(p[3]);
                    String status = p[4];
                    // If creation time is missing (old file format), default to current time
                    long cTime = (p.length == 6) ? Long.parseLong(p[5]) : System.currentTimeMillis(); 
                    bills.add(new Bill(id, user, units, amt, status, cTime));
                } catch (NumberFormatException ex) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bills;
    }

    // Write all bills back to bills.csv (overwrite) (Updated to write creationTime)
    static boolean writeBills(List<Bill> bills) {
        ensureFiles();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(BILLS_FILE))) {
            for (Bill b : bills) {
                // Now writing 6 fields
                bw.write(b.id + "," + b.username + "," + b.units + "," + DEC.format(b.amount) + "," + b.status + "," + b.creationTime);
                bw.newLine();
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    // --- New Date Parsing Helper ---
    // Converts a YYYY-MM-DD string to a milliseconds timestamp
    static long dateToTimestamp(String dateString) throws DateTimeParseException {
        // We parse the date at the start of the day (midnight) to be consistent
        return LocalDate.parse(dateString, DATE_FORMAT)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();
    }
    // ---------------------------------
    
    // Add bill, auto-generate id using max existing id + 1 (Updated to use custom date)
    static Bill addBill(String username, int units, long billTimestamp) {
        List<Bill> bills = readBills();
        int nextId = 1;
        for (Bill b : bills) if (b.id >= nextId) nextId = b.id + 1;
        double amount = units * 6.50; // flat rate 
        
        Bill nb = new Bill(nextId, username, units, amount, "NOT_PAID", billTimestamp);
        bills.add(nb);
        if (writeBills(bills)) return nb;
        return null;
    }

    // Delete bill by id
    static boolean deleteBill(int id) {
        List<Bill> bills = readBills();
        boolean removed = bills.removeIf(b -> b.id == id);
        if (removed) return writeBills(bills);
        return false;
    }

    // Update status (e.g., pay) for bill id
    static boolean updateBillStatus(int id, String status) {
        List<Bill> bills = readBills();
        boolean updated = false;
        for (Bill b : bills) {
            if (b.id == id) {
                b.status = status;
                updated = true;
                break;
            }
        }
        if (updated) return writeBills(bills);
        return false;
    }

    // --- UPDATED Due Date Logic Method ---
    // Returns the calculated due date or overdue status string.
    static String getDueStatus(Bill bill) {
        if ("PAID".equalsIgnoreCase(bill.status)) {
            return "Paid - No Due";
        }
        
        // Calculate due date
        Instant creation = Instant.ofEpochMilli(bill.creationTime);
        Instant dueInstant = creation.plus(DUE_PERIOD_DAYS, ChronoUnit.DAYS);
        LocalDate dueDate = dueInstant.atZone(ZoneId.systemDefault()).toLocalDate();
        Instant now = Instant.now();
        
        String dueDateString = dueDate.format(DATE_FORMAT);
        
        if (now.isAfter(dueInstant)) {
            Duration overdue = Duration.between(dueInstant, now);
            long days = overdue.toDays();
            return "**OVERDUE** (" + dueDateString + ", by " + days + " days)";
        } else {
            return "Due on: " + dueDateString;
        }
    }
}

// -----------------------------------------------------------
// Login Page (Admin / User) with Register button
// -----------------------------------------------------------
class LoginPage extends JFrame {
    JTextField userField;
    JPasswordField passField;
    JComboBox<String> roleBox;

    LoginPage() {
        FileUtils.ensureFiles();

        setTitle("EBMS - Login");
        setSize(380, 260);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel main = new JPanel(new GridLayout(5, 1, 8, 8));
        main.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel roleP = new JPanel(new FlowLayout(FlowLayout.LEFT));
        roleP.add(new JLabel("Role:"));
        roleBox = new JComboBox<>(new String[]{"Admin", "User"});
        roleP.add(roleBox);
        main.add(roleP);

        JPanel userP = new JPanel(new FlowLayout(FlowLayout.LEFT));
        userP.add(new JLabel("Username:      "));
        userField = new JTextField(18);
        userP.add(userField);
        main.add(userP);

        JPanel passP = new JPanel(new FlowLayout(FlowLayout.LEFT));
        passP.add(new JLabel("Password:      "));
        passField = new JPasswordField(18);
        passP.add(passField);
        main.add(passP);

        JPanel btnP = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        JButton loginBtn = new JButton("Login");
        JButton registerBtn = new JButton("Register (User)");
        btnP.add(loginBtn);
        btnP.add(registerBtn);
        main.add(btnP);

        add(main);

        // Actions
        loginBtn.addActionListener(e -> validateLogin());
        registerBtn.addActionListener(e -> new RegisterPage());

        setVisible(true);
    }

    void validateLogin() {
        String role = (String) roleBox.getSelectedItem();
        String username = userField.getText().trim();
        String password = new String(passField.getPassword()).trim();

        if (role.equals("Admin")) {
            if (username.equals("Vignesh") && password.equals("050706")) {
                JOptionPane.showMessageDialog(this, "Admin Login Successful!");
                dispose();
                new AdminPanel();
            } else {
                JOptionPane.showMessageDialog(this, "Invalid Admin Credentials!");
            }
            return;
        }

        // User login: check users.csv
        Map<String, String> users = FileUtils.readUsers();
        if (users.containsKey(username) && users.get(username).equals(password)) {
            JOptionPane.showMessageDialog(this, "User Login Successful!");
            dispose();
            new UserPanel(username);
        } else {
            JOptionPane.showMessageDialog(this, "Invalid Username or Password!");
        }
    }
}

// -----------------------------------------------------------
// Register Page (User only)
// -----------------------------------------------------------
class RegisterPage extends JFrame {
    JTextField userField;
    JPasswordField passField;

    RegisterPage() {
        setTitle("EBMS - Register User");
        setSize(340, 200);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel main = new JPanel(new GridLayout(3, 1, 8, 8));
        main.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel uP = new JPanel(new FlowLayout(FlowLayout.LEFT));
        uP.add(new JLabel("Username:    "));
        userField = new JTextField(18);
        uP.add(userField);
        main.add(uP);

        JPanel pP = new JPanel(new FlowLayout(FlowLayout.LEFT));
        pP.add(new JLabel("Password:    "));
        passField = new JPasswordField(18);
        pP.add(passField);
        main.add(pP);

        JPanel btnP = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton regBtn = new JButton("Register");
        btnP.add(regBtn);
        main.add(btnP);

        add(main);

        regBtn.addActionListener(e -> doRegister());

        setVisible(true);
    }

    void doRegister() {
        String username = userField.getText().trim();
        String password = new String(passField.getPassword()).trim();

        if (username.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter username and password.");
            return;
        }

        Map<String, String> users = FileUtils.readUsers();
        if (users.containsKey(username)) {
            JOptionPane.showMessageDialog(this, "Username already exists.");
            return;
        }

        boolean ok = FileUtils.appendUser(username, password);
        if (ok) {
            JOptionPane.showMessageDialog(this, "Registration Successful! You can login now.");
            dispose();
        } else {
            JOptionPane.showMessageDialog(this, "Registration failed (IO error).");
        }
    }
}

// -----------------------------------------------------------
// Admin Panel (Add Bill, Delete Bill, Back to Login)
// -----------------------------------------------------------
class AdminPanel extends JFrame {
    JTextField userField, unitsField, dateField; // Added dateField
    DefaultTableModel tableModel;
    JTable table;

    AdminPanel() {
        setTitle("Admin Panel - Manage Bills (Vignesh)");
        setSize(1050, 550); // Increased size to fit new date column
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Changed layout to 3 rows to accommodate date input
        JPanel top = new JPanel(new GridLayout(3, 3, 8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        top.add(new JLabel("Username:"));
        userField = new JTextField();
        top.add(userField);

        top.add(new JLabel("Units Consumed:"));
        unitsField = new JTextField();
        top.add(unitsField);

        // NEW DATE INPUT FIELD
        top.add(new JLabel("Bill Date (YYYY-MM-DD):"));
        dateField = new JTextField();
        // Set default to today's date for convenience
        dateField.setText(LocalDate.now().format(FileUtils.DATE_FORMAT)); 
        top.add(dateField);
        
        // Buttons adjusted to fill the grid
        JButton addBtn = new JButton("Add Bill");
        top.add(addBtn);

        JButton deleteBtn = new JButton("Delete Selected Bill");
        top.add(deleteBtn);

        JButton refreshBtn = new JButton("Refresh");
        top.add(refreshBtn);

        JButton backBtn = new JButton("Back to Login");
        top.add(backBtn);
        // The last spot (3,3) is left empty in this layout for spacing
        top.add(new JPanel()); // Empty panel for alignment/spacing

        add(top, BorderLayout.NORTH);

        // UPDATED: Added "Due Status" column (displaying actual date/status)
        tableModel = new DefaultTableModel(new String[]{"ID", "Username", "Units", "Amount", "Status", "Due Status"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        add(new JScrollPane(table), BorderLayout.CENTER);

        loadBills();

        // Actions
        addBtn.addActionListener(e -> addBill());
        deleteBtn.addActionListener(e -> deleteBill());
        refreshBtn.addActionListener(e -> loadBills());
        backBtn.addActionListener(e -> {
            dispose();
            new LoginPage();
        });

        setVisible(true);
    }

    void loadBills() {
        tableModel.setRowCount(0);
        List<FileUtils.Bill> bills = FileUtils.readBills();
        for (FileUtils.Bill b : bills) {

            // LOGIC ADDED: Calculate due status using the new method
            String dueStatus = FileUtils.getDueStatus(b);

            // UPDATED: Added dueStatus to the row data
            tableModel.addRow(new Object[]{b.id, b.username, b.units, String.format("%.2f", b.amount), b.status, dueStatus});
        }
    }

    void addBill() {
        String username = userField.getText().trim();
        String unitsText = unitsField.getText().trim();
        String dateText = dateField.getText().trim(); // NEW: Get date input

        if (username.isEmpty() || dateText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter username and bill date.");
            return;
        }
        int units;
        try {
            units = Integer.parseInt(unitsText);
            if (units < 0) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Enter valid integer units.");
            return;
        }

        // --- NEW DATE PARSING LOGIC ---
        long billTimestamp;
        try {
            // Convert the YYYY-MM-DD string into a timestamp
            billTimestamp = FileUtils.dateToTimestamp(dateText);
        } catch (DateTimeParseException ex) {
            JOptionPane.showMessageDialog(this, "Invalid date format. Use YYYY-MM-DD.");
            return;
        }
        // --- END NEW DATE PARSING LOGIC ---

        // UPDATED: Pass the calculated timestamp to addBill
        FileUtils.Bill nb = FileUtils.addBill(username, units, billTimestamp);
        if (nb != null) {
            JOptionPane.showMessageDialog(this, "Bill added (ID: " + nb.id + ")");
            userField.setText("");
            unitsField.setText("");
            // Set date back to today for the next bill entry
            dateField.setText(LocalDate.now().format(FileUtils.DATE_FORMAT));
            loadBills();
        } else {
            JOptionPane.showMessageDialog(this, "Failed to add bill (IO error).");
        }
    }

    void deleteBill() {
        int row = table.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Select a bill to delete.");
            return;
        }
        // ID is in column 0
        int id = (int) tableModel.getValueAt(row, 0);
        int confirm = JOptionPane.showConfirmDialog(this, "Delete bill ID " + id + " ?", "Confirm", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;
        boolean ok = FileUtils.deleteBill(id);
        if (ok) {
            JOptionPane.showMessageDialog(this, "Bill deleted.");
            loadBills();
        } else {
            JOptionPane.showMessageDialog(this, "Failed to delete bill.");
        }
    }
}

// -----------------------------------------------------------
// User Panel (View own bills, Pay bill, Back to Login)
// -----------------------------------------------------------
class UserPanel extends JFrame {
    DefaultTableModel tableModel;
    JTable table;
    String username;

    UserPanel(String username) {
        this.username = username;
        setTitle("User Panel - Your Bills (" + username + ")");
        setSize(850, 460); // Increased size to fit new column
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton payBtn = new JButton("Pay Selected Bill");
        JButton refreshBtn = new JButton("Refresh");
        JButton backBtn = new JButton("Back to Login");
        top.add(payBtn);
        top.add(refreshBtn);
        top.add(backBtn);
        add(top, BorderLayout.NORTH);

        // UPDATED: Added "Due Status" column
        tableModel = new DefaultTableModel(new String[]{"ID", "Units", "Amount", "Status", "Due Status"}, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        add(new JScrollPane(table), BorderLayout.CENTER);

        loadUserBills();

        payBtn.addActionListener(e -> payBill());
        refreshBtn.addActionListener(e -> loadUserBills());
        backBtn.addActionListener(e -> {
            dispose();
            new LoginPage();
        });

        setVisible(true);
    }

    void loadUserBills() {
        tableModel.setRowCount(0);
        List<FileUtils.Bill> bills = FileUtils.readBills();
        for (FileUtils.Bill b : bills) {
            if (b.username.equals(username)) {

                // LOGIC ADDED: Calculate due status using the new method
                String dueStatus = FileUtils.getDueStatus(b);

                // UPDATED: Added dueStatus to the row data
                tableModel.addRow(new Object[]{b.id, b.units, String.format("%.2f", b.amount), b.status, dueStatus});
            }
        }
    }

    void payBill() {
        int row = table.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(this, "Select a bill to pay.");
            return;
        }
        // ID is in column 0
        int id = (int) tableModel.getValueAt(row, 0);
        // Status is in column 3 (ID, Units, Amount, Status)
        String status = (String) tableModel.getValueAt(row, 3);

        if ("PAID".equalsIgnoreCase(status)) {
            JOptionPane.showMessageDialog(this, "This bill is already paid.");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this, "Pay bill ID " + id + " now?", "Confirm Payment", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        boolean ok = FileUtils.updateBillStatus(id, "PAID");
        if (ok) {
            JOptionPane.showMessageDialog(this, "Payment successful. Status updated to PAID.");
            loadUserBills();
        } else {
            JOptionPane.showMessageDialog(this, "Payment failed (IO error).");
        }
    }
}