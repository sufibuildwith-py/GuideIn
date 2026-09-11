package gui;

import javax.swing.*;
import java.awt.*;

class ChatBubble extends JPanel {

    private static final Color AI_BG = new Color(0x15, 0x17, 0x19);
    private static final Color AI_BORDER = new Color(216, 179, 106, 45);
    private static final Color TEXT_LIGHT = new Color(0xEE, 0xE9, 0xDF);

    private float alpha = 1f;
    private int yOffset = 0;
    private Timer entranceTimer;

    ChatBubble(String sender, String text, boolean isUser) {
        setOpaque(false);
        setLayout(new FlowLayout(
                isUser ? FlowLayout.RIGHT : FlowLayout.LEFT,
                0,
                0
        ));

        RoundedPanel bubble = new RoundedPanel(16);
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(
                BorderFactory.createEmptyBorder(10, 14, 12, 14)
        );

        if (isUser) {
            bubble.setFillGradient(
                    new Color(0x9E, 0x77, 0x3D),
                    new Color(0x70, 0x52, 0x29)
            );
        } else {
            bubble.setFill(AI_BG);
            bubble.setBorderColor(AI_BORDER, 1f);
        }

        JLabel label = new JLabel(sender.toUpperCase());
        label.setFont(new Font("Segoe UI", Font.BOLD, 10));

        label.setForeground(
                isUser
                        ? new Color(255, 255, 255, 160)
                        : new Color(216, 179, 106, 150)
        );

        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(
                BorderFactory.createEmptyBorder(0, 0, 4, 0)
        );

        JTextArea textArea = new JTextArea(text);
        textArea.setEditable(false);
        textArea.setFocusable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);

        textArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        textArea.setForeground(TEXT_LIGHT);
        textArea.setColumns(38);
        textArea.setBorder(null);
        textArea.setAlignmentX(Component.LEFT_ALIGNMENT);

        bubble.add(label);
        bubble.add(textArea);

        add(bubble);
    }

    /**
     * Starts a smooth fade + slide-up entrance animation.
     */
    void startEntranceAnimation() {

        alpha = 0f;
        yOffset = 16;

        if (entranceTimer != null && entranceTimer.isRunning()) {
            entranceTimer.stop();
        }

        final long startTime = System.currentTimeMillis();
        final int durationMs = 260;

        entranceTimer = new Timer(12, null);

        entranceTimer.addActionListener(e -> {

            long elapsed =
                    System.currentTimeMillis() - startTime;

            float t = Math.min(
                    1f,
                    elapsed / (float) durationMs
            );

            // Cubic ease-out
            float eased =
                    1f - (float) Math.pow(1f - t, 3);

            alpha = eased;

            yOffset =
                    Math.round(16 * (1f - eased));

            repaint();

            if (t >= 1f) {
                ((Timer) e.getSource()).stop();
            }
        });

        entranceTimer.start();
    }

    @Override
    protected void paintChildren(Graphics g) {

        Graphics2D g2 = (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        g2.setComposite(
                AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER,
                        Math.max(
                                0f,
                                Math.min(1f, alpha)
                        )
                )
        );

        g2.translate(0, yOffset);

        super.paintChildren(g2);

        g2.dispose();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(
                Integer.MAX_VALUE,
                super.getPreferredSize().height
        );
    }
}