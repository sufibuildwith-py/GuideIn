package gui;

import javax.swing.*;
import java.awt.*;

/** A JPanel painted as a rounded rectangle, with optional gradient fill and border. */
class RoundedPanel extends JPanel {
    private final int arc;
    private Color fillTop;
    private Color fillBottom;
    private Color borderColor;
    private float borderWidth = 1f;

    RoundedPanel(int arc) {
        this.arc = arc;
        setOpaque(false);
    }

    void setFill(Color solid) {
        this.fillTop = solid;
        this.fillBottom = solid;
    }

    void setFillGradient(Color top, Color bottom) {
        this.fillTop = top;
        this.fillBottom = bottom;
    }

    void setBorderColor(Color c, float width) {
        this.borderColor = c;
        this.borderWidth = width;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        if (fillTop != null) {
            if (fillBottom != null && !fillBottom.equals(fillTop)) {
                g2.setPaint(new GradientPaint(0, 0, fillTop, w, h, fillBottom));
            } else {
                g2.setColor(fillTop);
            }
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
        }
        if (borderColor != null) {
            g2.setColor(borderColor);
            g2.setStroke(new BasicStroke(borderWidth));
            g2.drawRoundRect(0, 0, w - 2, h - 2, arc, arc);
        }
        g2.dispose();
        super.paintComponent(g);
    }
}
