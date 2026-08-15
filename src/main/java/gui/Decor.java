package gui;

import javax.swing.*;
import java.awt.*;
import java.util.Random;

/**
 * Animated ambient background for GuideIn.
 */
class GradientBackgroundPanel extends JPanel {

    private final Color top;
    private final Color bottom;

    private final Random random = new Random(42);

    private final Particle[] particles;

    private float time = 0f;

    private final Timer animationTimer;

    GradientBackgroundPanel(Color top, Color bottom) {

        this.top = top;
        this.bottom = bottom;

        setOpaque(true);

        particles = new Particle[28];

        for (int i = 0; i < particles.length; i++) {
            particles[i] = new Particle();
        }

        animationTimer = new Timer(33, e -> {

            time += 0.012f;

            for (Particle particle : particles) {
                particle.update();
            }

            repaint();
        });

        animationTimer.start();
    }

    @Override
    protected void paintComponent(Graphics g) {

        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        int width = getWidth();
        int height = getHeight();

        if (width <= 0 || height <= 0) {
            g2.dispose();
            return;
        }

        // Base gradient
        g2.setPaint(
                new GradientPaint(
                        0,
                        0,
                        top,
                        width,
                        height,
                        bottom
                )
        );

        g2.fillRect(
                0,
                0,
                width,
                height
        );

        // Main breathing glow
        float breathing =
                (float) (
                        (Math.sin(time * 0.55) + 1.0)
                                / 2.0
                );

        float glowX =
                width *
                        (
                                0.5f
                                        + 0.18f
                                        * (float)
                                        Math.sin(time * 0.22)
                        );

        float glowY =
                height *
                        (
                                0.48f
                                        + 0.14f
                                        * (float)
                                        Math.cos(time * 0.18)
                        );

        int glowRadius =
                Math.round(
                        Math.min(width, height)
                                *
                                (
                                        0.55f
                                                + breathing * 0.08f
                                )
                );

        paintGlow(
                g2,
                glowX,
                glowY,
                glowRadius,
                8
        );

        paintGlow(
                g2,
                glowX,
                glowY,
                Math.round(glowRadius * 0.72f),
                5
        );

        // Secondary glow
        float glow2X =
                width *
                        (
                                0.18f
                                        + 0.10f
                                        * (float)
                                        Math.sin(time * 0.15)
                        );

        float glow2Y =
                height *
                        (
                                0.72f
                                        + 0.08f
                                        * (float)
                                        Math.cos(time * 0.20)
                        );

        paintGlow(
                g2,
                glow2X,
                glow2Y,
                Math.round(
                        Math.min(width, height) * 0.32f
                ),
                4
        );

        // Floating particles
        for (Particle particle : particles) {
            particle.paint(g2, width, height);
        }

        g2.dispose();
    }

    private void paintGlow(
            Graphics2D g2,
            float x,
            float y,
            int radius,
            int alpha
    ) {

        for (int i = 5; i >= 1; i--) {

            float scale = i / 5f;

            int r =
                    Math.round(radius * scale);

            int a =
                    Math.max(
                            1,
                            Math.round(
                                    alpha * (1f - scale)
                            )
                    );

            g2.setColor(
                    new Color(
                            216,
                            179,
                            106,
                            a
                    )
            );

            g2.fillOval(
                    Math.round(x - r),
                    Math.round(y - r),
                    r * 2,
                    r * 2
            );
        }
    }

    private class Particle {

        float x;
        float y;

        float speed;
        float drift;

        float size;

        float phase;

        float alpha;

        Particle() {
            reset(true);
        }

        void reset(boolean randomY) {

            x = random.nextFloat();

            y =
                    randomY
                            ? random.nextFloat()
                            : 1.05f;

            speed =
                    0.00015f
                            + random.nextFloat()
                            * 0.00035f;

            drift =
                    (
                            random.nextFloat()
                                    - 0.5f
                    )
                            * 0.00018f;

            size =
                    1.2f
                            + random.nextFloat()
                            * 2.2f;

            phase =
                    random.nextFloat()
                            * (float)
                            (Math.PI * 2);

            alpha =
                    20f
                            + random.nextFloat()
                            * 35f;
        }

