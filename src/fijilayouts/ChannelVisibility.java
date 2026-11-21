package fijilayouts;

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.plugin.frame.Channels;
import ij.plugin.frame.Recorder;

import java.awt.Point;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import javax.swing.SwingUtilities;

/** Changes the Composite view using the same visibility mask as the Channels tool. */
final class ChannelVisibility {
    static final String KEYS_PREF = "fijilayouts.channelKeys";
    static final String SCROLL_PREF = "fijilayouts.channelScroll";
    private static final long GESTURE_GAP_MS = 180;
    private static final double MIN_ROTATION = 0.75;

    private long lastScroll;
    private double rotation;
    private boolean inGesture, applied;

    static int shortcutChannel(KeyEvent event) {
        if (!Prefs.get(KEYS_PREF, true) || !IJ.isMacOSX() || !event.isMetaDown()
                || !event.isControlDown() || event.isAltDown() || event.isShiftDown()) return -1;
        int key = event.getKeyCode();
        if (key >= KeyEvent.VK_0 && key <= KeyEvent.VK_8) return key - KeyEvent.VK_0;
        if (key >= KeyEvent.VK_NUMPAD0 && key <= KeyEvent.VK_NUMPAD8) return key - KeyEvent.VK_NUMPAD0;
        return -1;
    }

    static CompositeImage shortcutTarget(KeyEvent event) {
        Window origin = event.getComponent() instanceof Window ? (Window) event.getComponent()
                : SwingUtilities.getWindowAncestor(event.getComponent());
        ImagePlus active = WindowManager.getCurrentImage();
        if (!supports(active) || (origin != active.getWindow() && !(origin instanceof Channels)
                && origin != IJ.getInstance())) return null;
        return (CompositeImage) active;
    }

    static boolean supports(ImagePlus image) {
        return image instanceof CompositeImage && image.getCompositeMode() == CompositeImage.COMPOSITE
                && ImageWindows.isDataWindow(image.getWindow()) && image.getNChannels() > 1;
    }

    static void toggle(CompositeImage image, int channel) {
        boolean[] active = image.getActiveChannels();
        int count = Math.min(image.getNChannels(), active.length);
        if (channel < 0 || channel > count) return;
        if (channel == 0) show(image, 0);
        else {
            active[channel - 1] = !active[channel - 1];
            refresh(image);
        }
    }

    private static void show(CompositeImage image, int solo) {
        boolean[] active = image.getActiveChannels();
        boolean changed = false;
        for (int i = 0; i < Math.min(image.getNChannels(), active.length); i++) {
            boolean visible = solo == 0 || i == solo - 1;
            changed |= active[i] != visible;
            active[i] = visible;
        }
        if (changed) refresh(image);
    }

    static void cycle(CompositeImage image, int direction) {
        boolean[] active = image.getActiveChannels();
        int count = Math.min(image.getNChannels(), active.length);
        int solo = 0;
        for (int i = 0; i < count; i++) {
            if (!active[i]) continue;
            if (solo != 0) { solo = 0; break; } // A mixed view starts at the all-channel position.
            solo = i + 1;
        }
        show(image, Math.floorMod(solo + direction, count + 1));
    }

    private static void refresh(CompositeImage image) {
        if (IJ.recording()) {
            StringBuilder mask = new StringBuilder();
            boolean[] active = image.getActiveChannels();
            for (int i = 0; i < Math.min(image.getNChannels(), active.length); i++)
                mask.append(active[i] ? '1' : '0');
            if (Recorder.scriptMode()) Recorder.recordCall("imp.setActiveChannels(\"" + mask + "\");");
            else Recorder.record("Stack.setActiveChannels", mask.toString());
        }
        image.updateAndDraw();
        Channels.updateChannels();
    }

    boolean scroll(ImageWindow window, MouseWheelEvent event) {
        if (!event.isMetaDown() && !inGesture) return false;
        long when = event.getWhen();
        ImagePlus image = window.getImagePlus();
        if (!IJ.isMacOSX() || !Prefs.get(SCROLL_PREF, true) || !supports(image)
                || image != WindowManager.getCurrentImage() || event.isControlDown()
                || event.isAltDown() || event.isShiftDown() || IJ.spaceBarDown()) {
            reset();
            return false;
        }
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), window.getCanvas());
        if (!window.getCanvas().contains(point)) {
            reset();
            return false;
        }
        if (inGesture && (when < lastScroll || when - lastScroll >= GESTURE_GAP_MS)) reset();
        if (!event.isMetaDown()) {
            if (!inGesture) return false;
            lastScroll = when;
            return true; // Discard momentum after Command is released.
        }
        inGesture = true;
        lastScroll = when;
        if (!applied) {
            rotation += event.getPreciseWheelRotation();
            if (Math.abs(rotation) >= MIN_ROTATION) {
                applied = true;
                cycle((CompositeImage) image, rotation < 0 ? 1 : -1);
            }
        }
        return true;
    }

    private void reset() {
        inGesture = false;
        applied = false;
        rotation = 0;
    }
}
