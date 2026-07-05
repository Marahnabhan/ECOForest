package client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;

/**
 * StoryClientGui - Swing GUI client for ECOForest.
 *
 * Protocol:
 *   Client -> Server: NAME <user>, PICK <title>, SENTENCE <sentence>,
 *                     CREATE_STORY <title>|<opening sentence>
 *   Server -> Client: STORIES a|b|c, STORY <text>, ROUND <taken> <max>,
 *                     YOUR_TURN, WAIT, END, INFO <msg>, REFRESH_STORIES a|b|c
 *
 * Threading: reader thread blocks on din.readUTF() and posts UI work to
 * the EDT via SwingUtilities.invokeLater; sends happen on the EDT.
 */
public class StoryClientGui {

    private static final String HOST = "localhost";
    private static final int    PORT = 6500;

    // ECOForest palette.
    private static final Color FOREST_DEEP = new Color(0x1F, 0x4A, 0x2E);
    private static final Color FOREST_MOSS = new Color(0x4A, 0x7A, 0x4F);
    private static final Color FOREST_SAGE = new Color(0xA8, 0xC4, 0xA2);
    private static final Color PARCHMENT   = new Color(0xF7, 0xF3, 0xE8);
    private static final Color BARK        = new Color(0x3E, 0x2F, 0x22);
    private static final Color WAIT_TAN    = new Color(0x6B, 0x5A, 0x3A);
    private static final Color ERROR_RED   = new Color(0x8B, 0x1A, 0x1A);

    private Socket           socket;
    private DataInputStream  din;
    private DataOutputStream dout;

    private String  username;
    private boolean gameOver = false;

    private JFrame    frame;
    private JLabel    stateLabel;
    private JLabel    roundLabel;
    private JTextArea storyArea;
    private JTextField sentenceField;
    private JButton    sendButton;
    private JButton    leaveButton;

    private JFrame dialogOwner;

