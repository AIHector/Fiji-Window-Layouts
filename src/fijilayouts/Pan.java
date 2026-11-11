package fijilayouts;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.StackWindow;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/**
 * Two-finger panning on a trackpad (or a mouse with a tilt wheel): an image larger than its window moves in both
 * directions, smoothly, like a map. Everything else stays ImageJ's: zooming with Ctrl or Shift held, horizontal
 * scrolling with the space bar held, and changing slices in a stack. It sits in front of ImageJ's own handler.
 */
final class Pan implements MouseWheelListener {

	/** Screen points the image moves per unit of wheel rotation (a trackpad sends fractions of a unit). */
	static final double POINTS_PER_UNIT = 12;

	final ImageWindow win;
	final MouseWheelListener imagej;
	private double restX, restY; // movement smaller than one image pixel, kept for the next event

	Pan(ImageWindow win, MouseWheelListener imagej) {
		this.win = win;
		this.imagej = imagej;
	}

	static boolean enabled() { return Prefs.get("fijilayouts.trackpadpan", true); }

	/** Puts a Pan in front of ImageJ's wheel handler on an image window (once). */
	static void install(ImageWindow w) {
		for (MouseWheelListener l : w.getMouseWheelListeners()) if (l instanceof Pan) return;
		for (MouseWheelListener l : w.getMouseWheelListeners()) {
			if (l == w) { // ImageJ's ImageWindow and StackWindow handle the wheel themselves
				w.removeMouseWheelListener(l);
				w.addMouseWheelListener(new Pan(w, l));
				return;
			}
		}
	}

	public void mouseWheelMoved(MouseWheelEvent e) {
		if (!handle(e)) imagej.mouseWheelMoved(e);
	}

	static boolean smoothZoom() { return Prefs.get("fijilayouts.smoothzoom", true); }

	/** In a stack, whether two fingers pan a zoomed image (Option + two fingers then change the slice). */
	static boolean stackPan() { return Prefs.get("fijilayouts.stackpan", true); }

	/** How strongly scrolling zooms: the magnification changes by e^(ZOOM_RATE x rotation). */
	static final double ZOOM_RATE = 0.04;
	static final double MAX_MAG = 32;
	/** Scroll events closer together than this after a zoom are the trackpad's momentum, not a new pan. */
	static final long ZOOM_TAIL_MS = 150;
	/** After this pause in zooming, the view is handed to ImageJ (its own zoom, source rectangle and redraw). */
	static final int ZOOM_COMMIT_MS = 160;

	private long lastZoomAt;
	/** The zoom in progress: magnification and view origin in image pixels, unrounded. */
	private double viewMag, viewX, viewY;
	private boolean zooming;
	/** The source rectangle and magnification this class handed to ImageJ last (if they changed since, start afresh). */
	private Rectangle lastSrc;
	private double lastMag;
	private javax.swing.Timer commitTimer;

	/** Pans (or, with Ctrl, zooms) the image for this event; false leaves the event to ImageJ. */
	boolean handle(MouseWheelEvent e) {
		boolean ctrl = (e.getModifiers() & InputEvent.CTRL_MASK) != 0;
		long now = System.currentTimeMillis();
		if (ctrl && smoothZoom() && !IJ.shiftKeyDown()) {
			lastZoomAt = now;
			return zoom(e);
		}
		// the trackpad's momentum keeps sending scroll events after a zoom, also once Ctrl is released:
		// they belong to the zoom, so they must not pan the image away (the stream ends with a short pause)
		if (now - lastZoomAt < ZOOM_TAIL_MS) {
			lastZoomAt = now;
			return true;
		}
		if (!enabled()) return false;
		// Ctrl or Shift (the key) zoom, the space bar scrolls sideways: ImageJ's own behaviour
		if (ctrl || IJ.shiftKeyDown() || IJ.spaceBarDown()) return false;
		ImageCanvas ic = win.getCanvas();
		ImagePlus imp = win.getImagePlus();
		if (ic == null || imp == null) return false;
		// Java reports sideways scrolling as the wheel with Shift: here Shift is not held, so it is the trackpad
		boolean sideways = e.isShiftDown();
		Rectangle src = ic.getSrcRect();
		int iw = imp.getWidth(), ih = imp.getHeight();
		if (!sideways && win instanceof StackWindow && imp.getStackSize() > 1) {
			// in a stack, up and down change the slice (or channel) as ImageJ users expect, except while the image
			// is zoomed in and can move up or down: then two fingers pan, and Option + two fingers change the slice
			boolean option = e.isAltDown();
			if (option || !stackPan() || src.height >= ih) return false;
		}
		boolean canMove = sideways ? src.width < iw : src.height < ih;
		// the whole image is visible along this direction: nothing to move (and nothing for ImageJ to do either
		// when the whole image is visible both ways)
		if (!canMove) return src.width < iw || src.height < ih;
		double pixels = e.getPreciseWheelRotation() * POINTS_PER_UNIT / ic.getMagnification();
		if (sideways) {
			restX += pixels;
			int dx = (int) restX;
			restX -= dx;
			if (dx == 0) return true;
			src.x = Math.max(0, Math.min(iw - src.width, src.x + dx));
		} else {
			restY += pixels;
			int dy = (int) restY;
			restY -= dy;
			if (dy == 0) return true;
			src.y = Math.max(0, Math.min(ih - src.height, src.y + dy));
		}
		ic.setSourceRect(src);
		ic.repaint();
		return true;
	}

