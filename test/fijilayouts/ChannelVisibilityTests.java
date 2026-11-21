package fijilayouts;

import ij.CompositeImage;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.plugin.frame.Channels;
import ij.plugin.frame.Recorder;
import ij.process.ShortProcessor;

import java.awt.EventQueue;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Arrays;

public final class ChannelVisibilityTests {
    private static final int KEYS = InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK;

    public static void main(String[] args) {
        try {
            new ImageJ(ImageJ.NO_SHOW);
            Prefs.set("fijilayouts.auto", false);
            Prefs.set(ChannelVisibility.KEYS_PREF, true);
            Prefs.set(ChannelVisibility.SCROLL_PREF, true);
            new WindowLayouts_().run("install");
            CompositeImage image = composite("Channels test");
            CompositeImage other = composite("Other image");
            EventQueue.invokeAndWait(() -> {
                WindowManager.setCurrentWindow(image.getWindow());
                new Channels();
                new Recorder(false);
            });

            key(image, KeyEvent.VK_2, true);
            mask(image, true, false, true);
            mask(other, true, true, true);
            check(image.getChannel() == 1 && image.getSlice() == 2 && image.getFrame() == 1, "image position");
            check(Recorder.getInstance().getText().contains("Stack.setActiveChannels(\"101\");"), "native macro statement");
            key(image, KeyEvent.VK_2, false);
            mask(image, true, true, true);
            key(image, KeyEvent.VK_1, false);
            key(image, KeyEvent.VK_3, false);
            mask(image, false, true, false);
            key(image, KeyEvent.VK_0, false);
            mask(image, true, true, true);

            long time = System.currentTimeMillis();
            wheel(image, time, InputEvent.META_DOWN_MASK, -0.4);
            mask(image, true, true, true);
            wheel(image, time + 20, InputEvent.META_DOWN_MASK, -0.4);
            mask(image, true, false, false);
            wheel(image, time + 40, InputEvent.META_DOWN_MASK, -2);
            wheel(image, time + 80, 0, -2);
            mask(image, true, false, false);
            wheel(image, time + 400, InputEvent.META_DOWN_MASK, -2);
            mask(image, false, true, false);
            wheel(image, time + 620, InputEvent.META_DOWN_MASK, 2);
            mask(image, true, false, false);
            wheel(image, time + 840, InputEvent.META_DOWN_MASK, 2);
            mask(image, true, true, true);
            check(image.getSlice() == 2 && image.getChannel() == 1 && image.getFrame() == 1, "gesture preserves image position");
            for (int i = 1; i <= image.getStackSize(); i++)
                check(image.getStack().getProcessor(i).get(0, 0) == i * 1000, "original pixels");
            check(!image.changes, "display alone leaves image unmodified");

            EventQueue.invokeAndWait(() -> {
                Prefs.set(ChannelVisibility.KEYS_PREF, false);
                check(!Engine.onKey(new KeyEvent(image.getCanvas(), KeyEvent.KEY_PRESSED, time, KEYS, KeyEvent.VK_1, '1')), "key opt-out");
                Prefs.set(ChannelVisibility.KEYS_PREF, true);
                image.setMode(CompositeImage.GRAYSCALE);
                check(!Engine.onKey(new KeyEvent(image.getCanvas(), KeyEvent.KEY_PRESSED, time, KEYS, KeyEvent.VK_1, '1')), "grayscale ignored");
            });
            System.out.println("PASS: composite keys, real wheel dispatch, repeat suppression, momentum, image scope, Recorder and pixel preservation");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static CompositeImage composite(String name) throws Exception {
        ImageStack stack = new ImageStack(48, 40);
        for (int i = 1; i <= 6; i++) {
            ShortProcessor pixels = new ShortProcessor(48, 40);
            pixels.set(0, 0, i * 1000);
            stack.addSlice(pixels);
        }
        ImagePlus source = new ImagePlus(name, stack);
        source.setDimensions(3, 2, 1);
        CompositeImage composite = new CompositeImage(source, CompositeImage.COMPOSITE);
        EventQueue.invokeAndWait(() -> {
            composite.show();
            composite.setPosition(1, 2, 1);
        });
        return composite;
    }

    private static void key(CompositeImage image, int code, boolean repeat) throws Exception {
        EventQueue.invokeAndWait(() -> {
            ImageCanvas canvas = image.getCanvas();
            long now = System.currentTimeMillis();
            check(Engine.onKey(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, now, KEYS, code, KeyEvent.CHAR_UNDEFINED)), "handled key");
            if (repeat) check(Engine.onKey(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, now + 10, KEYS, code, KeyEvent.CHAR_UNDEFINED)), "handled repeat");
            check(Engine.onKey(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, now + 20, KEYS, code, KeyEvent.CHAR_UNDEFINED)), "handled release");
        });
        EventQueue.invokeAndWait(() -> {});
    }

    private static void wheel(CompositeImage image, long time, int modifiers, double rotation) throws Exception {
        EventQueue.invokeAndWait(() -> {
            ImageCanvas canvas = image.getCanvas();
            canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseWheelEvent.MOUSE_WHEEL, time, modifiers,
                    8, 8, 8, 8, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (int) rotation, rotation));
        });
    }

    private static void mask(CompositeImage image, boolean... expected) {
        check(Arrays.equals(Arrays.copyOf(image.getActiveChannels(), expected.length), expected),
                "expected visibility " + Arrays.toString(expected));
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
    }
}
