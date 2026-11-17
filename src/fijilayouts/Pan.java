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

	private long lastZoomAt;
	private final ZoomView zoomView = new ZoomView();

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

	/** Continuous magnification using ImageJ's renderer for both moving and resting views. */
	boolean zoom(MouseWheelEvent e) {
		if (e.isShiftDown()) return true;
		ImageCanvas ic = win.getCanvas();
		ImagePlus imp = win.getImagePlus();
		if (ic == null || imp == null || win instanceof ij.gui.PlotWindow) return false;
		java.awt.Point p = javax.swing.SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), ic);
		zoomView.zoom(ic, imp, Math.exp(-e.getPreciseWheelRotation() * ZOOM_RATE), p.x, p.y);
		Follow.noteZoom(win);
		return true;
	}

	/** Keeps a view origin inside the image (or centred when the image is smaller than the view). */
	static double clamp(double v, double max) {
		return max <= 0 ? max / 2 : Math.max(0, Math.min(max, v));
	}

	/**
	 * Retains fractional cursor anchoring between wheel events, but publishes a native ImageJ view on every event.
	 * There is deliberately no preview, timer, delayed rounding, canvas resize or end-of-gesture animation.
	 * Native drawing also keeps selections, overlays and the zoom indicator visible and aligned throughout.
	 */
	static final class ZoomView {
		private double mag, x, y;
		private Rectangle lastSrc;
		private double lastMag;
		private int lastWidth, lastHeight, lastImageWidth, lastImageHeight;

		void zoom(ImageCanvas ic, ImagePlus imp, double factor, double px, double py) {
			int iw = imp.getWidth(), ih = imp.getHeight(), cw = ic.getWidth(), ch = ic.getHeight();
			if (cw <= 0 || ch <= 0 || !Double.isFinite(factor) || factor <= 0) return;
			Rectangle src = ic.getSrcRect();
			// Panning, a menu zoom, a layout or image-size change invalidates the previous fractional anchor.
			if (lastSrc == null || !src.equals(lastSrc) || ic.getMagnification() != lastMag
					|| cw != lastWidth || ch != lastHeight || iw != lastImageWidth || ih != lastImageHeight) {
				x = src.x; y = src.y; mag = ic.getMagnification();
			}
			double minimum = Math.max(cw / (double) iw, ch / (double) ih);
			double next = Math.max(Math.min(minimum, mag), Math.min(MAX_MAG, mag * factor));
			px = Math.max(0, Math.min(cw, px)); py = Math.max(0, Math.min(ch, py));
			x = clamp(x + px / mag - px / next, iw - cw / next);
			y = clamp(y + py / mag - py / next, ih - ch / next);
			mag = next;
			// Cover the canvas, including its final fractional image pixel. Never resize its fitted area.
			int sw = Math.max(1, (int) Math.min(iw, Math.ceil(cw / mag)));
			int sh = Math.max(1, (int) Math.min(ih, Math.ceil(ch / mag)));
			int sx = (int) Math.max(0, Math.min(iw - sw, Math.round(x)));
			int sy = (int) Math.max(0, Math.min(ih - sh, Math.round(y)));
			ic.setSourceRect(new Rectangle(sx, sy, sw, sh));
			ic.setMagnification(mag); // setSourceRect derives a different zoom; restore the intended continuous zoom
			lastSrc = new Rectangle(ic.getSrcRect()); lastMag = ic.getMagnification();
			lastWidth = cw; lastHeight = ch; lastImageWidth = iw; lastImageHeight = ih;
			ic.repaint();
		}
	}
}