	/**
	 * Smooth zoom around the cursor. ImageJ can place a zoomed view only at whole image pixels, which makes the image
	 * hop at every step; so while the fingers move, this class draws the image itself at the exact (sub-pixel) view,
	 * and hands the final view to ImageJ when they stop. The window never changes size: a fitted image grows into the
	 * window's empty space, and zooming out stops where the whole image fits in the window.
	 */
	boolean zoom(MouseWheelEvent e) {
		if (e.isShiftDown()) return true; // sideways movement while zooming: ignored
		// the same direction as ImageJ's own Ctrl + scroll zoom
		java.awt.Point pw = javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), win);
		return zoomAt(Math.exp(-e.getPreciseWheelRotation() * ZOOM_RATE), pw);
	}

	/** Zooms by a factor around a point given in the window's coordinates (Ctrl + scroll). */
	boolean zoomAt(double factor, java.awt.Point pw) {
		ImageCanvas ic = win.getCanvas();
		ImagePlus imp = win.getImagePlus();
		if (ic == null || imp == null || win instanceof ij.gui.PlotWindow) return false;
		int iw = imp.getWidth(), ih = imp.getHeight(), cw = ic.getWidth(), ch = ic.getHeight();
		if (cw <= 0 || ch <= 0) return true;
		// the space inside the window's border: a fitted image leaves some of it empty, and grows into it
		java.awt.Dimension border = Engine.chrome(win);
		int aw = Math.max(cw, win.getWidth() - border.width), ah = Math.max(ch, win.getHeight() - border.height);
		if (!zooming) {
			Rectangle src = ic.getSrcRect();
			double mag = ic.getMagnification();
			// continue from the unrounded view of the last zoom if nothing else changed the view since
			if (lastSrc == null || !src.equals(lastSrc) || mag != lastMag) { viewX = src.x; viewY = src.y; viewMag = mag; }
			zooming = true;
		}
		// zooming out stops where the whole image fits in the window; with "Fill the Window", where the image still
		// covers the window (fill means no empty space: switch to Fit, Cmd+Option+X, to see the whole image)
		double minMag = Follow.mode().equals("fill") ? Math.max(aw / (double) iw, ah / (double) ih)
			: Math.min(aw / (double) iw, ah / (double) ih);
		double next = viewMag * factor;
		next = Math.max(Math.min(minMag, viewMag), Math.min(MAX_MAG, next));
		// the pointer in window coordinates: the canvas may move inside the window when its size changes
		java.awt.Point at = ic.getLocation();
		double px = Math.max(0, Math.min(cw, pw.x - at.x)), py = Math.max(0, Math.min(ch, pw.y - at.y));
		// the image point under the cursor stays under it
		double ox = viewX + px / viewMag, oy = viewY + py / viewMag;
		viewMag = next;
		// the canvas: the image at this zoom, no larger than the window's inner space
		int nw = (int) Math.min(aw, Math.floor(iw * next)), nh = (int) Math.min(ah, Math.floor(ih * next));
		if (nw != cw || nh != ch) {
			ic.setSize(Math.max(nw, 1), Math.max(nh, 1));
			win.validate();
			win.repaint(); // the part of the window the canvas no longer covers
			cw = ic.getWidth();
			ch = ic.getHeight();
			at = ic.getLocation();
			px = Math.max(0, Math.min(cw, pw.x - at.x));
			py = Math.max(0, Math.min(ch, pw.y - at.y));
		}
		viewX = clamp(ox - px / next, iw - cw / next);
		viewY = clamp(oy - py / next, ih - ch / next);
		drawPreview(ic, imp);
		if (commitTimer == null) {
			commitTimer = new javax.swing.Timer(ZOOM_COMMIT_MS, ev -> commit());
			commitTimer.setRepeats(false);
		}
		commitTimer.restart();
		return true;
	}

	/** Keeps a view origin inside the image (or centred when the image is smaller than the view). */
	static double clamp(double v, double max) {
		return max <= 0 ? max / 2 : Math.max(0, Math.min(max, v));
	}

	/** Draws the image at the exact view, straight onto the canvas (ImageJ redraws it properly at the commit). */
	void drawPreview(ImageCanvas ic, ImagePlus imp) {
		java.awt.Image img = imp.getImage();
		java.awt.Graphics g = ic.getGraphics();
		if (img == null || g == null) return;
		try {
			java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
			boolean smooth = viewMag < 1 || Prefs.interpolateScaledImages && viewMag < 4;
			g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, smooth
				? java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR : java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
			at.scale(viewMag, viewMag);
			at.translate(-viewX, -viewY);
			g2.drawImage(img, at, null);
		} finally {
			g.dispose();
		}
	}

	/** Hands the view to ImageJ: its magnification, the nearest whole-pixel source rectangle, and a proper redraw. */
	void commit() {
		zooming = false;
		ImageCanvas ic = win.getCanvas();
		ImagePlus imp = win.getImagePlus();
		if (ic == null || imp == null || !win.isVisible()) return;
		int iw = imp.getWidth(), ih = imp.getHeight(), cw = ic.getWidth(), ch = ic.getHeight();
		int sw = (int) Math.min(iw, Math.round(cw / viewMag)), sh = (int) Math.min(ih, Math.round(ch / viewMag));
		int sx = (int) Math.max(0, Math.min(iw - sw, Math.round(viewX)));
		int sy = (int) Math.max(0, Math.min(ih - sh, Math.round(viewY)));
		ic.setSourceRect(new Rectangle(sx, sy, sw, sh)); // the canvas keeps its size: ImageJ derives about this zoom
		ic.setMagnification(viewMag);
		ic.repaint();
		lastSrc = new Rectangle(ic.getSrcRect());
		lastMag = ic.getMagnification();
		Follow.noteZoom(win);
	}
}
