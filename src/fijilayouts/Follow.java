package fijilayouts;

import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import javax.swing.SwingUtilities;

/**
 * What an image does when you resize its window by dragging an edge or a corner:
 * "keep" (ImageJ's behaviour: the zoom stays, more or less of the image shows),
 * "fill" (the image scales so that it fills the window; pan to see the parts that do not fit), or
 * "fit" (the image scales so that all of it shows: ImageJ's own Scale to Fit), or
 * "shrink" (as "fit" when resizing; placements fit the whole image and shrink the window around it).
 */
final class Follow extends ComponentAdapter {

	static final String[] MODES = {"keep", "fill", "fit", "shrink"};

	static String mode() {
		String m = Prefs.get("fijilayouts.follow", "keep");
		for (String ok : MODES) if (ok.equals(m)) return m;
		return "keep";
	}

	static void setMode(String m) {
		Prefs.set("fijilayouts.follow", m);
		Engine.persist();
		applyAll();
	}

	/** Watches an image window's size (once per window) and applies the current mode to it. */
	static void install(ImageWindow w) {
		Follow f = null;
		for (ComponentListener l : w.getComponentListeners()) if (l instanceof Follow) f = (Follow) l;
		if (f == null) {
			f = new Follow(w);
			w.addComponentListener(f);
		}
		f.applyMode();
	}

	static void applyAll() {
		int[] ids = WindowManager.getIDList();
		if (ids == null) return;
		for (int id : ids) {
			ImagePlus imp = WindowManager.getImage(id);
			if (imp != null && imp.getWindow() != null) install(imp.getWindow());
		}
	}

	final ImageWindow win;
	/** The zoom after the last resize: a resize that comes with a new zoom is ImageJ zooming, not the user dragging. */
	private double lastMag;
	private long quietUntil;

	Follow(ImageWindow win) {
		this.win = win;
		ImageCanvas c = win.getCanvas();
		lastMag = c != null ? c.getMagnification() : -1;
	}

	/** Called after a zoom that does not resize the window (smooth zoom), so the next resize counts as a drag. */
	static void noteZoom(ImageWindow w) {
		ImageCanvas c = w.getCanvas();
		if (c == null) return;
		for (ComponentListener l : w.getComponentListeners()) if (l instanceof Follow) ((Follow) l).lastMag = c.getMagnification();
	}

	void applyMode() {
		ImageCanvas c = win.getCanvas();
		if (c == null) return;
		c.setScaleToFit(mode().equals("fit") || mode().equals("shrink"));
		lastMag = c.getMagnification();
	}

	public void componentResized(ComponentEvent e) {
		if (mode().equals("fill")) SwingUtilities.invokeLater(this::fill);
	}

	/** Zooms the image so that it fills the window, keeping the top-left corner of the visible part. */
	void fill() {
		ImageCanvas c = win.getCanvas();
		ImagePlus imp = win.getImagePlus();
		if (c == null || imp == null || !win.isVisible()) return;
		double mag = c.getMagnification();
		// ImageJ zooming (keys, menu, pinch) resizes the window around the new zoom: leave that alone
		// (it may resize several times for one zoom: the resizes in the next moments are left alone too)
		long now = System.currentTimeMillis();
		if (Math.abs(mag - lastMag) > 1e-9) { lastMag = mag; quietUntil = now + 400; return; }
		if (now < quietUntil) return;
		// the space inside the window's border (ImageJ keeps the canvas no larger than the image at the old zoom)
		java.awt.Dimension border = Engine.chrome(win);
		int cw = win.getWidth() - border.width, ch = win.getHeight() - border.height, iw = imp.getWidth(), ih = imp.getHeight();
		if (cw <= 0 || ch <= 0) return;
		double next = Math.max(cw / (double) iw, ch / (double) ih);
		// the visible part keeps its top-left corner (a placement shows the image from its top-left corner)
		Rectangle src = c.getSrcRect();
		int sw = Math.max(1, Math.min(iw, (int) Math.round(cw / next))), sh = Math.max(1, Math.min(ih, (int) Math.round(ch / next)));
		int sx = Math.max(0, Math.min(iw - sw, src.x));
		int sy = Math.max(0, Math.min(ih - sh, src.y));
		if (Math.abs(next - mag) < 1e-9 && src.equals(new Rectangle(sx, sy, sw, sh))) return;
		Engine.releaseAlt(); // ImageJ's layout would fit the image instead while it thinks Option is down
		// this order matters: ImageJ's setSourceRect derives the zoom from the canvas's current size
		c.setSize(cw, ch);
		c.setSourceRect(new Rectangle(sx, sy, sw, sh));
		c.setMagnification(next);
		win.validate(); // ImageJ's layout now gives the canvas the whole space
		c.repaint();
		lastMag = c.getMagnification();
		Engine.debug("follow fill " + Engine.titleOf(win) + " win " + win.getBounds() + " mag " + lastMag);
	}
}
