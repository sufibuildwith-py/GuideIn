package gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

class GradientButton extends JButton {

    private static final Color BASE_TOP =
            new Color(0xE1, 0xC2, 0x7A);

    private static final Color BASE_BOTTOM =
            new Color(0x9B, 0x72, 0x38);

    private static final Color HOVER_TOP =
            new Color(0xF0, 0xD7, 0x8E);

    private static final Color HOVER_BOTTOM =
            new Color(0xB8, 0x96, 0x3A);

    private float hoverProgress = 0f;
    private float targetProgress = 0f;

    private boolean pressed = false;

    private final Timer animTimer;

    GradientButton(String text) {

        super(text);

        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);

        setForeground(new Color(0x16, 0x11, 0x08));

        setFont(
                new Font(
                        "Segoe UI",
                        Font.BOLD,
                        13
                )
        );

        setCursor(
                Cursor.getPredefinedCursor(
                        Cursor.HAND_CURSOR
                )
        );

        addMouseListener(new MouseAdapter() {

            @Override
            public void mouseEntered(MouseEvent e) {
                targetProgress = 1f;
                ensureAnimating();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                targetProgress = 0f;
                pressed = false;
                ensureAnimating();
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                pressed = true;
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                pressed = false;
                repaint();
            }
        });

        animTimer = new Timer(12, e -> {

            float diff =
                    targetProgress - hoverProgress;

            if (Math.abs(diff) < 0.01f) {

                hoverProgress = targetProgress;

                ((Timer) e.getSource()).stop();

            } else {

                hoverProgress += diff * 0.35f;
            }

            repaint();
        });
    }

    private void ensureAnimating() {

        if (!animTimer.isRunning()) {
            animTimer.start();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {

        Graphics2D g2 =
                (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        Color top =
                lerp(
                        BASE_TOP,
                        HOVER_TOP,
                        hoverProgress
                );

        Color bottom =
                lerp(
                        BASE_BOTTOM,
                        HOVER_BOTTOM,
                        hoverProgress
                );

        if (!isEnabled()) {

            top =
                    new Color(
                            0x6a,
                            0x63,
                            0x4c
                    );

            bottom =
                    new Color(
                            0x4a,
                            0x45,
                            0x35
                    );

        } else if (pressed) {

            top = top.darker();
            bottom = bottom.darker();
        }

        int inset = pressed ? 1 : 0;

        g2.setPaint(
                new GradientPaint(
                        0,
                        0,
                        top,
                        getWidth(),
                        getHeight(),
                        bottom
                )
        );

        g2.fillRoundRect(
                inset,
                inset,
                getWidth() - 1 - inset * 2,
                getHeight() - 1 - inset * 2,
                14,
                14
        );

        g2.dispose();

        super.paintComponent(g);
    }

    private static Color lerp(
            Color a,
            Color b,
            float t
    ) {

        int r =
                Math.round(
                        a.getRed()
                                + (b.getRed() - a.getRed()) * t
                );

        int g =
                Math.round(
                        a.getGreen()
                                + (b.getGreen() - a.getGreen()) * t
                );

        int bValue =
                Math.round(
                        a.getBlue()
                                + (b.getBlue() - a.getBlue()) * t
                );

        return new Color(r, g, bValue);
    }
}