        void update() {

            y -= speed;

            x += drift;

            if (y < -0.05f) {
                reset(false);
            }

            if (x < -0.05f) {
                x = 1.05f;
            }

            if (x > 1.05f) {
                x = -0.05f;
            }
        }

        void paint(
                Graphics2D g2,
                int width,
                int height
        ) {

            float pulse =
                    (float)
                            (
                                    (
                                            Math.sin(
                                                    time * 0.8f
                                                            + phase
                                            )
                                                    + 1.0
                                    )
                                            / 2.0
                            );

            int currentAlpha =
                    Math.round(
                            alpha *
                                    (
                                            0.45f
                                                    + pulse * 0.55f
                                    )
                    );

            float px = x * width;
            float py = y * height;

            int s =
                    Math.max(
                            1,
                            Math.round(size)
                    );

            g2.setColor(
                    new Color(
                            216,
                            179,
                            106,
                            currentAlpha
                    )
            );

            g2.fillOval(
                    Math.round(px - s / 2f),
                    Math.round(py - s / 2f),
                    s,
                    s
            );
        }
    }
}


/**
 * Small rounded gradient square used as the GuideIn logo.
 */
class BrandMark extends JComponent {

    private final String letter;

    BrandMark(String letter) {

        this.letter = letter;

        setPreferredSize(
                new Dimension(42, 42)
        );

        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {

        Graphics2D g2 =
                (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        g2.setPaint(
                new GradientPaint(
                        0,
                        0,
                        new Color(
                                0xE5,
                                0xC7,
                                0x7E
                        ),
                        getWidth(),
                        getHeight(),
                        new Color(
                                0x9D,
                                0x74,
                                0x36
                        )
                )
        );

        g2.fillRoundRect(
                0,
                0,
                getWidth() - 1,
                getHeight() - 1,
                12,
                12
        );

        g2.setFont(
                new Font(
                        "Georgia",
                        Font.BOLD,
                        20
                )
        );

        g2.setColor(
                new Color(
                        0x17,
                        0x12,
                        0x0A
                )
        );

        FontMetrics fm =
                g2.getFontMetrics();

        int x =
                (
                        getWidth()
                                - fm.stringWidth(letter)
                ) / 2;

        int y =
                (
                        getHeight()
                                - fm.getHeight()
                ) / 2
                        + fm.getAscent();

        g2.drawString(
                letter,
                x,
                y
        );

        g2.dispose();
    }
}


/**
 * Faint gold divider underneath the header.
 */
class GoldHairline extends JComponent {

    GoldHairline() {

        setPreferredSize(
                new Dimension(10, 1)
        );

        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {

        Graphics2D g2 =
                (Graphics2D) g.create();

        g2.setPaint(
                new GradientPaint(
                        0,
                        0,
                        new Color(
                                216,
                                179,
                                106,
                                70
                        ),
                        getWidth(),
                        0,
                        new Color(
                                216,
                                179,
                                106,
                                0
                        )
                )
        );

        g2.fillRect(
                0,
                0,
                getWidth(),
                1
        );

        g2.dispose();
    }
}


/**
 * JTextField with a custom placeholder.
 */
class PlaceholderTextField extends JTextField {

    private final String placeholder;

    PlaceholderTextField(String placeholder) {

        this.placeholder = placeholder;
    }

    @Override
    protected void paintComponent(Graphics g) {

        super.paintComponent(g);

        if (getText().isEmpty()) {

            Graphics2D g2 =
                    (Graphics2D) g.create();

            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            g2.setColor(
                    new Color(
                            255,
                            255,
                            255,
                            70
                    )
            );

            g2.setFont(getFont());

            FontMetrics fm =
                    g2.getFontMetrics();

            int x =
                    getInsets().left;

            int y =
                    (
                            getHeight()
                                    - fm.getHeight()
                    ) / 2
                            + fm.getAscent();

            g2.drawString(
                    placeholder,
                    x,
                    y
            );

            g2.dispose();
        }
    }
}