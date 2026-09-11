package gui;

import model.Message;
import model.Role;
import service.GUIAIClient;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.concurrent.ExecutionException;

public class GuideInFrame extends JFrame {

    private static final Color BG_TOP =
            new Color(0x0A, 0x0B, 0x0D);

    private static final Color BG_BOTTOM =
            new Color(0x15, 0x14, 0x19);

    private static final Color GOLD =
            new Color(0xD8, 0xB3, 0x6A);

    private static final Color TEXT_MUTED =
            new Color(0x9F, 0x87, 0x5E);

    private static final int COMPOSER_ALPHA_IDLE = 40;
    private static final int COMPOSER_ALPHA_FOCUSED = 150;

    private final ChatListPanel chatList =
            new ChatListPanel();

    private final JScrollPane scrollPane;

    private final PlaceholderTextField inputField =
            new PlaceholderTextField(
                    "Ask about careers, skills, or interview prep..."
            );

    private final GradientButton sendButton =
            new GradientButton("Send");

    private final GUIAIClient aiClient =
            new GUIAIClient();

    private final RoundedPanel composer =
            new RoundedPanel(20);

    private TypingBubble typingBubble;

    private Timer scrollTimer;
    private Timer composerGlowTimer;

    private int composerBorderAlpha =
            COMPOSER_ALPHA_IDLE;

    public GuideInFrame() {

        setTitle(
                "GuideIn — AI Career Mentor"
        );

        setSize(880, 660);

        setMinimumSize(
                new Dimension(640, 480)
        );

        setLocationRelativeTo(null);

        setDefaultCloseOperation(
                JFrame.EXIT_ON_CLOSE
        );

        JPanel root =
                new GradientBackgroundPanel(
                        BG_TOP,
                        BG_BOTTOM
                );

        root.setLayout(
                new BorderLayout()
        );

        setContentPane(root);

        root.add(
                buildHeader(),
                BorderLayout.NORTH
        );

        chatList.setBackground(
                BG_BOTTOM
        );

        scrollPane =
                new JScrollPane(chatList);

        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.setBackground(BG_BOTTOM);

        scrollPane.getViewport()
                .setOpaque(false);

        scrollPane.getViewport()
                .setBackground(BG_BOTTOM);

        scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants
                        .HORIZONTAL_SCROLLBAR_NEVER
        );

        scrollPane.getVerticalScrollBar()
                .setUI(
                        new DarkScrollBarUI()
                );

        scrollPane.getVerticalScrollBar()
                .setUnitIncrement(16);

        scrollPane.getVerticalScrollBar()
                .setOpaque(false);

        root.add(
                scrollPane,
                BorderLayout.CENTER
        );

        root.add(
                buildComposer(),
                BorderLayout.SOUTH
        );

