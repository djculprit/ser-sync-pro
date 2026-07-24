import javax.swing.*;
import java.awt.*;

/**
 * Base GUI log window for cdd-sync-pro.
 * Provides a text area for log output and a progress bar.
 * Extended by cdd_sync_pro_window for the full config UI.
 * Also used standalone by session-fixer.
 *
 * @author Roman Alekseenkov (original)
 */
public class cdd_sync_log_window extends JFrame {

    protected JTextArea textArea;
    protected JProgressBar progressBar;
    protected JLabel progressLabel;

    public cdd_sync_log_window(String title, int width, int height) {
        super(title);
        setSize(width, height);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Main panel with BorderLayout
        JPanel mainPanel = new JPanel(new BorderLayout());

        // Text area for logs
        textArea = new JTextArea();
        textArea.setEditable(false);
        JScrollPane pane = new JScrollPane(textArea);
        mainPanel.add(pane, BorderLayout.CENTER);

        // Progress panel at bottom
        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        progressLabel = new JLabel(" ");
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);

        progressPanel.add(progressLabel, BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        mainPanel.add(progressPanel, BorderLayout.SOUTH);

        getContentPane().add(mainPanel);
        setVisible(true);
    }

    /**
     * Appends data to the text area.
     */
    public void showInfo(String data) {
        SwingUtilities.invokeLater(() -> {
            textArea.append(data);
            textArea.setCaretPosition(textArea.getDocument().getLength());
        });
    }

    /**
     * Updates the progress bar and label.
     */
    public void setProgress(String message, int percent) {
        SwingUtilities.invokeLater(() -> {
            if (message == null || message.isEmpty()) {
                progressBar.setVisible(false);
                progressLabel.setText(" ");
            } else {
                progressBar.setVisible(true);
                progressBar.setValue(percent);
                progressLabel.setText(message);
            }
        });
    }
}
