package fijilayouts;

import ij.*;
import ij.gui.*;
import ij.process.ByteProcessor;
import ij.process.ImageStatistics;
import ij.text.TextWindow;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Actual Analyze > Histogram with all three plugins: native controls and data-image boundaries. */
public final class NativeToolWindowTests {
    static int passed;
    static Object undo;
    static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
        passed++;
    }
    static Object invoke(String cls, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = Class.forName(cls).getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }
    static Object field(Object target, String name) throws Exception {
        Field f = HistogramWindow.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
    static Object history(ImagePlus imp) throws Exception {
        Method m = undo.getClass().getDeclaredMethod("view", int.class);
        m.setAccessible(true);
        return m.invoke(undo, imp.getID());
    }
    static List<?> shelf(String kind) throws Exception {
        return (List<?>) invoke("sidetoolbar.ShelfView", kind, new Class<?>[0]);
    }
    static void pause(int ms) throws Exception {
        Thread.sleep(ms);
        EventQueue.invokeAndWait(() -> {});
    }
    static void waitFor(BooleanSupplier test) throws Exception {
        for (int i = 0; i < 100; i++) { if (test.getAsBoolean()) return; pause(50); }
        throw new AssertionError("Timed out waiting for native tool update");
    }
    static void click(HistogramWindow window, String name) throws Exception {
        Button b = (Button) field(window, name);
        EventQueue.invokeAndWait(() -> window.actionPerformed(new ActionEvent(b, ActionEvent.ACTION_PERFORMED, b.getLabel())));
    }
    static void excluded(ImageWindow window) throws Exception {
        ImagePlus imp = window.getImagePlus();
        check(!ImageWindows.isDataWindow(window), "special window is not a data image");
        check(Engine.classify(window) == null && !Engine.visibleImages().contains(imp), "special window excluded from image layout");
        check(!shelf("images").contains(imp) && shelf("toolWindows").contains(window), "special window stays in tool shelf");
        check(history(imp) == null, "special window has no pixel undo history");
        ImageCanvas canvas = window.getCanvas();
        ComponentListener[] components = window.getComponentListeners();
        MouseWheelListener[] wheels = window.getMouseWheelListeners();
        Pan.install(window); Follow.install(window);
        check(SmoothCanvas.install(window) == null && window.getCanvas() == canvas, "native canvas is never replaced");
        check(Arrays.equals(components, window.getComponentListeners()) && Arrays.equals(wheels, window.getMouseWheelListeners()),
                "native resize and wheel listeners retained");
    }
    static class CustomToolWindow extends ImageWindow {
        CustomToolWindow(ImagePlus image) { super(image); }
    }
    public static void main(String[] args) throws Exception {
        try {
            new ImageJ(ImageJ.NO_SHOW);
            Prefs.set("fijilayouts.auto", true); Prefs.set("fijilayouts.follow", "fit");
            Prefs.set("undohistory.testMemoryMB", 64); Prefs.set("undohistory.useDisk", false);
            Prefs.set("undohistory.recordMacro", false);
            new WindowLayouts_().run("install");
            ((ij.plugin.PlugIn) Class.forName("undohistory.Undo_History").newInstance()).run("start");
            undo = invoke("undohistory.Engine", "get", new Class<?>[0]);
            ByteProcessor pixels = new ByteProcessor(256, 192);
            for (int i = 0; i < pixels.getPixelCount(); i++) pixels.set(i, i % 61);
            ImagePlus source = new ImagePlus("Histogram is also a valid image title", pixels);
            source.show(); pause(700);
            check(ImageWindows.isDataWindow(source.getWindow()) && Engine.visibleImages().contains(source), "ordinary image is managed regardless of title");
            check(history(source) != null && shelf("images").contains(source), "ordinary image retains history and shelf");
            Rectangle sourceBounds = source.getWindow().getBounds();
            IJ.run(source, "Histogram", "");
            check(WindowManager.getCurrentImage().getWindow() instanceof HistogramWindow, "menu command opens native HistogramWindow");
            HistogramWindow histogram = (HistogramWindow) WindowManager.getCurrentImage().getWindow();
            Dimension size = histogram.getSize();
            ImageCanvas canvas = histogram.getCanvas();
            Rectangle rect = new Rectangle(canvas.getSrcRect());
            double mag = canvas.getMagnification();
            pause(900);
            excluded(histogram);
            check(histogram.getSize().equals(size) && canvas.getMagnification() == mag && canvas.getSrcRect().equals(rect),
                    "automatic placement preserves native histogram view");
            check(source.getWindow().getBounds().equals(sourceBounds), "opening histogram does not retile source image");
            for (String name : new String[]{"list", "copy", "log", "live"}) {
                Button button = (Button) field(histogram, name);
                check(button.isShowing() && Arrays.asList(button.getActionListeners()).contains(histogram), "native " + name + " button stays visible and connected");
            }
            for (String mode : Follow.MODES) {
                EventQueue.invokeAndWait(() -> {
                    WindowManager.setCurrentWindow(histogram);
                    Follow.setMode(mode); Engine.applyLayout(2); Engine.refitActiveImage(); Engine.zoomTo(4);
                    Engine.applyBounds(histogram, new Rectangle(200, 180, 800, 700));
                });
                pause(400);
                check(histogram.getSize().equals(size) && histogram.getCanvas() == canvas && canvas.getMagnification() == mag && canvas.getSrcRect().equals(rect),
                        mode + " and explicit layout/snap/zoom retain native histogram size and view");
            }
            Layout saved = Engine.captureExact("Native tool test");
            check(saved.windows.containsKey(histogram.getTitle()), "saved arrangement records histogram as a tool");
            boolean imageSlot = false;
            for (ImageSlot slot : saved.images) imageSlot |= slot.title.equals(histogram.getImagePlus().getTitle());
            check(!imageSlot, "saved image slots exclude histogram");
            histogram.setLocation(histogram.getX() + 50, histogram.getY() + 50);
            EventQueue.invokeAndWait(() -> Engine.placeAll(saved)); pause(400);
            check(histogram.getSize().equals(size) && canvas.getMagnification() == mag, "restoring arrangement preserves native tool size and zoom");
            Object linearPixels = ((int[]) histogram.getImagePlus().getProcessor().getPixels()).clone();
            click(histogram, "log");
            check((Boolean) field(histogram, "logScale"), "Log control invokes native logarithmic rendering");
            check(!Arrays.equals((int[])linearPixels,(int[])histogram.getImagePlus().getProcessor().getPixels()), "Log control changes rendered histogram pixels");
            click(histogram, "log");
            check(!(Boolean) field(histogram, "logScale"), "Log toggles back to linear rendering");
            click(histogram, "list"); pause(300);
            boolean table = false;
            for (Window w : Window.getWindows()) if (w instanceof TextWindow && w.isVisible() && ((TextWindow) w).getTitle().equals(histogram.getTitle())) table = true;
            check(table && histogram.getResultsTable().size() == 256, "List opens native 256-bin table");
            click(histogram, "live");
            check(field(histogram, "srcImp") == source, "Live binds to original data image");
            pixels.invert(); source.updateAndDraw();
            waitFor(() -> {
                try { return ((ImageStatistics) field(histogram, "stats")).mean > 190; }
                catch (Exception ex) { return false; }
            });
            check(((ImageStatistics) field(histogram, "stats")).mean == source.getStatistics().mean, "Live recalculates statistics from changed source pixels");
            check(history(histogram.getImagePlus()) == null, "live redraws are not captured as image history");
            click(histogram, "live");
            int[] bins = histogram.getHistogram(); int sum = 0; for (int bin : bins) sum += bin;
            check(sum == pixels.getPixelCount(), "histogram counts still account for all source pixels");
            PlotWindow plot = new Plot("Native plot", "x", "y", new double[]{0, 1, 2}, new double[]{0, 1, 4}).show();
            CustomToolWindow custom = new CustomToolWindow(new ImagePlus("Custom image-backed tool", new ByteProcessor(100, 80)));
            pause(500); excluded(plot); excluded(custom);
            ImageStack stack = new ImageStack(64, 64); stack.addSlice(new ByteProcessor(64, 64)); stack.addSlice(new ByteProcessor(64, 64));
            ImagePlus dataStack = new ImagePlus("Ordinary stack", stack); dataStack.show(); pause(500);
            check(ImageWindows.isDataWindow(dataStack.getWindow()) && Engine.visibleImages().contains(dataStack), "ordinary stacks remain layout images");
            check(history(dataStack) != null && shelf("images").contains(dataStack), "ordinary stacks retain history and shelf");
            System.out.println(passed + " native tool regression checks passed");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
