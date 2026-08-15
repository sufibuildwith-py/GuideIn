import gui.GuideInFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class MainGUI {

    public static void main(String[] args) {
        // Use the cross-platform L&F so the custom dark/gold theme renders
        // consistently instead of the OS-native look painting over it.
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to whatever the default L&F is.
        }

        SwingUtilities.invokeLater(() -> {
            GuideInFrame frame = new GuideInFrame();
            frame.setVisible(true);
        });
    }
}
