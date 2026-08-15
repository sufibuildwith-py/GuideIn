package gui;

import javax.swing.*;
import java.awt.*;

class StatusDot extends JComponent {

    private final Color color;

    private float pulse = 0f;
    private boolean increasing = true;

    private final Timer timer;

    StatusDot(Color color) {

        this.color = color;

        setPreferredSize(
                new Dimension(10, 10)
        );

        setOpaque(false);

        timer = new Timer(40, e -> {

            if (increasing) {
                pulse += 0.05f;

                if (pulse >= 1f) {
                    pulse = 1f;
                    increasing = false;
                }

            } else {

                pulse -= 0.05f;

                if (pulse <= 0f) {
                    pulse = 0f;
                    increasing = true;
                }
            }

            repaint();
        });

        timer.start();
    }

    @Override
    protected void paintComponent(Graphics g) {

        Graphics2D g2 =
                (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        int glowAlpha =
                Math.round(35 + pulse * 70);

        int glowSize =
                Math.round(10 + pulse * 6);

        int glowX =
                (getWidth() - glowSize) / 2;

        int glowY =
                (getHeight() - glowSize) / 2;

        g2.setColor(
                new Color(
                        color.getRed(),
                        color.getGreen(),
                        color.getBlue(),
                        glowAlpha
                )
        );

        g2.fillOval(
                glowX,
                glowY,
                glowSize,
                glowSize
        );

        int dotSize = 6;

        g2.setColor(color);

        g2.fillOval(
                (getWidth() - dotSize) / 2,
                (getHeight() - dotSize) / 2,
                dotSize,
                dotSize
        );

        g2.dispose();
    }
}