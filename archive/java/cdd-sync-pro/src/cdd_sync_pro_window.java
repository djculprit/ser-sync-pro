import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.Properties;

/**
 * Full config + log GUI window for cdd-sync-pro.
 * Extends the base log window with interactive config controls.
 * Dark theme matching Music Timestamp Agent style.
 */
public class cdd_sync_pro_window extends cdd_sync_log_window {

    // Theme colors
    private static final Color BG_DARK = new Color(0x3C, 0x3F, 0x41);
    private static final Color BG_DARKER = new Color(0x2B, 0x2B, 0x2B);
    private static final Color BG_INPUT = new Color(0x45, 0x49, 0x4A);
    private static final Color BG_LOG = new Color(0x1E, 0x1E, 0x1E);
    private static final Color FG_TEXT = new Color(0xBB, 0xBB, 0xBB);
    private static final Color FG_LABEL = new Color(0xDD, 0xDD, 0xDD);
    private static final Color ACCENT_GREEN = new Color(0x5F, 0xA8, 0x5F);
    private static final Color ACCENT_AMBER = new Color(0xC8, 0x96, 0x2A);
    private static final Color ACCENT_RED = new Color(0xC7, 0x5F, 0x5F);
    private static final Color BORDER_COLOR = new Color(0x55, 0x58, 0x5A);

    // Path fields
    private JTextField musicFolderField;
    private JTextField seratoPathField;
    private JTextField parentCrateField;

    // Sync option controls
    private JCheckBox backupCheck;
    private JCheckBox clearLibraryCheck;
    private JCheckBox sortCratesCheck;

    // Pipeline step toggles (debug)
    private JCheckBox step1Check;
    private JCheckBox step2Check;
    private JCheckBox step3Check;
    private JCheckBox step4Check;

    // Duplicate management controls
    private JCheckBox dupeScanCheck;
    private JComboBox<String> dupeDetectionCombo;
    private JComboBox<String> dupeMoveCombo;

    // Action buttons
    private JButton startButton;
    private JButton cancelButton;

    // Sync state
    private volatile boolean syncRunning = false;
    private volatile boolean syncCancelled = false;
    private Runnable onStartCallback;

    public cdd_sync_pro_window() {
        super("cdd-sync-pro", 750, 700);

        // Remove the default content from base class
        getContentPane().removeAll();

        // Apply dark theme
        getContentPane().setBackground(BG_DARK);

        // Build the full layout
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(BG_DARK);

        // === Top: Config Panel ===
        JPanel configPanel = buildConfigPanel();
        mainPanel.add(configPanel, BorderLayout.NORTH);

        // === Center: Log Output ===
        JPanel logPanel = buildLogPanel();
        mainPanel.add(logPanel, BorderLayout.CENTER);

        // === Bottom: Progress + Buttons ===
        JPanel bottomPanel = buildBottomPanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        getContentPane().add(mainPanel);
        setLocationRelativeTo(null);
        revalidate();
        repaint();
    }

    // ==================== Panel Builders ====================

