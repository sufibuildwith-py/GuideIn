package gui;

import javax.swing.*;
import java.awt.*;

class TypingBubble extends JPanel {

    private static final Color BG =
            new Color(0x15, 0x17, 0x19);

    private static final Color BORDER =
            new Color(216, 179, 106, 45);

    private static final Color DOT =
            new Color(216, 179, 106);

    private int animationStep = 0;

    private final Timer timer;

    TypingBubble() {

        setOpaque(false);

        setLayout(
                new FlowLayout(
                        FlowLayout.LEFT,
                        0,
                        0
                )
        );

        RoundedPanel bubble =
                new RoundedPanel(16);

        bubble.setFill(BG);
        bubble.setBorderColor(BORDER, 1f);

        bubble.setLayout(
                new FlowLayout(
                        FlowLayout.LEFT,
                        5,
                        12
                )
        );

        bubble.setBorder(
                BorderFactory.createEmptyBorder(
                        0,
                        14,
                        0,
                        14
                )
        );

        bubble.add(
                new DotComponent(0)
        );

        bubble.add(
                new DotComponent(1)
        );

        bubble.add(
                new DotComponent(2)
        );

        add(bubble);

        timer = new Timer(120, e -> {

            animationStep++;

            repaint();
        });

        timer.start();
    }

    void stopAnimation() {

        timer.stop();
    }

    @Override
    public Dimension getMaximumSize() {

        return new Dimension(
                Integer.MAX_VALUE,
                super.getPreferredSize().height
        );
    }

    private class DotComponent extends JComponent {

        private final int index;

        DotComponent(int index) {

            this.index = index;

            setPreferredSize(
                    new Dimension(8, 20)
            );
        }

        @Override
        protected void paintComponent(Graphics g) {

            Graphics2D g2 =
                    (Graphics2D) g.create();

            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            double phase =
                    (animationStep + index * 2)
                            * Math.PI / 3.0;

            float pulse =
                    (float)
                            ((Math.sin(phase) + 1.0) / 2.0);

            int size =
                    5 + Math.round(3 * pulse);

            int x =
                    (getWidth() - size) / 2;

            int y =
                    (getHeight() - size) / 2;

            int alpha =
                    90 + Math.round(165 * pulse);

            g2.setColor(
                    new Color(
                            DOT.getRed(),
                            DOT.getGreen(),
                            DOT.getBlue(),
                            alpha
                    )
            );

            g2.fillOval(
                    x,
                    y,
                    size,
                    size
            );

            g2.dispose();
        }
    }
}