    // -------------------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new StoryClientGui().start());
    }

    // -------------------------------------------------------------------------
    private void start() {
        dialogOwner = createDialogOwner();

        username = promptUsername();
        if (username == null) { dialogOwner.dispose(); return; }

        try {
            socket = new Socket(HOST, PORT);
            dout   = new DataOutputStream(socket.getOutputStream());
            din    = new DataInputStream(socket.getInputStream());
            dout.writeUTF("NAME " + username);

            String storiesMsg = din.readUTF();
            if (!storiesMsg.startsWith("STORIES ")) {
                showFatal("Unexpected first message from server:\n" + storiesMsg);
                return;
            }
            String[] titles = storiesMsg.substring("STORIES ".length()).split("\\|");

            // Show the combined pick/create dialog.
            // Returns null if the user cancelled, otherwise the title to join.
            String picked = showStoryPickerDialog(titles);
            if (picked == null) {
                socket.close();
                dialogOwner.dispose();
                return;
            }
            dout.writeUTF("PICK " + picked);

            buildUi(picked);
            dialogOwner.dispose();
            new Thread(this::readerLoop, "ecoforest-reader").start();

        } catch (IOException e) {
            showFatal("Could not connect to " + HOST + ":" + PORT + "\n\n" + e.getMessage());
        }
    }

    // =========================================================================
    // Story picker dialog — dropdown + inline "create new story" form
    // =========================================================================

    /**
     * Shows a custom dialog with:
     *   • A JComboBox listing existing stories
     *   • A "＋ Create New Story" toggle that expands a mini-form inline
     *
     * Returns the title of the story the user wants to join (creating it first
     * via CREATE_STORY if they filled in the form), or null if they cancelled.
     */
    private String showStoryPickerDialog(String[] initialTitles) {
        // --- mutable state shared between the dialog components ---
        final String[][] titlesHolder = { initialTitles };
        final boolean[]  createMode   = { false };
        final String[]   result       = { null };

        JDialog dialog = new JDialog(dialogOwner, "ECOForest — Enter the Forest", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.getContentPane().setBackground(PARCHMENT);

        // ── outer layout ──────────────────────────────────────────────────────
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(PARCHMENT);
        root.setBorder(new EmptyBorder(0, 0, 0, 0));

        // ── header ────────────────────────────────────────────────────────────
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(FOREST_DEEP);
        header.setBorder(new EmptyBorder(16, 20, 16, 20));
        header.add(new TreeMark(44, FOREST_SAGE, BARK), BorderLayout.WEST);

        JLabel titleLbl = new JLabel(
                "<html><span style='color:#A8C4A2;font-weight:bold;'>ECO</span>" +
                        "<span style='color:#F7F3E8;font-weight:bold;'>Forest</span></html>");
        titleLbl.setFont(new Font(Font.SERIF, Font.BOLD, 26));
        JLabel subLbl = new JLabel("Welcome, " + username + ". Choose your story.");
        subLbl.setForeground(FOREST_SAGE);
        subLbl.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));

        JPanel headerText = new JPanel();
        headerText.setOpaque(false);
        headerText.setLayout(new BoxLayout(headerText, BoxLayout.Y_AXIS));
        headerText.add(titleLbl);
        headerText.add(Box.createVerticalStrut(3));
        headerText.add(subLbl);
        header.add(headerText, BorderLayout.CENTER);

        // ── body ──────────────────────────────────────────────────────────────
        JPanel body = new JPanel();
        body.setBackground(PARCHMENT);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(20, 24, 8, 24));

        // --- "Join existing story" label
        JLabel joinLabel = styledLabel("Join an existing story:", Font.BOLD, 13);
        joinLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // --- combo box
        JComboBox<String> combo = new JComboBox<>(titlesHolder[0]);
        combo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        combo.setBackground(PARCHMENT);
        combo.setForeground(BARK);
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

        // --- divider label
        JLabel orLabel = styledLabel("— or —", Font.ITALIC, 12);
        orLabel.setForeground(WAIT_TAN);
        orLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // --- toggle button
        JButton toggleBtn = createToggleButton();
        toggleBtn.setAlignmentX(Component.LEFT_ALIGNMENT);

        // --- create-form panel (hidden initially)
        JPanel createPanel = buildCreateFormPanel();
        createPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        createPanel.setVisible(false);

        body.add(joinLabel);
        body.add(Box.createVerticalStrut(6));
        body.add(combo);
        body.add(Box.createVerticalStrut(14));
        body.add(orLabel);
        body.add(Box.createVerticalStrut(10));
        body.add(toggleBtn);
        body.add(Box.createVerticalStrut(6));
        body.add(createPanel);

        // ── button row ────────────────────────────────────────────────────────
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        btnRow.setBackground(PARCHMENT);
        btnRow.setBorder(new MatteBorder(1, 0, 0, 0, FOREST_SAGE));

        JButton cancelBtn = new JButton("Cancel");
        styleSecondaryBtn(cancelBtn);

        JButton okBtn = new JButton("Enter the Forest →");
        stylePrimaryBtn(okBtn);

        btnRow.add(cancelBtn);
        btnRow.add(okBtn);

        // ── wire up ───────────────────────────────────────────────────────────
        JTextField newTitleField   = (JTextField) createPanel.getClientProperty("titleField");
        JTextField newOpeningField = (JTextField) createPanel.getClientProperty("openingField");

        toggleBtn.addActionListener(e -> {
            createMode[0] = !createMode[0];
            createPanel.setVisible(createMode[0]);
            combo.setEnabled(!createMode[0]);
            if (createMode[0]) {
                toggleBtn.setText("✕  Cancel new story");
                toggleBtn.setBackground(ERROR_RED);
                toggleBtn.setOpaque(true);
                toggleBtn.setContentAreaFilled(true);
                newTitleField.requestFocusInWindow();
            } else {
                toggleBtn.setText("＋  Create a new story");
                toggleBtn.setBackground(FOREST_MOSS);
                toggleBtn.setOpaque(true);
                toggleBtn.setContentAreaFilled(true);
            }
            dialog.pack();
        });

        cancelBtn.addActionListener(e -> {
            result[0] = null;
            dialog.dispose();
        });

        okBtn.addActionListener(e -> {
            if (createMode[0]) {
                // Validate & create
                String newTitle   = newTitleField.getText().trim();
                String newOpening = newOpeningField.getText().trim();
                if (newTitle.isEmpty() || newOpening.isEmpty()) {
                    shake(dialog);
                    JOptionPane.showMessageDialog(dialog,
                            "Please fill in both the title and the opening sentence.",
                            "ECOForest", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (newTitle.contains("|")) {
                    JOptionPane.showMessageDialog(dialog,
                            "The title must not contain the '|' character.",
                            "ECOForest", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    dout.writeUTF("CREATE_STORY " + newTitle + "|" + newOpening);
                } catch (IOException ex) {
                    showFatal("Failed to send CREATE_STORY:\n" + ex.getMessage());
                    return;
                }
                result[0] = newTitle;
            } else {
                result[0] = (String) combo.getSelectedItem();
            }
            dialog.dispose();
        });

        // Enter key on fields triggers ok
        ActionListener okAction = e -> okBtn.doClick();
        newTitleField.addActionListener(okAction);
        newOpeningField.addActionListener(okAction);

        // ── assemble & show ───────────────────────────────────────────────────
        root.add(header, BorderLayout.NORTH);
        root.add(body,   BorderLayout.CENTER);
        root.add(btnRow, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, dialog.getHeight()));
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(dialogOwner);
        dialog.setVisible(true);  // blocks until disposed

        return result[0];
    }

    /** Builds the expandable "create new story" sub-form. */
    private JPanel buildCreateFormPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(PARCHMENT);
        panel.setBorder(new CompoundBorder(
                new LineBorder(FOREST_MOSS, 1, true),
                new EmptyBorder(12, 14, 12, 14)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));

        JLabel titleLbl = styledLabel("Story title:", Font.BOLD, 12);
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextField titleField = new JTextField();
        titleField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        titleField.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        styleInputField(titleField);

        JLabel openingLbl = styledLabel("Opening sentence:", Font.BOLD, 12);
        openingLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextField openingField = new JTextField();
        openingField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        openingField.setAlignmentX(Component.LEFT_ALIGNMENT);
        openingField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        styleInputField(openingField);

        panel.add(titleLbl);
        panel.add(Box.createVerticalStrut(4));
        panel.add(titleField);
        panel.add(Box.createVerticalStrut(10));
        panel.add(openingLbl);
        panel.add(Box.createVerticalStrut(4));
        panel.add(openingField);

        // Stash fields so the caller can retrieve them
        panel.putClientProperty("titleField",   titleField);
        panel.putClientProperty("openingField", openingField);

        return panel;
    }

    // =========================================================================
    // Main game UI
    // =========================================================================

    private void buildUi(String storyTitle) {
        frame = new JFrame("ECOForest  ·  " + storyTitle + "  ·  " + username);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                // Only called when the user clicks the OS close button.
                try { if (socket != null) socket.close(); } catch (IOException ignored) {}
                System.exit(0);
            }
        });

        frame.setContentPane(buildContent(storyTitle));
        frame.pack();
        frame.setMinimumSize(new Dimension(640, 480));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    private JPanel buildContent(String storyTitle) {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(PARCHMENT);

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildHeader(), BorderLayout.NORTH);
        north.add(buildStatusBar(storyTitle), BorderLayout.SOUTH);

        root.add(north, BorderLayout.NORTH);
        root.add(buildStoryArea(), BorderLayout.CENTER);
        root.add(buildInputRow(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(14, 0));
        header.setBackground(FOREST_DEEP);
        header.setBorder(new EmptyBorder(14, 18, 14, 18));

        header.add(new TreeMark(48, FOREST_SAGE, BARK), BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

        JLabel brand = new JLabel(
                "<html><span style='color:#A8C4A2; font-weight:bold;'>ECO</span>" +
                        "<span style='color:#F7F3E8; font-weight:bold;'>Forest</span></html>");
        brand.setFont(new Font(Font.SERIF, Font.BOLD, 30));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel tagline = new JLabel("A collaborative woodland story");
        tagline.setForeground(FOREST_SAGE);
        tagline.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);

        text.add(brand);
        text.add(Box.createVerticalStrut(2));
        text.add(tagline);
        header.add(text, BorderLayout.CENTER);

        return header;
    }

    private JPanel buildStatusBar(String storyTitle) {
        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setBorder(new EmptyBorder(8, 18, 8, 18));
        bar.setBackground(FOREST_MOSS);

        stateLabel = new JLabel("Connecting…");
        stateLabel.setForeground(Color.WHITE);
        stateLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));

        roundLabel = new JLabel("Round – of " + 12);
        roundLabel.setForeground(PARCHMENT);
        roundLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        roundLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        bar.add(stateLabel, BorderLayout.WEST);
        bar.add(roundLabel, BorderLayout.EAST);
        return bar;
    }

    private JComponent buildStoryArea() {
        storyArea = new JTextArea();
        storyArea.setEditable(false);
        storyArea.setLineWrap(true);
        storyArea.setWrapStyleWord(true);
        storyArea.setBackground(PARCHMENT);
        storyArea.setForeground(BARK);
        storyArea.setFont(new Font(Font.SERIF, Font.PLAIN, 17));
        storyArea.setMargin(new Insets(18, 22, 18, 22));

        JScrollPane scroll = new JScrollPane(storyArea);
        scroll.setBorder(new MatteBorder(0, 0, 1, 0, FOREST_SAGE));
        scroll.getViewport().setBackground(PARCHMENT);
        scroll.setPreferredSize(new Dimension(640, 320));
        return scroll;
    }

    private JPanel buildInputRow() {
        sentenceField = new JTextField();
        sentenceField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        sentenceField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FOREST_SAGE, 1),
                new EmptyBorder(6, 8, 6, 8)));
        sentenceField.setEnabled(false);

        sendButton = new JButton("Submit");
        sendButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        sendButton.setBackground(FOREST_MOSS);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setOpaque(true);
        sendButton.setContentAreaFilled(true);
        sendButton.setBorderPainted(false);
        sendButton.setBorder(new EmptyBorder(6, 18, 6, 18));
        sendButton.setEnabled(false);

        leaveButton = new JButton("← Leave Story");
        leaveButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        leaveButton.setBackground(WAIT_TAN);
        leaveButton.setForeground(Color.WHITE);
        leaveButton.setFocusPainted(false);
        leaveButton.setOpaque(true);
        leaveButton.setContentAreaFilled(true);
        leaveButton.setBorderPainted(false);
        leaveButton.setBorder(new EmptyBorder(6, 14, 6, 14));
        leaveButton.addActionListener(e -> leaveStory());

        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.setOpaque(false);
        buttons.add(leaveButton);
        buttons.add(sendButton);

        ActionListener sendAction = e -> sendSentence();
        sentenceField.addActionListener(sendAction);
        sendButton.addActionListener(sendAction);

        JLabel label = new JLabel("Add a sentence:");
        label.setForeground(BARK);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));

        JPanel inputRow = new JPanel(new BorderLayout(10, 0));
        inputRow.setBackground(PARCHMENT);
        inputRow.setBorder(new EmptyBorder(12, 18, 14, 18));
        inputRow.add(label, BorderLayout.WEST);
        inputRow.add(sentenceField, BorderLayout.CENTER);
        inputRow.add(buttons, BorderLayout.EAST);
        return inputRow;
    }

    // =========================================================================
    // Network reader loop
    // =========================================================================

    private void readerLoop() {
        try {
            while (true) {
                String msg = din.readUTF();
                if (msg.startsWith("STORY ")) {
                    String text = msg.substring("STORY ".length());
                    SwingUtilities.invokeLater(() -> {
                        storyArea.setText(text);
                        storyArea.setCaretPosition(storyArea.getDocument().getLength());
                    });
                } else if (msg.startsWith("ROUND ")) {
                    String[] parts = msg.substring("ROUND ".length()).split(" ");
                    if (parts.length == 2) {
                        try {
                            int taken = Integer.parseInt(parts[0]);
                            int max   = Integer.parseInt(parts[1]);
                            SwingUtilities.invokeLater(() ->
                                    roundLabel.setText("Round " + taken + " of " + max));
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (msg.equals("YOUR_TURN")) {
                    SwingUtilities.invokeLater(() -> {
                        setState("YOUR TURN — write the next sentence", FOREST_DEEP);
                        sentenceField.setEnabled(true);
                        sendButton.setEnabled(true);
                        sentenceField.requestFocusInWindow();
                    });
                } else if (msg.equals("WAIT")) {
                    SwingUtilities.invokeLater(() -> {
                        if (gameOver) return;
                        setState("Waiting for the other writers…", WAIT_TAN);
                        sentenceField.setEnabled(false);
                        sendButton.setEnabled(false);
                    });
                } else if (msg.equals("END")) {
                    SwingUtilities.invokeLater(this::handleGameEnd);
                } else if (msg.startsWith("STORIES ")) {
                    // Received a fresh story list after leaving — go to lobby.
                    String[] titles = msg.substring("STORIES ".length()).split("\\|");
                    SwingUtilities.invokeLater(() -> returnToLobby(titles));
                    return; // stop this reader thread; returnToLobby starts a new one
                } else if (msg.equals("BACK_TO_LOBBY")) {
                    // Handled via the STORIES message that precedes it; ignore.
                } else if (msg.startsWith("REFRESH_STORIES ")) {
                    // A new story was created by someone else while we are already
                    // in a game — nothing to do in the main UI; silently accepted.
                } else if (msg.startsWith("INFO ")) {
                    // Informational only — could be shown in a status bar if desired.
                }
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> {
                if (!gameOver) setState("Disconnected from the forest.", ERROR_RED);
                sentenceField.setEnabled(false);
                sendButton.setEnabled(false);
            });
        }
    }

    // =========================================================================
    // Game actions
    // =========================================================================

    private void leaveStory() {
        int confirm = JOptionPane.showConfirmDialog(
                frame,
                "Leave this story and return to the story picker?",
                "ECOForest — Leave Story",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;
        try {
            dout.writeUTF("LEAVE");
        } catch (IOException e) {
            setState("Failed to leave: " + e.getMessage(), ERROR_RED);
        }
    }

    private void returnToLobby(String[] titles) {
        frame.dispose();
        frame = null;
        gameOver = false;

        // Show the story picker again with the fresh list.
        String picked = showStoryPickerDialog(titles);
        if (picked == null) {
            // User cancelled — disconnect.
            try { socket.close(); } catch (IOException ignored) {}
            System.exit(0);
            return;
        }
        try {
            dout.writeUTF("PICK " + picked);
            buildUi(picked);
            new Thread(this::readerLoop, "ecoforest-reader").start();
        } catch (IOException e) {
            showFatal("Failed to join story:\n" + e.getMessage());
        }
    }

    private void sendSentence() {
        if (gameOver) return;
        String sentence = sentenceField.getText().trim().replaceAll("\\s+", " ");
        if (sentence.isEmpty()) return;
        try {
            dout.writeUTF("SENTENCE " + sentence);
            sentenceField.setText("");
            sentenceField.setEnabled(false);
            sendButton.setEnabled(false);
            setState("Sent. Waiting for the next turn…", WAIT_TAN);
        } catch (IOException e) {
            setState("Send failed: " + e.getMessage(), ERROR_RED);
        }
    }

    private void handleGameEnd() {
        gameOver = true;
        setState("The story is complete.", FOREST_DEEP);
        sentenceField.setEnabled(false);
        sendButton.setEnabled(false);
        leaveButton.setText("← Choose Another Story");
        leaveButton.setBackground(FOREST_MOSS);
        leaveButton.setEnabled(true);

        JTextArea finalText = new JTextArea(storyArea.getText());
        finalText.setEditable(false);
        finalText.setLineWrap(true);
        finalText.setWrapStyleWord(true);
        finalText.setBackground(PARCHMENT);
        finalText.setForeground(BARK);
        finalText.setFont(new Font(Font.SERIF, Font.PLAIN, 15));
        finalText.setMargin(new Insets(12, 14, 12, 14));
        JScrollPane scroll = new JScrollPane(finalText);
        scroll.setPreferredSize(new Dimension(540, 280));

        Object[] options = { "Choose Another Story", "Close" };
        int choice = JOptionPane.showOptionDialog(
                frame, scroll,
                "ECOForest — The Story is Complete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null, options, options[0]);

        if (choice == JOptionPane.YES_OPTION) {
            try {
                dout.writeUTF("LEAVE");
            } catch (IOException e) {
                setState("Error returning to lobby: " + e.getMessage(), ERROR_RED);
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setState(String text, Color bg) {
        stateLabel.setText(text);
        JPanel parent = (JPanel) stateLabel.getParent();
        parent.setBackground(bg);
    }

    private void showFatal(String message) {
        JOptionPane.showMessageDialog(dialogOwner, message,
                "ECOForest — Error", JOptionPane.ERROR_MESSAGE);
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        if (dialogOwner != null) dialogOwner.dispose();
        System.exit(1);
    }

    private JFrame createDialogOwner() {
        JFrame owner = new JFrame();
        owner.setUndecorated(true);
        owner.setSize(1, 1);
        owner.setLocationRelativeTo(null);
        owner.setAlwaysOnTop(true);
        owner.setVisible(true);
        owner.toFront();
        return owner;
    }

    private String promptUsername() {
        dialogOwner.toFront();
        String name = JOptionPane.showInputDialog(
                dialogOwner,
                "Welcome to ECOForest.\nEnter your name to join the woodland:",
                "ECOForest — Sign In",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null) return null;
        name = name.trim();
        return name.isEmpty() ? null : name;
    }

    // ── style helpers ─────────────────────────────────────────────────────────

    private static JLabel styledLabel(String text, int style, int size) {
        JLabel l = new JLabel(text);
        l.setForeground(BARK);
        l.setFont(new Font(Font.SANS_SERIF, style, size));
        return l;
    }

    private static void stylePrimaryBtn(JButton btn) {
        btn.setBackground(FOREST_MOSS);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorderPainted(false);
        btn.setBorder(new EmptyBorder(7, 18, 7, 18));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static void styleSecondaryBtn(JButton btn) {
        btn.setBackground(FOREST_SAGE);
        btn.setForeground(BARK);
        btn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorderPainted(false);
        btn.setBorder(new EmptyBorder(7, 14, 7, 14));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private static JButton createToggleButton() {
        JButton btn = new JButton("＋  Create a new story");
        btn.setBackground(FOREST_MOSS);
        btn.setForeground(Color.WHITE);
        btn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        btn.setBorderPainted(false);
        btn.setBorder(new EmptyBorder(7, 14, 7, 14));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return btn;
    }

    private static void styleInputField(JTextField field) {
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FOREST_SAGE, 1),
                new EmptyBorder(4, 6, 4, 6)));
        field.setBackground(PARCHMENT);
        field.setForeground(BARK);
        field.setCaretColor(BARK);
    }

    /** Briefly shakes a window to indicate a validation error. */
    private static void shake(Window w) {
        final int[] pos = { w.getX() };
        Timer t = new Timer(20, null);
        final int[] count = { 0 };
        t.addActionListener(e -> {
            int offset = (count[0] % 2 == 0) ? 8 : -8;
            w.setLocation(pos[0] + offset, w.getY());
            if (++count[0] == 8) { w.setLocation(pos[0], w.getY()); t.stop(); }
        });
        t.start();
    }

    // =========================================================================
    // TreeMark — small painted brand icon
    // =========================================================================

    private static class TreeMark extends JComponent {
        private final Color leaf, trunk;
        TreeMark(int size, Color leaf, Color trunk) {
            this.leaf  = leaf;
            this.trunk = trunk;
            setPreferredSize(new Dimension(size, size));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int trunkW = w / 6;
            g2.setColor(trunk);
            g2.fillRect(w / 2 - trunkW / 2, (int)(h * 0.72), trunkW, (int)(h * 0.22));
            g2.setColor(leaf);
            g2.fillPolygon(new int[]{ w/2, (int)(w*.15), (int)(w*.85) },
                    new int[]{ (int)(h*.55),(int)(h*.78),(int)(h*.78) }, 3);
            g2.fillPolygon(new int[]{ w/2, (int)(w*.2),  (int)(w*.8)  },
                    new int[]{ (int)(h*.32),(int)(h*.6),(int)(h*.6)  }, 3);
            g2.fillPolygon(new int[]{ w/2, (int)(w*.28), (int)(w*.72) },
                    new int[]{ (int)(h*.08),(int)(h*.42),(int)(h*.42) }, 3);
            g2.dispose();
        }
    }
}