        addBubble(
                "GuideIn",
                "Hello! Ask me anything about careers, skills, projects, or interviews.",
                false
        );
    }

    private JComponent buildHeader() {

        JPanel header =
                new JPanel(
                        new BorderLayout()
                );

        header.setOpaque(false);

        header.setBorder(
                new EmptyBorder(
                        18,
                        24,
                        18,
                        24
                )
        );

        JPanel left =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.LEFT,
                                12,
                                0
                        )
                );

        left.setOpaque(false);

        left.add(
                new BrandMark("G")
        );

        JPanel titles =
                new JPanel();

        titles.setOpaque(false);

        titles.setLayout(
                new BoxLayout(
                        titles,
                        BoxLayout.Y_AXIS
                )
        );

        JLabel title =
                new JLabel("GUIDEIN");

        title.setFont(
                new Font(
                        "Segoe UI",
                        Font.BOLD,
                        18
                )
        );

        title.setForeground(
                new Color(
                        0xEE,
                        0xE8,
                        0xDB
                )
        );

        title.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        JLabel subtitle =
                new JLabel(
                        "AI CAREER MENTOR"
                );

        subtitle.setFont(
                new Font(
                        "Segoe UI",
                        Font.PLAIN,
                        10
                )
        );

        subtitle.setForeground(
                TEXT_MUTED
        );

        subtitle.setAlignmentX(
                Component.LEFT_ALIGNMENT
        );

        titles.add(title);

        titles.add(
                Box.createVerticalStrut(2)
        );

        titles.add(subtitle);

        left.add(titles);

        header.add(
                left,
                BorderLayout.WEST
        );

        JPanel right =
                new JPanel(
                        new FlowLayout(
                                FlowLayout.RIGHT,
                                6,
                                0
                        )
                );

        right.setOpaque(false);

        JLabel statusText =
                new JLabel(
                        "GEMINI 2.5 FLASH"
                );

        statusText.setFont(
                new Font(
                        "Segoe UI",
                        Font.BOLD,
                        10
                )
        );

        statusText.setForeground(
                new Color(
                        0x6F,
                        0xC2,
                        0x82
                )
        );

        right.add(
                new StatusDot(
                        new Color(
                                0x5F,
                                0xD0,
                                0x7E
                        )
                )
        );

        right.add(
                Box.createHorizontalStrut(6)
        );

        right.add(statusText);

        header.add(
                right,
                BorderLayout.EAST
        );

        JPanel wrapper =
                new JPanel(
                        new BorderLayout()
                );

        wrapper.setOpaque(false);

        wrapper.add(
                header,
                BorderLayout.CENTER
        );

        wrapper.add(
                new GoldHairline(),
                BorderLayout.SOUTH
        );

        return wrapper;
    }

    private JComponent buildComposer() {

        JPanel outer =
                new JPanel(
                        new BorderLayout()
                );

        outer.setOpaque(false);

        outer.setBorder(
                new EmptyBorder(
                        0,
                        20,
                        20,
                        20
                )
        );

        composer.setFill(
                new Color(
                        0x11,
                        0x13,
                        0x16
                )
        );

        composer.setBorderColor(
                new Color(
                        216,
                        179,
                        106,
                        composerBorderAlpha
                ),
                1f
        );

        composer.setLayout(
                new BorderLayout(
                        10,
                        0
                )
        );

        composer.setBorder(
                new EmptyBorder(
                        6,
                        16,
                        6,
                        6
                )
        );

        inputField.setOpaque(false);

        inputField.setBorder(null);

        inputField.setForeground(
                new Color(
                        0xE9,
                        0xE3,
                        0xD8
                )
        );

        inputField.setCaretColor(GOLD);

        inputField.setFont(
                new Font(
                        "Segoe UI",
                        Font.PLAIN,
                        14
                )
        );

        inputField.addFocusListener(
                new FocusAdapter() {

                    @Override
                    public void focusGained(
                            FocusEvent e
                    ) {
                        animateComposerGlow(
                                COMPOSER_ALPHA_FOCUSED
                        );
                    }

                    @Override
                    public void focusLost(
                            FocusEvent e
                    ) {
                        animateComposerGlow(
                                COMPOSER_ALPHA_IDLE
                        );
                    }
                }
        );

        composer.add(
                inputField,
                BorderLayout.CENTER
        );

        sendButton.setPreferredSize(
                new Dimension(92, 38)
        );

        composer.add(
                sendButton,
                BorderLayout.EAST
        );

        outer.add(
                composer,
                BorderLayout.CENTER
        );

        sendButton.addActionListener(
                e -> sendMessage()
        );

        inputField.addActionListener(
                e -> sendMessage()
        );

        return outer;
    }

    private void animateComposerGlow(
            int target
    ) {

        if (
                composerGlowTimer != null
                        && composerGlowTimer.isRunning()
        ) {
            composerGlowTimer.stop();
        }

        composerGlowTimer =
                new Timer(
                        12,
                        null
                );

        composerGlowTimer.addActionListener(
                e -> {

                    int diff =
                            target
                                    - composerBorderAlpha;

                    if (
                            Math.abs(diff) <= 2
                    ) {

                        composerBorderAlpha =
                                target;

                        ((Timer) e.getSource())
                                .stop();

                    } else {

                        /*
                         * Important:
                         * cast back to int because
                         * composerBorderAlpha is an int.
                         */
                        composerBorderAlpha +=
                                Math.round(
                                        diff * 0.3f
                                );
                    }

                    composer.setBorderColor(
                            new Color(
                                    216,
                                    179,
                                    106,
                                    composerBorderAlpha
                            ),
                            1f
                    );

                    composer.repaint();
                }
        );

        composerGlowTimer.start();
    }

    private void sendMessage() {

        String input =
                inputField
                        .getText()
                        .trim();

        if (input.isEmpty()) {
            return;
        }

        inputField.setText("");

        setControlsEnabled(false);

        addBubble(
                "You",
                input,
                true
        );

        showTyping();

        SwingWorker<String, Void> worker =
                new SwingWorker<>() {

                    @Override
                    protected String doInBackground() {

                        return aiClient.getReply(
                                new Message(
                                        Role.USER,
                                        input
                                )
                        );
                    }

                    @Override
                    protected void done() {

                        hideTyping();

                        try {

                            addBubble(
                                    "GuideIn",
                                    get(),
                                    false
                            );

                        } catch (
                                InterruptedException e
                        ) {

                            Thread.currentThread()
                                    .interrupt();

                            addBubble(
                                    "GuideIn",
                                    "Request interrupted.",
                                    false
                            );

                        } catch (
                                ExecutionException e
                        ) {

                            Throwable cause =
                                    e.getCause();

                            addBubble(
                                    "GuideIn",
                                    "Error: "
                                            + (
                                            cause == null
                                                    ? e.getMessage()
                                                    : cause.getMessage()
                                    ),
                                    false
                            );

                        } finally {

                            setControlsEnabled(
                                    true
                            );

                            inputField
                                    .requestFocusInWindow();
                        }
                    }
                };

        worker.execute();
    }

    private void setControlsEnabled(
            boolean enabled
    ) {

        sendButton.setEnabled(enabled);

        inputField.setEnabled(enabled);
    }

    private void addBubble(
            String sender,
            String text,
            boolean isUser
    ) {

        ChatBubble bubble =
                new ChatBubble(
                        sender,
                        text,
                        isUser
                );

        chatList.add(bubble);

        chatList.add(
                Box.createVerticalStrut(12)
        );

        chatList.revalidate();

        bubble.startEntranceAnimation();

        scrollToBottomSmooth();
    }

    private void showTyping() {

        typingBubble =
                new TypingBubble();

        chatList.add(
                typingBubble
        );

        chatList.add(
                Box.createVerticalStrut(12)
        );

        chatList.revalidate();

        scrollToBottomSmooth();
    }

    private void hideTyping() {

        if (typingBubble == null) {
            return;
        }

        typingBubble.stopAnimation();

        Component[] comps =
                chatList.getComponents();

        int idx = -1;

        for (int i = 0; i < comps.length; i++) {

            if (comps[i] == typingBubble) {

                idx = i;
                break;
            }
        }

        if (idx >= 0) {

            chatList.remove(idx);

            if (
                    idx
                            < chatList
                            .getComponentCount()
            ) {

                chatList.remove(idx);
            }
        }

        typingBubble = null;

        chatList.revalidate();
        chatList.repaint();
    }

    private void scrollToBottomSmooth() {

        SwingUtilities.invokeLater(() -> {

            JScrollBar bar =
                    scrollPane
                            .getVerticalScrollBar();

            int target =
                    bar.getMaximum()
                            - bar.getVisibleAmount();

            int start =
                    bar.getValue();

            if (
                    Math.abs(target - start)
                            < 2
            ) {

                bar.setValue(target);
                return;
            }

            if (
                    scrollTimer != null
                            && scrollTimer.isRunning()
            ) {

                scrollTimer.stop();
            }

            final long startTime =
                    System.currentTimeMillis();

            final int duration = 240;

            scrollTimer =
                    new Timer(
                            12,
                            null
                    );

            scrollTimer.addActionListener(
                    e -> {

                        long elapsed =
                                System.currentTimeMillis()
                                        - startTime;

                        float t =
                                Math.min(
                                        1f,
                                        elapsed
                                                / (float) duration
                                );

                        float eased =
                                1f
                                        - (float)
                                        Math.pow(
                                                1f - t,
                                                3
                                        );

                        int max =
                                bar.getMaximum()
                                        - bar.getVisibleAmount();

                        int value =
                                Math.round(
                                        start
                                                + (max - start)
                                                * eased
                                );

                        bar.setValue(
                                Math.min(
                                        value,
                                        max
                                )
                        );

                        if (t >= 1f) {

                            ((Timer) e.getSource())
                                    .stop();
                        }
                    }
            );

            scrollTimer.start();
        });
    }
}