    private JPanel buildConfigPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 5, 12));

        // --- Path Fields ---
        panel.add(buildPathRow("Music Folder:", musicFolderField = createDarkTextField(), true));
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildPathRow("Serato Path:", seratoPathField = createDarkTextField(), true));
        panel.add(Box.createVerticalStrut(4));
        panel.add(buildPathRow("Parent Crate:", parentCrateField = createDarkTextField(), false));
        panel.add(Box.createVerticalStrut(8));

        // --- Pipeline Steps (all controls in execution order) ---
        panel.add(buildPipelineStepsPanel());
        panel.add(Box.createVerticalStrut(6));

        // --- Duplicate Management ---
        JPanel dupePanel = createTitledPanel("Duplicate Management");
        JPanel dupeContent = new JPanel();
        dupeContent.setLayout(new BoxLayout(dupeContent, BoxLayout.Y_AXIS));
        dupeContent.setBackground(BG_DARK);

        dupeScanCheck = createDarkCheckBox("Scan for duplicates", false);
        dupeScanCheck.setToolTipText("<html><b>[DEBUG] Scan for duplicates</b><br>"
                + "Enables the hard-drive duplicate scanner (cdd_sync_dupe_mover).<br>"
                + "When Move mode is active: dupes are physically moved before crates are built,<br>"
                + "then database V2 is patched and the media library is rescanned.<br>"
                + "When Move mode is 'false': scan runs log-only AFTER crates are written.<br>"
                + "Config key: harddrive.dupe.scan.enabled</html>");
        JPanel scanRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        scanRow.setBackground(BG_DARK);
        scanRow.add(dupeScanCheck);
        dupeContent.add(scanRow);

        JPanel dupeDropdowns = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        dupeDropdowns.setBackground(BG_DARK);
        dupeDropdowns.add(createDarkLabel("Detection:"));
        dupeDetectionCombo = createDarkComboBox(new String[] { "name-and-size", "name-only", "off" });
        dupeDetectionCombo.setToolTipText("<html><b>[DEBUG] Dupe detection strategy</b><br>"
                + "<b>name-and-size</b> — filename + file size must match (safest)<br>"
                + "<b>name-only</b> — filename match only (catches bitrate variants)<br>"
                + "<b>off</b> — detection disabled (scan check ignored)<br>"
                + "Config key: harddrive.dupe.detection.mode</html>");
        dupeDropdowns.add(dupeDetectionCombo);
        dupeDropdowns.add(Box.createHorizontalStrut(15));
        dupeDropdowns.add(createDarkLabel("Move mode:"));
        dupeMoveCombo = createDarkComboBox(new String[] { "keep-oldest", "keep-newest", "false" });
        dupeMoveCombo.setToolTipText("<html><b>[DEBUG] Dupe move mode</b><br>"
                + "<b>keep-oldest</b> — keeps the oldest file, moves newer dupe to /dupes/<br>"
                + "<b>keep-newest</b> — keeps the newest file, moves older dupe to /dupes/<br>"
                + "<b>false</b> — no files moved; scan is log-only<br>"
                + "Moves happen BEFORE crates are built. database V2 is patched post-move.<br>"
                + "Config key: harddrive.dupe.move.enabled</html>");
        dupeDropdowns.add(dupeMoveCombo);
        dupeContent.add(dupeDropdowns);

        dupePanel.add(dupeContent, BorderLayout.CENTER);
        panel.add(dupePanel);

        return panel;
    }

    private JPanel buildPipelineStepsPanel() {
        JPanel panel = createTitledPanel("Pipeline Steps");
        JPanel grid = new JPanel(new GridLayout(0, 2, 10, 2));
        grid.setBackground(BG_DARK);

        // --- Pre-steps ---
        backupCheck = createDarkCheckBox("Backup", true);
        backupCheck.setToolTipText("<html><b>Backup | music.library.database.backup</b><br>"
                + "Creates a timestamped ZIP of _Serato_ before any changes.<br>"
                + "If backup fails, sync is aborted. Disable for faster dev cycles.</html>");

        clearLibraryCheck = createDarkCheckBox("Clear Library \u26a0\ufe0f", false);
        clearLibraryCheck.setToolTipText("<html><b>Clear Library | music.library.database.clear-before-sync</b><br>"
                + "<b>DESTRUCTIVE</b> \u2014 Deletes ALL Crates, Subcrates, and database V2 before sync.<br>"
                + "Clean rebuild from scratch. Disables Fix Database Paths &amp; Fix Crate Paths.<br>"
                + "Requires confirmation.</html>");
        clearLibraryCheck.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                int choice = JOptionPane.showConfirmDialog(
                        this,
                        "\u26a0\ufe0f  This will DELETE all existing Serato crates and database V2 before sync.\n\n"
                                + "All crate structure will be rebuilt from scratch.\n"
                                + "This cannot be undone (unless backup is enabled).\n\n"
                                + "Are you sure you want to enable this?",
                        "Destructive Option \u2014 Confirm",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    clearLibraryCheck.setSelected(false);
                }
            }
        });

        // --- Path fixing ---
        step1Check = createDarkCheckBox("Fix Database Paths", true);
        step1Check.setToolTipText("<html><b>Fix Database Paths | sync.step1.enabled</b><br>"
                + "Fix broken pfil paths in database V2.<br>"
                + "Requires Gate 1+2 (Fix broken paths) to be ON.</html>");

        step2Check = createDarkCheckBox("Fix Existing Crate Paths", true);
        step2Check.setToolTipText("<html><b>Fix Existing Crate Paths | sync.step2.enabled</b><br>"
                + "Re-resolve broken track paths in all existing .crate files using database V2.<br>"
                + "Reloads DB from disk after Fix Database Paths. Requires Gate 1+2 to be ON.</html>");

        // --- Crate writing ---
        step3Check = createDarkCheckBox("Append Existing Crates", true);
        step3Check.setToolTipText("<html><b>Append Existing Crates | sync.step3.enabled</b><br>"
                + "Append new tracks to existing folder-mapped crates.<br>"
                + "Dedup prevents adding tracks already in the crate.</html>");

        step4Check = createDarkCheckBox("Create New Crates", true);
        step4Check.setToolTipText("<html><b>Create New Crates | sync.step4.enabled</b><br>"
                + "Create new .crate files for library folders with no matching crate on disk.<br>"
                + "Skips folders whose crate already exists (Append Existing Crates handles those).</html>");

        // --- Post-step ---
        sortCratesCheck = createDarkCheckBox("Reset Crates: A\u2192Z", false);
        sortCratesCheck.setToolTipText("<html><b>Reset Crates: A\u2192Z | crate.sorting.alphabetical</b><br>"
                + "Runs after all steps. Rewrites neworder.pref so crates appear A\u2192Z in Serato.<br>"
                + "Display order only \u2014 no effect on crate contents.</html>");

        // Add in execution order (2-column grid, left-to-right top-to-bottom)
        grid.add(backupCheck);       // row 1 left
        grid.add(clearLibraryCheck); // row 1 right
        grid.add(step1Check);        // row 2 left
        grid.add(step2Check);        // row 2 right
        grid.add(step3Check);        // row 3 left
        grid.add(step4Check);        // row 3 right
        grid.add(sortCratesCheck);   // row 4 left (intentionally last)
        panel.add(grid, BorderLayout.NORTH);
        return panel;
    }

    private JPanel buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_DARK);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));

        // Reuse base class textArea
        textArea.setBackground(BG_LOG);
        textArea.setForeground(FG_TEXT);
        textArea.setCaretColor(FG_TEXT);
        textArea.setFont(new Font("Menlo", Font.PLAIN, 12));
        textArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setBackground(BG_DARK);
        scrollPane.getViewport().setBackground(BG_LOG);
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(BORDER_COLOR),
                        "Log Output",
                        TitledBorder.LEFT, TitledBorder.TOP,
                        new Font("SansSerif", Font.PLAIN, 12), FG_LABEL),
                BorderFactory.createLineBorder(BG_DARKER, 2)));

        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_DARK);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));

        // Progress
        JPanel progressPanel = new JPanel(new BorderLayout(5, 2));
        progressPanel.setBackground(BG_DARK);

        progressLabel.setForeground(FG_LABEL);
        progressLabel.setText("Ready");
        progressBar.setVisible(false);

        progressPanel.add(progressBar, BorderLayout.NORTH);
        progressPanel.add(progressLabel, BorderLayout.SOUTH);
        panel.add(progressPanel, BorderLayout.NORTH);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        buttonPanel.setBackground(BG_DARK);

        startButton = new JButton("  Start  ");
        startButton.setBackground(ACCENT_GREEN);
        startButton.setForeground(Color.WHITE);
        startButton.setOpaque(true);
        startButton.setFocusPainted(false);
        startButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        startButton.setPreferredSize(new Dimension(130, 34));
        startButton.setToolTipText("Full sync: write crates + fix paths");
        startButton.addActionListener(e -> onStartClicked());

        cancelButton = new JButton("  Cancel  ");
        cancelButton.setBackground(BG_INPUT);
        cancelButton.setForeground(FG_TEXT);
        cancelButton.setOpaque(true);
        cancelButton.setFocusPainted(false);
        cancelButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        cancelButton.setPreferredSize(new Dimension(130, 34));
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> onCancelClicked());

        buttonPanel.add(startButton);
        buttonPanel.add(cancelButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    // ==================== Dark-themed Widget Factories ====================

    private JPanel buildPathRow(String label, JTextField field, boolean withBrowse) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(BG_DARK);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        JLabel lbl = createDarkLabel(label);
        lbl.setPreferredSize(new Dimension(100, 24));
        row.add(lbl, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);

        if (withBrowse) {
            JButton browseBtn = new JButton("Browse");
            browseBtn.setBackground(BG_INPUT);
            browseBtn.setForeground(FG_TEXT);
            browseBtn.setFocusPainted(false);
            browseBtn.setPreferredSize(new Dimension(80, 24));
            browseBtn.addActionListener(e -> browseFolder(field));
            row.add(browseBtn, BorderLayout.EAST);
        }

        return row;
    }

    private JTextField createDarkTextField() {
        JTextField field = new JTextField();
        field.setBackground(BG_INPUT);
        field.setForeground(FG_TEXT);
        field.setCaretColor(FG_TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        field.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return field;
    }

    private JCheckBox createDarkCheckBox(String text, boolean selected) {
        JCheckBox cb = new JCheckBox(text, selected);
        cb.setBackground(BG_DARK);
        cb.setForeground(FG_LABEL);
        cb.setFocusPainted(false);
        cb.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return cb;
    }

    private JComboBox<String> createDarkComboBox(String[] items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setBackground(BG_INPUT);
        combo.setForeground(FG_TEXT);
        combo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        combo.setPreferredSize(new Dimension(140, 24));
        return combo;
    }

    private JLabel createDarkLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(FG_LABEL);
        label.setFont(new Font("SansSerif", Font.PLAIN, 12));
        return label;
    }

    private JPanel createTitledPanel(String title) {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBackground(BG_DARK);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(BORDER_COLOR),
                        title, TitledBorder.LEFT, TitledBorder.TOP,
                        new Font("SansSerif", Font.PLAIN, 12), FG_LABEL),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        return panel;
    }

    private void browseFolder(JTextField targetField) {
        JFileChooser chooser = new JFileChooser(targetField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            targetField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ==================== Config Load/Save ====================

    /**
     * Populates all controls from the given properties.
     */
    public void loadFromProperties(Properties props) {
        musicFolderField.setText(props.getProperty("music.library.filesystem", ""));
        seratoPathField.setText(props.getProperty("music.library.database", ""));
        parentCrateField.setText(props.getProperty("crate.parent.path", ""));

        backupCheck.setSelected(!"false".equalsIgnoreCase(
                props.getProperty("music.library.database.backup", "true")));
        clearLibraryCheck.setSelected("true".equalsIgnoreCase(
                props.getProperty("music.library.database.clear-before-sync", "false")));
        sortCratesCheck.setSelected("true".equalsIgnoreCase(
                props.getProperty("crate.sorting.alphabetical", "false")));

        step1Check.setSelected(!"false".equalsIgnoreCase(
                props.getProperty("sync.step1.enabled", "true")));
        step2Check.setSelected(!"false".equalsIgnoreCase(
                props.getProperty("sync.step2.enabled", "true")));
        step3Check.setSelected(!"false".equalsIgnoreCase(
                props.getProperty("sync.step3.enabled", "true")));
        step4Check.setSelected(!"false".equalsIgnoreCase(
                props.getProperty("sync.step4.enabled", "true")));

        dupeScanCheck.setSelected("true".equalsIgnoreCase(
                props.getProperty("harddrive.dupe.scan.enabled", "false")));

        String dupeDetection = props.getProperty("harddrive.dupe.detection.mode", "off");
        selectComboItem(dupeDetectionCombo, dupeDetection);

        String dupeMove = props.getProperty("harddrive.dupe.move.enabled", "false");
        selectComboItem(dupeMoveCombo, dupeMove);
    }

    /**
     * Collects current control values into a Properties object.
     */
    public Properties collectProperties() {
        Properties props = new Properties();
        props.setProperty("mode", "gui");
        props.setProperty("music.library.filesystem", musicFolderField.getText().trim());
        props.setProperty("music.library.database", seratoPathField.getText().trim());

        String parentCrate = parentCrateField.getText().trim();
        if (!parentCrate.isEmpty()) {
            props.setProperty("crate.parent.path", parentCrate);
        }

        props.setProperty("music.library.database.backup", String.valueOf(backupCheck.isSelected()));
        props.setProperty("music.library.database.clear-before-sync", String.valueOf(clearLibraryCheck.isSelected()));
        props.setProperty("crate.sorting.alphabetical", String.valueOf(sortCratesCheck.isSelected()));

        props.setProperty("sync.step1.enabled", String.valueOf(step1Check.isSelected()));
        props.setProperty("sync.step2.enabled", String.valueOf(step2Check.isSelected()));
        props.setProperty("sync.step3.enabled", String.valueOf(step3Check.isSelected()));
        props.setProperty("sync.step4.enabled", String.valueOf(step4Check.isSelected()));

        props.setProperty("harddrive.dupe.scan.enabled", String.valueOf(dupeScanCheck.isSelected()));
        props.setProperty("harddrive.dupe.detection.mode", (String) dupeDetectionCombo.getSelectedItem());
        props.setProperty("harddrive.dupe.move.enabled", (String) dupeMoveCombo.getSelectedItem());

        return props;
    }

    /**
     * Saves current control values to the properties file on disk.
     */
    public void saveToFile() {
        Properties props = collectProperties();
        try (FileOutputStream out = new FileOutputStream(cdd_sync_config.CONFIG_FILE)) {
            props.store(out, "cdd-sync-pro configuration");
        } catch (IOException e) {
            cdd_sync_log.error("Failed to save config: " + e.getMessage());
        }
    }

    private void selectComboItem(JComboBox<String> combo, String value) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).equalsIgnoreCase(value)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    // ==================== Action Handlers ====================

    /**
     * Sets the callback to invoke when Start is clicked.
     */
    public void setOnStartCallback(Runnable callback) {
        this.onStartCallback = callback;
    }



    /**
     * Returns true if the user has requested cancellation.
     */
    public boolean isCancelled() {
        return syncCancelled;
    }

    private void onStartClicked() {
        // Validate required fields
        if (musicFolderField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Music Folder is required.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (seratoPathField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Serato Path is required.", "Validation", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Save settings to file
        saveToFile();

        // Switch to running state
        syncRunning = true;
        syncCancelled = false;
        setControlsEnabled(false);
        startButton.setEnabled(false);
        cancelButton.setEnabled(true);
        cancelButton.setBackground(ACCENT_RED);
        progressLabel.setText("Starting sync...");
        textArea.setText(""); // Clear previous log

        if (onStartCallback != null) {
            onStartCallback.run();
        }
    }



    private void onCancelClicked() {
        syncCancelled = true;
        cancelButton.setEnabled(false);
        progressLabel.setText("Cancelling...");
    }

    /**
     * Called when sync completes (success or error). Re-enables controls.
     */
    public void syncFinished() {
        SwingUtilities.invokeLater(() -> {
            syncRunning = false;
            setControlsEnabled(true);
            startButton.setEnabled(true);
            cancelButton.setEnabled(false);
            cancelButton.setBackground(BG_INPUT);
        });
    }

    private void setControlsEnabled(boolean enabled) {
        musicFolderField.setEnabled(enabled);
        seratoPathField.setEnabled(enabled);
        parentCrateField.setEnabled(enabled);
        backupCheck.setEnabled(enabled);
        clearLibraryCheck.setEnabled(enabled);
        sortCratesCheck.setEnabled(enabled);
        step1Check.setEnabled(enabled);
        step2Check.setEnabled(enabled);
        step3Check.setEnabled(enabled);
        step4Check.setEnabled(enabled);
        dupeScanCheck.setEnabled(enabled);
        dupeDetectionCombo.setEnabled(enabled);
        dupeMoveCombo.setEnabled(enabled);
    }
}
