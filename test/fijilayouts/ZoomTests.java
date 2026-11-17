package fijilayouts;

import ij.ImagePlus;
import ij.ImageStack;
import ij.CompositeImage;
import ij.gui.ImageCanvas;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import java.awt.Rectangle;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Exercises the real ImageJ canvas, including repaint after the old handover deadlines. */
public final class ZoomTests {
    static int passed;
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); passed++; }
    static int[] render(ImageCanvas canvas) {
        BufferedImage frame = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = frame.createGraphics(); canvas.paint(g); g.dispose();
        return frame.getRGB(0, 0, frame.getWidth(), frame.getHeight(), null, 0, frame.getWidth());
    }
    static void view(ImageCanvas c, Rectangle r, double mag, int w, int h) {
        c.setSize(w, h); c.setSourceRect(r); c.setMagnification(mag);
    }
    public static void main(String[] args) throws Exception {
        ByteProcessor pixels = new ByteProcessor(2796, 2796);
        for (int y = 0; y < 2796; y++) for (int x = 0; x < 2796; x++) pixels.set(x, y, (x / 17 + y / 19) % 2 * 255);
        ImagePlus image = new ImagePlus("zoom regression", pixels);
        ImageCanvas c = new SmoothCanvas(image);
        SmoothCanvas z = (SmoothCanvas)c;
        view(c, new Rectangle(0, 0, 2796, 2796), 400.0 / 2796, 400, 400);
        double start = c.getMagnification();
        z.zoom(Math.exp(0.04 * 4.3), 191, 133);
        check(c.getMagnification() > start, "continuous zoom applied immediately");
        check(c.getWidth() == 400 && c.getHeight() == 400, "fitted canvas stays fixed");
        Rectangle r = new Rectangle(c.getSrcRect()); double mag = c.getMagnification(); int[] moving = render(c);
        for (int pause : new int[] {170, 300, 500}) {
            Thread.sleep(pause);
            java.awt.EventQueue.invokeAndWait(() -> {});
            check(c.getSrcRect().equals(r) && c.getMagnification() == mag, "no delayed source/zoom change after " + pause);
            check(Arrays.equals(moving, render(c)), "same native frame after " + pause);
        }
        for (double factor : new double[] {1.003, 1.02, 1.2, 0.98, 1.1}) {
            z.zoom(factor, 191, 133);
            double anchorX = c.getSrcRect().x + 191 / c.getMagnification();
            double expectedX = r.x + 191 / mag;
            check(Math.abs(anchorX - expectedX) <= 1.1, "cursor anchor within one native image pixel");
        }
        z.zoom(1e20, 191, 133);
        check(c.getMagnification() == 32, "maximum zoom respected");
        int[] high = render(c); Thread.sleep(190);
        check(Arrays.equals(high, render(c)), "no high-magnification final jump");
        z.zoom(1e-20, 191, 133);
        check(Math.abs(c.getMagnification() - start) < 1e-12, "zoom out stops at original fitted area");
        check(c.getSrcRect().equals(new Rectangle(0, 0, 2796, 2796)), "whole image restored at minimum zoom");
        view(c, new Rectangle(400, 500, 400, 400), 1, 400, 400);
        z.zoom(2, 200, 100);
        check(c.getSrcRect().x == 500 && c.getSrcRect().y == 550, "external menu zoom resets stale anchor");
        c.getSrcRect().x = 800; c.getSrcRect().y = 900;
        z.zoom(2, 200, 100);
        check(c.getSrcRect().x == 850 && c.getSrcRect().y == 925, "pan resets stale anchor");
        view(c, new Rectangle(0, 0, 2796, 2796), 800.0 / 2796, 800, 800);
        z.zoom(2, 400, 400);
        check(c.getWidth() == 800 && c.getSrcRect().x == 699, "resize resets anchor without resizing again");
        z.zoom(1e20, 800, 800);
        for (int i = 0; i < 12; i++) z.zoom(0.9, 800, 800);
        Rectangle edge = c.getSrcRect();
        check(edge.x >= 0 && edge.y >= 0 && edge.x + edge.width <= 2796 && edge.y + edge.height <= 2796,
                "edge zooms stay inside image");
        r = new Rectangle(edge); mag = c.getMagnification();
        z.zoom(Double.NaN, 0, 0); z.zoom(0, 0, 0);
        check(c.getSrcRect().equals(r) && c.getMagnification() == mag, "invalid factors ignored");
        ImageStack stack = new ImageStack(2796, 2796);
        for (int i = 0; i < 3; i++) stack.addSlice(pixels.duplicate());
        ImagePlus base = new ImagePlus("three channels", stack); base.setDimensions(3, 1, 1);
        CompositeImage composite = new CompositeImage(base, CompositeImage.COMPOSITE);
        ImageCanvas cc = new SmoothCanvas(composite);
        view(cc, new Rectangle(0, 0, 2796, 2796), start, 400, 400);
        ((SmoothCanvas)cc).zoom(2.173, 171, 249);
        int[] color = render(cc); Thread.sleep(180);
        check(Arrays.equals(color, render(cc)), "three-channel composite repaint stable");
        check(composite.getNChannels() == 3, "channel structure preserved");
        System.out.println(passed + " zoom regression checks passed");
        System.exit(0);
    }
}
