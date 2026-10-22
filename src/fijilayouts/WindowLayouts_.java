package fijilayouts;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.HTMLDialog;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.PlotWindow;
import ij.text.TextWindow;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Fiji Window Layouts: numbered desktop layouts for Fiji's windows, Magnet-style snap zones,
 * and user-composed custom layouts. See README.md for the full description.
 *
 * Menu entry point: Plugins > Window Layouts. The argument selects the command.
 */
public class WindowLayouts_ implements ij.plugin.PlugIn {

	@Override
	public void run(String arg) {
		Engine.install();
		if (arg == null) arg = "";
		if (arg.matches("[0-9]")) {
			final int n = Integer.parseInt(arg);
			SwingUtilities.invokeLater(new Runnable() { public void run() { Engine.applyLayout(n); } });
		} else if (arg.startsWith("snap:")) {
			final String zone = arg.substring(5);
			SwingUtilities.invokeLater(new Runnable() { public void run() { Engine.snap(zone); } });
		} else if (arg.equals("install")) {
			// installed above
		} else if (arg.equals("toggle")) {
			Engine.toggleAuto();
		} else if (arg.equals("reset")) {
			Engine.resetDialog();
		} else if (arg.equals("save")) {
			Engine.saveArrangementDialog();
		} else if (arg.equals("edit")) {
			Engine.editLayoutDialog();
		} else if (arg.equals("prefs")) {
			Engine.prefsDialog();
		} else if (arg.equals("help")) {
			Engine.showHelp();
		} else if (arg.equals("snapdialog")) {
			Engine.snapDialog();
		} else if (arg.equals("rearrange")) {
			SwingUtilities.invokeLater(new Runnable() { public void run() { Engine.applyLayout(Engine.activeLayout()); } });
		}
	}
}

/** Window groups that a layout can place. */
enum Group {
	TOOLBAR("toolbar"), IMAGES("images"), BC("b&c"), ROI("roi"), THRESHOLD("threshold"),
	CHANNELS("channels"), RESULTS("results"), LOG("log"), CONSOLE("console"), SNT("snt");

	final String key;
	Group(String key) { this.key = key; }

	static Group byKey(String k) {
		for (Group g : values()) if (g.key.equalsIgnoreCase(k.trim())) return g;
		return null;
	}
}

/** A rectangle given as fractions (0..1) of a display's work area. */
final class Zone {
	static final int MAX_DISPLAYS = 9;
	final double x, y, w, h;
	final int display; // 0 = the display holding the ImageJ toolbar, 1.. = the other displays, left to right
	Zone(double x, double y, double w, double h, int display) {
		this.x = x; this.y = y; this.w = w; this.h = h; this.display = display;
	}
	String serialize() {
		return fmt(x * 100) + "," + fmt(y * 100) + "," + fmt(w * 100) + "," + fmt(h * 100) + (display > 0 ? "@" + (display + 1) : "");
	}
	static String fmt(double v) { return String.valueOf(Math.round(v * 10) / 10.0); }
	/** Three decimals of a percent: a hundredth of a pixel on a 1512-pixel screen, so a saved window comes back exactly. */
	static String fmt3(double v) { return String.valueOf(Math.round(v * 1000) / 1000.0); }
	String serializeExact() {
		return fmt3(x * 100) + "," + fmt3(y * 100) + "," + fmt3(w * 100) + "," + fmt3(h * 100) + (display > 0 ? "@" + (display + 1) : "");
	}

	/** Like parse, but a saved window may hang off its work area (over the toolbar band, partly off screen). */
	static Zone parseExact(String s) {
		s = s.trim();
		int display = 0;
		int at = s.indexOf('@');
		if (at >= 0) {
			display = Integer.parseInt(s.substring(at + 1).trim()) - 1;
			s = s.substring(0, at);
		}
		String[] p = s.split(",");
		if (p.length != 4) throw new IllegalArgumentException("need four numbers x,y,w,h");
		double[] v = new double[4];
		for (int i = 0; i < 4; i++) v[i] = Double.parseDouble(p[i].trim());
		if (v[2] <= 0 || v[3] <= 0 || display < 0 || display >= MAX_DISPLAYS) throw new IllegalArgumentException("bad window zone");
		return new Zone(v[0] / 100.0, v[1] / 100.0, v[2] / 100.0, v[3] / 100.0, display);
	}
	/** Same rectangle on the same display (tools that share a slot, like B&C and Threshold). */
	boolean sameAs(Zone o) {
		return o != null && display == o.display && Math.abs(x - o.x) < 1e-9 && Math.abs(y - o.y) < 1e-9
			&& Math.abs(w - o.w) < 1e-9 && Math.abs(h - o.h) < 1e-9;
	}

	/** Parses "x,y,w,h" or "x,y,w,h@N" (percent of the work area, N = display 1..9). Returns null if blank, throws if malformed. */
	static Zone parse(String s) {
		if (s == null) return null;
		s = s.trim();
		if (s.isEmpty()) return null;
		int display = 0;
		int at = s.indexOf('@');
		if (at >= 0) {
			try {
				display = Integer.parseInt(s.substring(at + 1).trim()) - 1;
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("display after @ must be a number from 1 to " + MAX_DISPLAYS);
			}
			s = s.substring(0, at);
		}
		String[] p = s.split(",");
		if (p.length != 4) throw new IllegalArgumentException("need four numbers x,y,w,h");
		double[] v = new double[4];
		for (int i = 0; i < 4; i++) {
			try {
				v[i] = Double.parseDouble(p[i].trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("\"" + p[i].trim() + "\" is not a number (use a dot for decimals)");
			}
		}
		if (display < 0 || display >= MAX_DISPLAYS) throw new IllegalArgumentException("display after @ must be from 1 to " + MAX_DISPLAYS);
		if (v[2] <= 0 || v[3] <= 0) throw new IllegalArgumentException("width and height must be above 0");
		if (v[0] < 0 || v[1] < 0 || v[0] + v[2] > 100.05 || v[1] + v[3] > 100.05)
			throw new IllegalArgumentException("zone must stay inside 0..100 (x+w and y+h at most 100)");
		return new Zone(v[0] / 100.0, v[1] / 100.0, v[2] / 100.0, v[3] / 100.0, display);
	}
}

/** An image window saved exactly: its place, and its zoom when the same image comes back. */
final class ImageSlot {
	final Zone zone;
	final double mag;       // magnification, 0 = unknown
	final double sx, sy;    // top-left of the visible part, as fractions of the image's width and height
	final int iw, ih;       // size of the image that was saved
	final String title;     // the image's title: the same image goes back to its own window
	ImageSlot(Zone zone, double mag, double sx, double sy, int iw, int ih, String title) {
		this.zone = zone; this.mag = mag; this.sx = sx; this.sy = sy; this.iw = iw; this.ih = ih;
		this.title = title == null ? "" : title;
	}
	String serialize() {
		return zone.serializeExact() + "|" + Math.round(mag * 1e6) / 1e6 + "|" + Zone.fmt3(sx) + "," + Zone.fmt3(sy) + "|" + iw + "x" + ih
			+ "|" + Engine.escape(title).replace("|", "%7C");
	}
	static ImageSlot parse(String s) {
		String[] p = s.split("\\|");
		Zone z = Zone.parseExact(p[0]);
		if (p.length < 4) return new ImageSlot(z, 0, 0, 0, 0, 0, "");
		String[] o = p[2].split(","), d = p[3].split("x");
		String title = p.length > 4 ? Engine.unescape(p[4].replace("%7C", "|")) : "";
		return new ImageSlot(z, Double.parseDouble(p[1]), Double.parseDouble(o[0]), Double.parseDouble(o[1]),
			Integer.parseInt(d[0].trim()), Integer.parseInt(d[1].trim()), title);
	}
}

final class Layout {
	final String name;
	final Map<Group, Zone> zones = new EnumMap<Group, Zone>(Group.class);
	/** Saved exactly ("Save Current Arrangement"): image windows in reading order, other windows by title. */
	final List<ImageSlot> images = new ArrayList<ImageSlot>();
	final Map<String, Zone> windows = new LinkedHashMap<String, Zone>();
	Layout(String name) { this.name = name; }

	boolean isEmpty() { return zones.isEmpty() && images.isEmpty() && windows.isEmpty(); }

	/** Zone given in percent of the work area of display 1 (below the toolbar). */
	Layout z(Group g, double x, double y, double w, double h) {
		zones.put(g, new Zone(x / 100.0, y / 100.0, w / 100.0, h / 100.0, 0));
		return this;
	}
	/** Zone given in percent of the full usable area of display 2. */
	Layout s(Group g, double x, double y, double w, double h) {
		zones.put(g, new Zone(x / 100.0, y / 100.0, w / 100.0, h / 100.0, 1));
		return this;
	}
}

final class Engine {
	static final int GAP = 0; // windows touch: no space between them
	static final int CASCADE = 24;
	static final int DELAY_MS = 250;
	static final String INSTALLED_KEY = "fijilayouts.installed";
	/** ImageJ's own zoom levels from 1/32 to 8x, so Zoom In/Out keeps working from where a layout leaves an image. */
	static final double[] ZOOMS = {1 / 32.0, 1 / 24.0, 1 / 16.0, 1 / 12.0, 1 / 8.0, 1 / 6.0, 1 / 4.0, 1 / 3.0, 1 / 2.0, 0.75, 1, 1.5, 2, 3, 4, 6, 8};
	static final String[] MODIFIERS = {"cmd+alt", "ctrl+alt", "ctrl+shift", "alt+shift"};

	static final Layout[] BUILTIN = new Layout[6];
	static {
		// zones in percent of the work area (below the toolbar); neighbouring zones share their edges
		BUILTIN[0] = new Layout("Layout 1")
			.z(Group.IMAGES, 0, 0, 58, 100).z(Group.ROI, 58, 0, 18, 33).z(Group.SNT, 58, 0, 18, 33).z(Group.BC, 76, 0, 11, 33)
			.z(Group.THRESHOLD, 87, 0, 13, 33).z(Group.CHANNELS, 87, 0, 13, 33)
			.z(Group.RESULTS, 58, 33, 42, 37).z(Group.LOG, 58, 70, 42, 30);
		BUILTIN[1] = new Layout("Layout 2")
			.z(Group.IMAGES, 0, 0, 72, 100).z(Group.BC, 72, 0, 11, 40).z(Group.THRESHOLD, 72, 0, 11, 40).z(Group.CHANNELS, 72, 0, 11, 40)
			.z(Group.ROI, 83, 0, 17, 40).z(Group.SNT, 83, 0, 17, 40)
			.z(Group.RESULTS, 72, 40, 28, 34).z(Group.LOG, 72, 74, 28, 26);
		BUILTIN[2] = new Layout("Layout 3")
			.z(Group.IMAGES, 0, 0, 100, 60).z(Group.RESULTS, 0, 60, 45, 40).z(Group.ROI, 45, 60, 19, 40).z(Group.SNT, 45, 60, 19, 40)
			.z(Group.BC, 64, 60, 16, 40).z(Group.THRESHOLD, 64, 60, 16, 40).z(Group.CHANNELS, 64, 60, 16, 40)
			.z(Group.LOG, 80, 60, 20, 40);
		BUILTIN[3] = new Layout("Layout 4")
			.z(Group.IMAGES, 0, 0, 82, 78).z(Group.RESULTS, 0, 78, 82, 22)
			.z(Group.BC, 82, 0, 18, 34).z(Group.THRESHOLD, 82, 0, 18, 34).z(Group.CHANNELS, 82, 0, 18, 34)
			.z(Group.ROI, 82, 34, 18, 34).z(Group.SNT, 82, 34, 18, 34).z(Group.LOG, 82, 68, 18, 32);
		BUILTIN[4] = new Layout("Layout 5")
			.z(Group.IMAGES, 0, 0, 60, 100).z(Group.SNT, 60, 0, 40, 33).z(Group.ROI, 60, 33, 20, 34)
			.z(Group.BC, 80, 33, 20, 34).z(Group.THRESHOLD, 80, 33, 20, 34).z(Group.CHANNELS, 80, 33, 20, 34)
			.z(Group.RESULTS, 60, 67, 40, 17).z(Group.LOG, 60, 84, 40, 16);
		BUILTIN[5] = new Layout("Layout 6")
			.z(Group.IMAGES, 0, 0, 100, 100)
			.s(Group.BC, 0, 0, 20, 40).s(Group.THRESHOLD, 0, 0, 20, 40).s(Group.CHANNELS, 0, 0, 20, 40)
			.s(Group.ROI, 20, 0, 30, 40).s(Group.SNT, 50, 0, 50, 40)
			.s(Group.RESULTS, 0, 40, 100, 35).s(Group.LOG, 0, 75, 100, 25);
	}

	private static final WeakHashMap<Window, Rectangle> previousBounds = new WeakHashMap<Window, Rectangle>();
	private static final Set<Window> pending = new LinkedHashSet<Window>();
	private static Timer timer;

	// ---------------------------------------------------------------- preferences / layouts

	static boolean autoEnabled() { return Prefs.get("fijilayouts.auto", true); }
	static int activeLayout() { return (int) Prefs.get("fijilayouts.active", 1); }
	static boolean debugEnabled() { return Prefs.get("fijilayouts.debug", false); }
	/** How images meet their area: "fill" (no empty space; edges may be cropped), "fit" (whole image), "steps" (whole image, standard zooms). */
	static final String[] IMAGE_ZOOMS = {"fill", "fit", "steps"};
	static String imageZoom() {
		String m = Prefs.get("fijilayouts.imagezoom", "fill");
		for (String ok : IMAGE_ZOOMS) if (ok.equals(m)) return m;
		return "fill";
	}
	static boolean stretchToolbar() { return Prefs.get("fijilayouts.toolbarwide", true); }

	/** The zone of a kind of window in a layout. Fiji's Console uses the Log's zone unless it has its own. */
	static Zone zoneOf(Layout l, Group g) {
		if (g == null) return null;
		Zone z = l.zones.get(g);
		return z == null && g == Group.CONSOLE ? l.zones.get(Group.LOG) : z;
	}
	static String modifier() {
		String m = Prefs.get("fijilayouts.mod", IJ.isMacOSX() ? "cmd+alt" : "ctrl+alt");
		for (String ok : MODIFIERS) if (ok.equals(m)) return m;
		return MODIFIERS[0];
	}

	/** Writes IJ_Prefs.txt now, so settings survive a crash or a forced quit. */
	static void persist() {
		try {
			Prefs.savePreferences();
		} catch (Throwable t) {
			debug("ERROR saving preferences: " + t);
		}
	}

	static void toggleAuto() {
		final boolean now = !autoEnabled();
		Prefs.set("fijilayouts.auto", now);
		persist();
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				// turning it on tidies the windows that opened while it was off
				Layout l = effective(activeLayout());
				if (now && l != null) placeAll(l);
				IJ.showStatus("Window Layouts: automatic placement " + (now ? "ON" : "OFF"));
			}
		});
	}

	static final int SLOTS = 10; // layouts 0..9

	/** The layout in slot n: what you saved there, else the built-in design (slots 1-6), else null (empty). */
	static Layout layout(int n) {
		if (n < 0 || n >= SLOTS) return null;
		Layout saved = parseCustom(Prefs.get("fijilayouts.name." + n, defaultName(n)), Prefs.get("fijilayouts.custom." + n, ""));
		if (saved != null) return saved;
		return builtin(n);
	}

	static Layout builtin(int n) { return n >= 1 && n <= 6 ? BUILTIN[n - 1] : null; }

	static String defaultName(int n) { return builtin(n) != null ? builtin(n).name : "Layout " + n; }

	/** Removes what you saved in slot n: slots 1-6 return to their built-in design, the others become empty. */
	static void resetSlot(int n) {
		Prefs.set("fijilayouts.custom." + n, "");
		Prefs.set("fijilayouts.name." + n, "");
		persist();
	}

	/**
	 * Parses a stored custom layout: "group=x,y,w,h" zones, plus windows saved exactly, "img=x,y,w,h|zoom|sx,sy|WxH"
	 * (image windows, in order) and "win:Title=x,y,w,h" (any other window, by title). Separated by ";".
	 * Returns null when nothing valid is stored.
	 */
	static Layout parseCustom(String name, String s) {
		if (s == null || s.trim().isEmpty()) return null;
		Layout l = new Layout(name);
		for (String part : s.split(";")) {
			int eq = part.indexOf('=');
			if (eq < 0) continue;
			String key = part.substring(0, eq).trim(), value = part.substring(eq + 1);
			try {
				if (key.equals("img")) { l.images.add(ImageSlot.parse(value)); continue; }
				if (key.startsWith("win:")) { l.windows.put(unescape(key.substring(4)), Zone.parseExact(value)); continue; }
				Group g = Group.byKey(key);
				if (g == null || g == Group.TOOLBAR) continue;
				Zone z = Zone.parse(value);
				if (z != null) l.zones.put(g, z);
			} catch (RuntimeException ignored) { }
		}
		return l.isEmpty() ? null : l;
	}

	/** Window titles may contain the separators. */
	static String escape(String t) { return t.replace("%", "%25").replace(";", "%3B").replace("=", "%3D"); }
	static String unescape(String t) { return t.replace("%3D", "=").replace("%3B", ";").replace("%25", "%"); }

	/** The exact part of a layout, in its stored form ("" when it has none). */
	static String serializeExact(Layout l) {
		if (l == null) return "";
		StringBuilder sb = new StringBuilder();
		for (ImageSlot i : l.images) sb.append(";img=").append(i.serialize());
		for (Map.Entry<String, Zone> e : l.windows.entrySet())
			sb.append(";win:").append(escape(e.getKey())).append('=').append(e.getValue().serializeExact());
		return sb.toString();
	}

	static String serializeCustom(Map<Group, Zone> zones) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Group, Zone> e : zones.entrySet()) {
			if (sb.length() > 0) sb.append(';');
			sb.append(e.getKey().key).append('=').append(e.getValue().serialize());
		}
		return sb.toString();
	}

	static void storeCustom(int n, String name, Map<Group, Zone> zones) { storeCustom(n, name, zones, ""); }

	/** exact: windows saved exactly, as serializeExact writes them. */
	static void storeCustom(int n, String name, Map<Group, Zone> zones, String exact) {
		String s = serializeCustom(zones) + exact;
		Prefs.set("fijilayouts.custom." + n, s.startsWith(";") ? s.substring(1) : s);
		Prefs.set("fijilayouts.name." + n, name);
		persist();
	}

	// ---------------------------------------------------------------- screens

	static GraphicsDevice[] devices() {
		return GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
	}

	static GraphicsDevice primaryDevice() {
		Frame ij = IJ.getInstance();
		if (ij != null && ij.getGraphicsConfiguration() != null) return ij.getGraphicsConfiguration().getDevice();
		return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
	}

	/** Display 1 is the toolbar's display; the others follow left to right (then top to bottom). */
	static List<GraphicsDevice> orderedDevices() {
		final GraphicsDevice p = primaryDevice();
		List<GraphicsDevice> others = new ArrayList<GraphicsDevice>();
		for (GraphicsDevice d : devices()) if (d != p) others.add(d);
		Collections.sort(others, new Comparator<GraphicsDevice>() {
			public int compare(GraphicsDevice a, GraphicsDevice b) {
				return compareBounds(a.getDefaultConfiguration().getBounds(), b.getDefaultConfiguration().getBounds());
			}
		});
		others.add(0, p);
		return others;
	}

	static int compareBounds(Rectangle a, Rectangle b) {
		if (a.x != b.x) return a.x < b.x ? -1 : 1;
		return a.y < b.y ? -1 : (a.y == b.y ? 0 : 1);
	}

	/** The device for a display index; a missing display falls back to the toolbar's display. */
	static GraphicsDevice deviceFor(int display) {
		List<GraphicsDevice> ds = orderedDevices();
		return display >= 0 && display < ds.size() ? ds.get(display) : ds.get(0);
	}

	static int displayIndex(GraphicsDevice d) {
		int i = orderedDevices().indexOf(d);
		return Math.max(i, 0);
	}

	static Rectangle usable(GraphicsDevice d) {
		GraphicsConfiguration gc = d.getDefaultConfiguration();
		Rectangle b = gc.getBounds();
		Insets i = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		return withoutSideToolbar(new Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom));
	}

	/** The rectangle the Side Toolbar plugin occupies (it publishes it as a system property), or null. */
	static Rectangle sideToolbar() {
		Object o = System.getProperties().get("sidetoolbar.reserved");
		return o instanceof Rectangle ? (Rectangle) o : null;
	}

	/** Every band the Side Toolbar plugin takes (its toolbar, side columns, bars at the bottom or top). */
	@SuppressWarnings("unchecked")
	static List<Rectangle> sideToolbarBands() {
		List<Rectangle> l = new ArrayList<Rectangle>();
		Object all = System.getProperties().get("sidetoolbar.reservedAll");
		if (all instanceof List) for (Object o : (List<Object>) all) if (o instanceof Rectangle) l.add((Rectangle) o);
		Rectangle one = sideToolbar();
		if (l.isEmpty() && one != null) l.add(one);
		return l;
	}

	/**
	 * Removes the bands the Side Toolbar takes (its toolbar, side columns, bars at the bottom or top) from the area:
	 * a tall band from the side it is nearer to, a wide band from the top or the bottom.
	 */
	static Rectangle withoutSideToolbar(Rectangle u) {
		int left = u.x, right = u.x + u.width, top = u.y, bottom = u.y + u.height;
		for (Rectangle s : sideToolbarBands()) {
			if (!s.intersects(u)) continue;
			boolean tall = s.height >= u.height / 2;
			if (tall) {
				if (s.x + s.width / 2 < u.x + u.width / 2) left = Math.max(left, s.x + s.width);
				else right = Math.min(right, s.x);
			} else {
				if (s.y + s.height / 2 < u.y + u.height / 2) top = Math.max(top, s.y + s.height);
				else bottom = Math.min(bottom, s.y);
			}
		}
		return new Rectangle(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
	}

	/**
	 * Usable area of a device, minus the toolbar band when the ImageJ toolbar lives on it. With the Side Toolbar,
	 * the ImageJ window is a status strip that plugin places at the top or the bottom; the band is cut from that side.
	 */
	static Rectangle workArea(GraphicsDevice d) {
		Rectangle u = usable(d);
		Frame ij = IJ.getInstance();
		if (ij != null && d == primaryDevice()) {
			int band = ij.getHeight() + GAP;
			if (sideToolbar() != null) {
				// the Side Toolbar placed the ImageJ window as a strip: remove it only where it covers the area
				Rectangle ib = ij.getBounds();
				if (!ib.intersects(u)) return u;
				if (ib.y + ib.height / 2 < u.y + u.height / 2) {
					int t = Math.min(u.y + u.height, ib.y + ib.height + GAP);
					return new Rectangle(u.x, t, u.width, u.y + u.height - t);
				}
				return new Rectangle(u.x, u.y, u.width, Math.max(0, ib.y - u.y));
			}
			return new Rectangle(u.x, u.y + band, u.width, u.height - band);
		}
		return u;
	}

	static Rectangle workArea(int display) { return workArea(deviceFor(display)); }

	static Rectangle toRect(Zone z) { return toRect(z, workArea(z.display)); }

	/** Edges are rounded, not sizes, so zones that touch in percent also touch in pixels (no 1 px slivers). */
	static Rectangle toRect(Zone z, Rectangle wa) {
		int x0 = (int) Math.round(z.x * wa.width), x1 = (int) Math.round((z.x + z.w) * wa.width);
		int y0 = (int) Math.round(z.y * wa.height), y1 = (int) Math.round((z.y + z.h) * wa.height);
		return new Rectangle(wa.x + x0, wa.y + y0, x1 - x0, y1 - y0);
	}

	// ---------------------------------------------------------------- classification

	static String titleOf(Window w) {
		if (w instanceof Frame) return ((Frame) w).getTitle();
		if (w instanceof Dialog) return ((Dialog) w).getTitle();
		return null;
	}

	static Group classify(Window w) {
		if (w == null || !w.isVisible()) return null;
		if (w instanceof ImageWindow) return Group.IMAGES;
		if (w == IJ.getInstance()) return Group.TOOLBAR;
		return classifyTitle(titleOf(w), w instanceof TextWindow);
	}

	static Group classifyTitle(String t, boolean textWindow) {
		if (t == null) return null;
		if (t.equals("B&C") || t.equals("W&L")) return Group.BC;
		if (t.equals("ROI Manager")) return Group.ROI;
		if (t.equals("Threshold") || t.equals("Threshold Color")) return Group.THRESHOLD;
		if (t.equals("Channels")) return Group.CHANNELS;
		if (t.equals("Log")) return Group.LOG;
		if (t.equals("Console")) return Group.CONSOLE;
		// SNT's main window is titled "SNT v<version>" (e.g. "SNT v5.0.21"); its startup prompt is not managed
		if (t.equals("SNT") || t.matches("SNT v?\\d.*") || t.equals("Path Manager")
				|| t.equals("Fill Manager") || t.startsWith("Neuroanatomy Shortcuts"))
			return Group.SNT;
		if (t.equals("Results") || t.equals("Summary") || textWindow) return Group.RESULTS;
		return null;
	}

	static boolean isResizable(Window w) {
		if (w instanceof Frame) return ((Frame) w).isResizable();
		if (w instanceof Dialog) return ((Dialog) w).isResizable();
		return false;
	}

	/** Groups whose windows are resized to fill their zone; the others keep their own size and are only moved. */
	static boolean resizableGroup(Group g) {
		return g == Group.RESULTS || g == Group.LOG || g == Group.CONSOLE || g == Group.ROI;
	}

	// ---------------------------------------------------------------- applying a layout

	/** The layout that slot n really applies: layout 6 behaves like layout 2 on a single display. */
	static Layout effective(int n) {
		return fallsBack(n, devices().length) ? BUILTIN[1] : layout(n);
	}

	/** The built-in layout 6 (tools on a second display) behaves like layout 2 on one display. */
	static boolean fallsBack(int n, int displays) { return n == 6 && displays < 2 && layout(6) == BUILTIN[5]; }

	static void applyLayout(int n) {
		Layout l = effective(n);
		if (l == null) {
			IJ.showStatus("Window Layouts: slot " + n + " is empty (use Save Current Arrangement or Edit Layout)");
			return;
		}
		Prefs.set("fijilayouts.active", n);
		persist();
		placeAll(l);
		IJ.showStatus("Window Layouts: " + (fallsBack(n, devices().length) ? "Layout 6 needs a second display, using Layout 2" : l.name));
	}

	static void placeAll(Layout l) {
		Frame ij = IJ.getInstance();
		if (ij != null && sideToolbar() == null) { // with the Side Toolbar, that plugin places the ImageJ window
			Rectangle u = usable(primaryDevice());
			if (stretchToolbar()) ij.setBounds(u.x, u.y, u.width, ij.getHeight());
			else ij.setLocation(u.x, u.y);
		}
		arrangeImages(l);
		for (Window w : Window.getWindows()) {
			if (placeExact(w, l)) continue;
			Group g = classify(w);
			if (g != null && g != Group.IMAGES && g != Group.TOOLBAR) placeTool(w, g, l);
		}
		settle(l);
	}

	/** A window the layout saved exactly (by title) goes back to its saved place and size. Returns false if not saved. */
	static boolean placeExact(Window w, Layout l) {
		if (l.windows.isEmpty() || !w.isVisible() || w instanceof ImageWindow || w == IJ.getInstance()) return false;
		String t = titleOf(w);
		Zone z = t == null ? null : l.windows.get(t);
		if (z == null) return false;
		Rectangle r = toRect(z);
		if (isResizable(w)) w.setBounds(r);
		else w.setLocation(r.x, r.y);
		return true;
	}

	static boolean savedExactly(Window w, Layout l) {
		String t = titleOf(w);
		return t != null && l.windows.containsKey(t) && !(w instanceof ImageWindow);
	}

	/**
	 * Fixed-size tool windows (B&C, Threshold...) keep their own size and can be taller than their zone, covering
	 * the top of a resizable window below (Results, Log, ROI Manager). Such a resizable window gives up height
	 * from its top so that it starts just below, unless it would become shorter than its minimum; then it stays.
	 * Fixed windows are never moved here, so the result does not depend on the order windows were opened.
	 */
	static void settle(Layout l) {
		List<Window> tools = new ArrayList<Window>();
		for (Window w : Window.getWindows()) {
			Group g = classify(w);
			if (g != null && g != Group.IMAGES && g != Group.TOOLBAR && zoneOf(l, g) != null && !savedExactly(w, l)) tools.add(w);
		}
		for (Window w : tools) {
			Group g = classify(w);
			if (!resizableGroup(g) || !isResizable(w)) continue;
			Rectangle r = w.getBounds();
			Zone zw = zoneOf(l, g);
			int top = r.y;
			for (Window o : tools) {
				if (o == w || zw.sameAs(zoneOf(l, classify(o)))) continue; // same slot: cascaded on purpose
				Rectangle a = o.getBounds();
				if (a.y < r.y && a.intersects(r)) top = Math.max(top, a.y + a.height + GAP);
			}
			int bottom = r.y + r.height;
			if (top > r.y && bottom - top >= minHeight(w, g)) w.setBounds(r.x, top, r.width, bottom - top);
		}
	}

	static void placeTool(Window w, Group g, Layout l) {
		Zone z = zoneOf(l, g);
		if (z == null) return;
		Rectangle r = toRect(z);
		Point p = freeSpot(w, r.x, r.y);
		int shift = p.x - r.x;
		if (resizableGroup(g) && isResizable(w)) {
			w.setBounds(p.x, p.y, Math.max(r.width - shift, 120), Math.max(r.height - shift, minHeight(w, g)));
		} else {
			Rectangle screen = usable(deviceFor(z.display));
			int x = Math.min(p.x, screen.x + screen.width - w.getWidth());
			int y = Math.min(p.y, screen.y + screen.height - w.getHeight());
			w.setLocation(Math.max(x, screen.x), Math.max(y, screen.y));
		}
	}

	/** Resizable windows are never made shorter than this: the ROI Manager needs its natural height for its buttons. */
	static int minHeight(Window w, Group g) {
		if (g == Group.ROI) return 220;
		return 80;
	}

	/** When another managed tool window already sits at (x,y), cascade down-right so both stay visible. */
	static Point freeSpot(Window self, int x, int y) {
		for (int i = 0; i < 8; i++) {
			boolean taken = false;
			for (Window o : Window.getWindows()) {
				if (o == self || o instanceof ImageWindow || classify(o) == null || classify(o) == Group.TOOLBAR) continue;
				if (Math.abs(o.getX() - x) < 4 && Math.abs(o.getY() - y) < 4) { taken = true; break; }
			}
			if (!taken) break;
			x += CASCADE; y += CASCADE;
		}
		return new Point(x, y);
	}

	static List<ImagePlus> visibleImages() {
		List<ImagePlus> imps = new ArrayList<ImagePlus>();
		int[] ids = WindowManager.getIDList();
		if (ids == null) return imps;
		for (int id : ids) {
			ImagePlus imp = WindowManager.getImage(id);
			if (imp != null && imp.getWindow() != null && imp.getWindow().isVisible()) imps.add(imp);
		}
		return imps;
	}

	/** Grid cells for n images inside an area: as square a grid as possible, filled row by row; 3 images in 3 columns. */
	static Rectangle[] grid(Rectangle area, int n) {
		Rectangle[] cells = new Rectangle[n];
		if (n == 0) return cells;
		int cols = n == 3 ? 3 : (int) Math.ceil(Math.sqrt(n)); // three images side by side
		int rows = (int) Math.ceil(n / (double) cols);
		int cw = area.width / cols, ch = area.height / rows;
		for (int k = 0; k < n; k++) cells[k] = new Rectangle(area.x + (k % cols) * cw, area.y + (k / cols) * ch, cw, ch);
		return cells;
	}

	static void arrangeImages(Layout l) {
		if (!l.images.isEmpty()) { arrangeImagesExact(l); return; }
		Zone z = l.zones.get(Group.IMAGES);
		if (z == null) return;
		List<ImagePlus> imps = visibleImages();
		Rectangle[] cells = grid(toRect(z), imps.size());
		for (int k = 0; k < cells.length; k++) {
			ImagePlus imp = imps.get(k);
			fitImage(imp.getWindow(), imp, cells[k]);
		}
	}

	/**
	 * An image that was open when the layout was saved goes back to its own window (matched by title). The other
	 * images take the remaining saved windows in the order they were opened, top-left window first. An image of the
	 * size that was saved gets back its exact zoom and visible part; another image is zoomed to its window as the
	 * "Image zoom" preference says. Images beyond the saved windows cascade from the last one.
	 */
	static void arrangeImagesExact(Layout l) {
		List<ImagePlus> imps = visibleImages();
		int[] slotOf = assignSlots(l.images, imps);
		int extra = 0;
		for (int k = 0; k < imps.size(); k++) {
			ImagePlus imp = imps.get(k);
			ImageWindow win = imp.getWindow();
			if (slotOf[k] >= 0) {
				ImageSlot slot = l.images.get(slotOf[k]);
				Rectangle r = toRect(slot.zone);
				if (!restoreView(win, imp, slot, r)) fitImage(win, imp, r);
			} else {
				Rectangle r = toRect(l.images.get(l.images.size() - 1).zone);
				int off = CASCADE * ++extra;
				fitImage(win, imp, new Rectangle(r.x + off, r.y + off, r.width, r.height));
			}
		}
	}

	/** For each open image (in opening order), the saved window it goes to, or -1 if none is left. */
	static int[] assignSlots(List<ImageSlot> slots, List<ImagePlus> imps) {
		String[] titles = new String[imps.size()];
		for (int k = 0; k < titles.length; k++) titles[k] = imps.get(k).getTitle();
		return assignSlots(slots, titles);
	}

	static int[] assignSlots(List<ImageSlot> slots, String[] titles) {
		int[] out = new int[titles.length];
		boolean[] used = new boolean[slots.size()];
		java.util.Arrays.fill(out, -1);
		for (int k = 0; k < titles.length; k++) // the same image: its own window
			for (int i = 0; i < slots.size(); i++)
				if (!used[i] && !slots.get(i).title.isEmpty() && slots.get(i).title.equals(titles[k])) { out[k] = i; used[i] = true; break; }
		int next = 0;
		for (int k = 0; k < titles.length; k++) { // the others: the free windows, top-left first
			if (out[k] >= 0) continue;
			while (next < used.length && used[next]) next++;
			if (next < used.length) { out[k] = next; used[next] = true; }
		}
		return out;
	}

	/** Puts back the saved zoom and visible part when the image has the saved size. */
	static boolean restoreView(ImageWindow win, ImagePlus imp, ImageSlot slot, Rectangle r) {
		ImageCanvas cv = win.getCanvas();
		if (cv == null || slot.mag <= 0 || imp.getWidth() != slot.iw || imp.getHeight() != slot.ih || win instanceof PlotWindow)
			return false;
		int chromeW = win.getWidth() - cv.getWidth(), chromeH = win.getHeight() - cv.getHeight();
		int cw = Math.max(r.width - chromeW, 32), ch = Math.max(r.height - chromeH, 32);
		int iw = imp.getWidth(), ih = imp.getHeight();
		int sw = Math.min(iw, (int) Math.round(cw / slot.mag)), sh = Math.min(ih, (int) Math.round(ch / slot.mag));
		int sx = Math.max(0, Math.min(iw - sw, (int) Math.round(slot.sx * iw)));
		int sy = Math.max(0, Math.min(ih - sh, (int) Math.round(slot.sy * ih)));
		cv.setSourceRect(new Rectangle(sx, sy, sw, sh));
		cv.setMagnification(slot.mag);
		cv.setSize(Math.min(cw, (int) Math.floor(iw * slot.mag)), Math.min(ch, (int) Math.floor(ih * slot.mag)));
		win.pack();
		win.setBounds(r.x, r.y, win.getWidth(), win.getHeight());
		imp.updateAndRepaintWindow();
		return true;
	}

	/** Largest standard magnification not above fit. */
	static double zoomFor(double fit) {
		double mag = ZOOMS[0];
		for (double zoom : ZOOMS) if (zoom <= fit) mag = zoom;
		return mag;
	}

	/** Zoom an image window to the largest standard magnification that fits the cell, and place it. */
	static void fitImage(ImageWindow win, ImagePlus imp, Rectangle cell) {
		ImageCanvas cv = win.getCanvas();
		if (cv == null || imp == null) {
			// special viewers (for example the 3D viewer) manage their own size: only move them
			win.setLocation(cell.x, cell.y);
			return;
		}
		int chromeW = win.getWidth() - cv.getWidth();
		int chromeH = win.getHeight() - cv.getHeight();
		if (win instanceof PlotWindow) {
			// a plot is redrawn at the size of its cell instead of being zoomed
			ij.gui.Plot plot = ((PlotWindow) win).getPlot();
			if (plot != null) {
				plot.setSize(Math.max(cell.width - chromeW, 200), Math.max(cell.height - chromeH, 120));
				plot.updateImage();
				win.pack();
			}
			win.setLocation(cell.x, cell.y);
			return;
		}
		int iw = imp.getWidth(), ih = imp.getHeight();
		int availW = Math.max(cell.width - chromeW, 32), availH = Math.max(cell.height - chromeH, 32);
		double fitW = availW / (double) iw, fitH = availH / (double) ih;
		String mode = imageZoom();
		double mag;
		Rectangle src;
		int cw, ch;
		if (mode.equals("fill")) {
			// the image covers the whole area: the window fills its zone, the overflowing edges are cropped
			mag = Math.max(1 / 72.0, Math.min(Math.max(fitW, fitH), 32));
			cw = Math.min(availW, (int) Math.floor(iw * mag));
			ch = Math.min(availH, (int) Math.floor(ih * mag));
			int sw = Math.min(iw, (int) Math.round(cw / mag)), sh = Math.min(ih, (int) Math.round(ch / mag));
			src = new Rectangle((iw - sw) / 2, (ih - sh) / 2, sw, sh);
		} else {
			double fit = Math.min(fitW, fitH);
			mag = mode.equals("fit") ? Math.max(1 / 72.0, Math.min(fit, 32)) : zoomFor(fit);
			cw = (int) Math.floor(iw * mag);
			ch = (int) Math.floor(ih * mag);
			src = new Rectangle(0, 0, iw, ih);
		}
		cv.setSourceRect(src);
		cv.setMagnification(mag);
		cv.setSize(cw, ch);
		win.pack();
		win.setLocation(cell.x, cell.y);
		imp.updateAndRepaintWindow();
	}

	// ---------------------------------------------------------------- Magnet-style snapping

	static final String[] SNAP_ZONES = {
		"left-half", "right-half", "top-half", "bottom-half",
		"top-left", "top-right", "bottom-left", "bottom-right",
		"left-third", "center-third", "right-third", "left-two-thirds", "right-two-thirds",
		"center", "maximize", "restore", "next-display", "previous-display"};

	/** The last Fiji window that was focused and is not one of this plugin's dialogs. */
	static Window lastTarget; // package-private so the test harness can aim keys without OS focus

	static Window activeWindow() {
		Window w = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		if (w != null && w.isVisible() && !isOwnDialog(w)) return w;
		if (lastTarget != null && lastTarget.isVisible()) return lastTarget;
		return IJ.getInstance();
	}

	static boolean isOwnDialog(Window w) {
		String t = titleOf(w);
		return t != null && t.startsWith("Snap active window");
	}

	static GraphicsDevice deviceOf(Window w) {
		GraphicsConfiguration gc = w.getGraphicsConfiguration();
		return gc != null ? gc.getDevice() : primaryDevice();
	}

	static Rectangle zoneRect(String zone, Rectangle u, Window w) {
		return zoneRect(zone, u, w == null ? 0 : w.getWidth(), w == null ? 0 : w.getHeight());
	}

	static Rectangle zoneRect(String zone, Rectangle u, int winW, int winH) {
		int hw = u.width / 2, hh = u.height / 2, tw = u.width / 3;
		switch (zone) {
			case "left-half": return new Rectangle(u.x, u.y, hw, u.height);
			case "right-half": return new Rectangle(u.x + hw, u.y, u.width - hw, u.height);
			case "top-half": return new Rectangle(u.x, u.y, u.width, hh);
			case "bottom-half": return new Rectangle(u.x, u.y + hh, u.width, u.height - hh);
			case "top-left": return new Rectangle(u.x, u.y, hw, hh);
			case "top-right": return new Rectangle(u.x + hw, u.y, u.width - hw, hh);
			case "bottom-left": return new Rectangle(u.x, u.y + hh, hw, u.height - hh);
			case "bottom-right": return new Rectangle(u.x + hw, u.y + hh, u.width - hw, u.height - hh);
			case "left-third": return new Rectangle(u.x, u.y, tw, u.height);
			case "center-third": return new Rectangle(u.x + tw, u.y, tw, u.height);
			case "right-third": return new Rectangle(u.x + 2 * tw, u.y, u.width - 2 * tw, u.height);
			case "left-two-thirds": return new Rectangle(u.x, u.y, 2 * tw, u.height);
			case "right-two-thirds": return new Rectangle(u.x + tw, u.y, u.width - tw, u.height);
			case "maximize": return new Rectangle(u);
			case "center": {
				int cw = Math.min(winW, u.width), ch = Math.min(winH, u.height);
				return new Rectangle(u.x + (u.width - cw) / 2, u.y + (u.height - ch) / 2, cw, ch);
			}
			default: return null;
		}
	}

	static void snap(String zone) {
		Window w = activeWindow();
		if (w == null) return;
		if (w == IJ.getInstance()) { IJ.showStatus("Window Layouts: click an image or tool window first"); return; }
		if (zone.equals("restore")) {
			Rectangle prev = previousBounds.get(w);
			if (prev != null) applyBounds(w, prev);
			return;
		}
		if (zone.equals("next-display") || zone.equals("previous-display")) {
			moveToOtherDisplay(w, zone.equals("next-display") ? 1 : -1);
			return;
		}
		// the snap area excludes the toolbar band, so a snapped window never hides the ImageJ toolbar
		Rectangle r = zoneRect(zone, workArea(deviceOf(w)), w);
		if (r == null) return;
		previousBounds.put(w, w.getBounds());
		applyBounds(w, r);
	}

	static void applyBounds(Window w, Rectangle r) {
		if (w instanceof ImageWindow) {
			ImageWindow iw = (ImageWindow) w;
			fitImage(iw, iw.getImagePlus(), r);
		} else if (isResizable(w)) {
			w.setBounds(r);
		} else {
			w.setLocation(r.x, r.y);
		}
	}

	static void moveToOtherDisplay(Window w, int step) {
		List<GraphicsDevice> ds = orderedDevices();
		if (ds.size() < 2) { IJ.showStatus("Window Layouts: only one display"); return; }
		GraphicsDevice cur = deviceOf(w);
		int idx = Math.max(ds.indexOf(cur), 0);
		GraphicsDevice next = ds.get((idx + step + ds.size()) % ds.size());
		Rectangle from = workArea(cur), to = workArea(next), b = w.getBounds();
		previousBounds.put(w, b);
		double fx = (b.x - from.x) / (double) from.width, fy = (b.y - from.y) / (double) from.height;
		fx = Math.max(0, Math.min(fx, 1)); fy = Math.max(0, Math.min(fy, 1));
		int nw = Math.min(b.width, to.width), nh = Math.min(b.height, to.height);
		int nx = Math.min(to.x + (int) Math.round(fx * to.width), to.x + to.width - nw);
		int ny = Math.min(to.y + (int) Math.round(fy * to.height), to.y + to.height - nh);
		applyBounds(w, new Rectangle(nx, ny, nw, nh));
	}

	// ---------------------------------------------------------------- listeners

	static void install() {
		if (System.getProperties().get(INSTALLED_KEY) != null) return;
		AWTEventListener l = new AWTEventListener() {
			public void eventDispatched(AWTEvent e) {
				try {
					if (e instanceof WindowEvent) onWindow((WindowEvent) e);
					else if (e.getID() == java.awt.event.ComponentEvent.COMPONENT_SHOWN && e.getSource() instanceof Window)
						onShown((Window) e.getSource()); // a window shown again (e.g. Fiji's Console) gets no WINDOW_OPENED
					else if (e instanceof KeyEvent) onKey((KeyEvent) e);
				} catch (Throwable t) { /* a layout problem must never break the user's work */
					debug("ERROR " + t + " at " + (t.getStackTrace().length > 0 ? t.getStackTrace()[0] : "")); }
			}
		};
		Toolkit.getDefaultToolkit().addAWTEventListener(l, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.KEY_EVENT_MASK | AWTEvent.COMPONENT_EVENT_MASK);
		System.getProperties().put(INSTALLED_KEY, l);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				// windows that opened before the plugin started (the toolbar, Fiji's Console) get placed too
				Layout a = effective(activeLayout());
				if (a != null && autoEnabled()) placeAll(a);
			}
		});
	}

	static void onWindow(WindowEvent e) {
		int id = e.getID();
		Window w = e.getWindow();
		if (id == WindowEvent.WINDOW_ACTIVATED && !isOwnDialog(w)) lastTarget = w;
		if (!autoEnabled()) return;
		if (id != WindowEvent.WINDOW_OPENED && id != WindowEvent.WINDOW_CLOSED) return;
		boolean image = w instanceof ImageWindow;
		if (id == WindowEvent.WINDOW_OPENED && !image && classify(w) == null && !isClassifiableLater(w)) return;
		if (id == WindowEvent.WINDOW_CLOSED && !image) return;
		queue(w);
	}

	static void onShown(Window w) {
		if (autoEnabled() && !(w instanceof ImageWindow) && (classify(w) != null || isClassifiableLater(w))) queue(w);
	}

	static void queue(Window w) {
		synchronized (pending) { pending.add(w); }
		if (timer == null) {
			timer = new Timer(DELAY_MS, new java.awt.event.ActionListener() {
				public void actionPerformed(java.awt.event.ActionEvent ev) { flushPending(); }
			});
			timer.setRepeats(false);
		}
		timer.restart();
	}

	/** Some windows get their final title just after opening; treat any titled frame as a candidate. */
	static boolean isClassifiableLater(Window w) {
		String t = titleOf(w);
		return t != null && (w instanceof Frame || w instanceof Dialog);
	}

	static void flushPending() {
		List<Window> ws;
		synchronized (pending) { ws = new ArrayList<Window>(pending); pending.clear(); }
		Layout l = effective(activeLayout());
		if (l == null) return;
		boolean images = false;
		for (Window w : ws) {
			if (placeExact(w, l)) continue;
			Group g = classify(w);
			if (w instanceof ImageWindow || (g == null && !w.isVisible())) images = true;
			else if (g != null && g != Group.TOOLBAR) placeTool(w, g, l);
		}
		settle(l);
		if (images) arrangeImages(l);
	}

	// ---------------------------------------------------------------- keyboard shortcuts

	static boolean modifierMatches(KeyEvent e) {
		return modifierMatches(modifier(), e.isControlDown(), e.isAltDown(), e.isShiftDown(), e.isMetaDown());
	}

	static boolean modifierMatches(String mod, boolean c, boolean a, boolean s, boolean m) {
		if (mod.equals("cmd+alt")) return m && a && !c && !s;
		if (mod.equals("ctrl+alt")) return c && a && !m && !s;
		if (mod.equals("ctrl+shift")) return c && s && !a && !m;
		if (mod.equals("alt+shift")) return a && s && !c && !m;
		return false;
	}

	/**
	 * What a key does when pressed with the modifier: "layout:N", "next", "previous", "toggle",
	 * or a snap zone name. Null for keys the plugin does not use. Several keys map to the same action
	 * so that every action can be reached on non-US keyboards (for example Spanish or Italian layouts,
	 * where = [ ] need Shift or Option).
	 */
	static String actionFor(int k) {
		if (k >= KeyEvent.VK_0 && k <= KeyEvent.VK_9) return "layout:" + (k - KeyEvent.VK_0);
		if (k >= KeyEvent.VK_NUMPAD0 && k <= KeyEvent.VK_NUMPAD9) return "layout:" + (k - KeyEvent.VK_NUMPAD0);
		switch (k) {
			case KeyEvent.VK_A: return "toggle";
			case KeyEvent.VK_EQUALS: case KeyEvent.VK_PERIOD: return "next";
			case KeyEvent.VK_MINUS: case KeyEvent.VK_COMMA: case KeyEvent.VK_SLASH: return "previous";
			case KeyEvent.VK_LEFT: return "left-half";
			case KeyEvent.VK_RIGHT: return "right-half";
			case KeyEvent.VK_UP: return "top-half";
			case KeyEvent.VK_DOWN: return "bottom-half";
			case KeyEvent.VK_U: return "top-left";
			case KeyEvent.VK_I: return "top-right";
			case KeyEvent.VK_J: return "bottom-left";
			case KeyEvent.VK_K: return "bottom-right";
			case KeyEvent.VK_D: case KeyEvent.VK_S: return "left-third";
			case KeyEvent.VK_F: return "center-third";
			case KeyEvent.VK_G: return "right-third";
			case KeyEvent.VK_E: return "left-two-thirds";
			case KeyEvent.VK_T: return "right-two-thirds";
			case KeyEvent.VK_C: return "center";
			case KeyEvent.VK_ENTER: return "maximize";
			case KeyEvent.VK_BACK_SPACE: case KeyEvent.VK_DELETE: return "restore";
			case KeyEvent.VK_CLOSE_BRACKET: case KeyEvent.VK_N: return "next-display";
			case KeyEvent.VK_OPEN_BRACKET: case KeyEvent.VK_P: return "previous-display";
			default: return null;
		}
	}

	static void debug(String msg) {
		if (!debugEnabled()) return;
		try {
			java.io.FileWriter fw = new java.io.FileWriter(System.getProperty("user.home") + "/fijilayouts-debug.log", true);
			fw.write(msg + "\n");
			fw.close();
		} catch (java.io.IOException ignored) { }
	}

	static void onKey(KeyEvent e) {
		if (e.getID() == KeyEvent.KEY_PRESSED && (e.isMetaDown() || e.isAltDown() || e.isControlDown()))
			debug("key " + KeyEvent.getKeyText(e.getKeyCode()) + " code=" + e.getKeyCode()
				+ " mods=" + KeyEvent.getModifiersExText(e.getModifiersEx())
				+ " match=" + modifierMatches(e) + " action=" + actionFor(e.getKeyCode()));
		if (e.getID() != KeyEvent.KEY_PRESSED || e.isConsumed() || !modifierMatches(e)) return;
		String action = actionFor(e.getKeyCode());
		if (action == null) return;
		e.consume();
		if (action.startsWith("layout:")) applyLayout(Integer.parseInt(action.substring(7)));
		else if (action.equals("toggle")) toggleAuto();
		else if (action.equals("next")) applyLayout(nextExisting(activeLayout(), 1));
		else if (action.equals("previous")) applyLayout(nextExisting(activeLayout(), -1));
		else snap(action);
	}

	static int nextExisting(int from, int step) {
		int n = from;
		for (int i = 0; i < SLOTS; i++) {
			n = (n + step + SLOTS) % SLOTS;
			if (layout(n) != null) return n;
		}
		return from;
	}

	// ---------------------------------------------------------------- dialogs

	static String slotLabel(int n) {
		Layout l = layout(n);
		return n + ": " + (l == null ? "(empty)" : l.name);
	}

	static String[] slotLabels(int from, int to) {
		String[] s = new String[to - from + 1];
		for (int i = from; i <= to; i++) s[i - from] = slotLabel(i);
		return s;
	}

	static void snapDialog() {
		GenericDialog gd = new GenericDialog("Snap active window");
		gd.addChoice("Zone", SNAP_ZONES, SNAP_ZONES[0]);
		gd.addMessage("Snaps the Fiji window that was active before this dialog opened.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		final String zone = gd.getNextChoice();
		SwingUtilities.invokeLater(new Runnable() { public void run() { snap(zone); } });
	}

	/** Save the current positions of all managed windows as custom layout 7, 8 or 9. */
	static void saveArrangementDialog() {
		GenericDialog gd = new GenericDialog("Save current arrangement");
		gd.addChoice("Save into slot", slotLabels(0, 9), slotLabel(activeLayout()));
		gd.addStringField("Name", "My layout", 20);
		gd.addMessage("Stores exactly what is on screen: every image window (place, size, zoom) and every other\n"
			+ "window by its title (B&C, ROI Manager, Results, Log, Undo History, any plugin's window).\n"
			+ "Windows opened later go where their kind of window was saved.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		final int slot = gd.getNextChoiceIndex();
		String typed = gd.getNextString().trim();
		final String name = typed.isEmpty() ? "Layout " + slot : typed;
		final Map<Group, Zone> zones = captureZones();
		final Layout exact = captureExact(name);
		if (zones.isEmpty() && exact.isEmpty()) { IJ.error("Window Layouts", "No windows to save are open."); return; }
		storeCustom(slot, name, zones, serializeExact(exact));
		Prefs.set("fijilayouts.active", slot);
		persist();
		IJ.showStatus("Window Layouts: saved " + name + " in slot " + slot);
	}

	static Map<Group, Zone> captureZones() {
		Map<Group, Rectangle> union = new EnumMap<Group, Rectangle>(Group.class);
		Map<Group, GraphicsDevice> dev = new EnumMap<Group, GraphicsDevice>(Group.class);
		for (Window w : Window.getWindows()) {
			Group g = classify(w);
			if (g == null || g == Group.TOOLBAR) continue;
			Rectangle b = w.getBounds();
			Rectangle u = union.get(g);
			union.put(g, u == null ? b : u.union(b));
			if (!dev.containsKey(g)) dev.put(g, deviceOf(w));
		}
		Map<Group, Zone> out = new EnumMap<Group, Zone>(Group.class);
		for (Map.Entry<Group, Rectangle> e : union.entrySet()) {
			GraphicsDevice d = dev.get(e.getKey());
			out.put(e.getKey(), toZone(e.getValue(), workArea(d), displayIndex(d)));
		}
		return out;
	}

	/**
	 * Every visible image window (in reading order: top to bottom, then left to right) and every other visible
	 * window with a title, except the ImageJ window, as exact zones of their display's work area.
	 */
	static Layout captureExact(String name) {
		Layout l = new Layout(name);
		List<ImageWindow> wins = new ArrayList<ImageWindow>();
		for (ImagePlus imp : visibleImages()) wins.add(imp.getWindow());
		Collections.sort(wins, new Comparator<ImageWindow>() {
			public int compare(ImageWindow a, ImageWindow b) {
				if (Math.abs(a.getY() - b.getY()) > 40) return a.getY() - b.getY();
				return a.getX() - b.getX();
			}
		});
		for (ImageWindow w : wins) {
			GraphicsDevice d = deviceOf(w);
			Zone z = exactZone(w.getBounds(), workArea(d), displayIndex(d));
			ImagePlus imp = w.getImagePlus();
			ImageCanvas cv = w.getCanvas();
			if (imp == null || cv == null) { l.images.add(new ImageSlot(z, 0, 0, 0, 0, 0, "")); continue; }
			Rectangle src = cv.getSrcRect();
			l.images.add(new ImageSlot(z, cv.getMagnification(), src.x / (double) imp.getWidth(), src.y / (double) imp.getHeight(),
				imp.getWidth(), imp.getHeight(), imp.getTitle()));
		}
		for (Window w : Window.getWindows()) {
			if (!w.isVisible() || w instanceof ImageWindow || w == IJ.getInstance() || isOwnDialog(w)) continue;
			String t = titleOf(w);
			if (t == null || t.trim().isEmpty() || t.startsWith("Save current arrangement")) continue;
			GraphicsDevice d = deviceOf(w);
			l.windows.put(t, exactZone(w.getBounds(), workArea(d), displayIndex(d)));
		}
		return l;
	}

	/** A window rectangle as a zone of a work area, not clipped: a saved window comes back exactly where it was. */
	static Zone exactZone(Rectangle r, Rectangle wa, int display) {
		return new Zone((r.x - wa.x) / (double) wa.width, (r.y - wa.y) / (double) wa.height,
			r.width / (double) wa.width, r.height / (double) wa.height, display);
	}

	/** Inverse of toRect: a window rectangle as a zone of a work area, clipped to it. */
	static Zone toZone(Rectangle r, Rectangle wa, int display) {
		Rectangle c = r.intersection(wa);
		if (c.isEmpty()) c = new Rectangle(wa.x, wa.y, Math.min(r.width, wa.width), Math.min(r.height, wa.height));
		return new Zone((c.x - wa.x) / (double) wa.width, (c.y - wa.y) / (double) wa.height,
			c.width / (double) wa.width, c.height / (double) wa.height, display);
	}

	/** Edit a custom layout numerically: zones as "x,y,w,h" in percent of the work area. */
	static void editLayoutDialog() {
		GenericDialog g1 = new GenericDialog("Edit layout: choose slot");
		g1.addChoice("Edit slot", slotLabels(0, 9), slotLabel(activeLayout()));
		g1.addChoice("Start from", slotLabels(0, 9), slotLabel(activeLayout()));
		g1.addMessage("\"Start from\" fills the next dialog with the zones of that layout.");
		g1.showDialog();
		if (g1.wasCanceled()) return;
		int slot = g1.getNextChoiceIndex();
		int from = g1.getNextChoiceIndex();
		Layout base = layout(from);
		Layout cur = layout(slot);
		Layout seed = base != null ? base : cur;

		GenericDialog gd = new GenericDialog("Edit layout (slot " + slot + ")");
		gd.addStringField("Name", cur != null ? cur.name : "Layout " + slot, 24);
		gd.addMessage("Each zone is x,y,w,h in percent of the display's work area (below the toolbar).\n"
			+ "Add @2, @3... for another display, e.g. 0,0,50,40@2. Leave blank to not manage that window kind."
			+ (seed != null && (!seed.images.isEmpty() || !seed.windows.isEmpty())
				? "\nThis layout also holds " + seed.images.size() + " image window(s) and " + seed.windows.size()
					+ " window(s) saved exactly; they are kept." : ""));
		for (Group g : Group.values()) {
			if (g == Group.TOOLBAR) continue;
			Zone z = seed != null ? seed.zones.get(g) : null;
			gd.addStringField(g.key, z == null ? "" : z.serialize(), 22);
		}
		gd.showDialog();
		if (gd.wasCanceled()) return;
		String name = gd.getNextString().trim();
		Map<Group, Zone> zones = new EnumMap<Group, Zone>(Group.class);
		for (Group g : Group.values()) {
			if (g == Group.TOOLBAR) continue;
			String s = gd.getNextString();
			try {
				Zone z = Zone.parse(s);
				if (z != null) zones.put(g, z);
			} catch (RuntimeException ex) {
				IJ.error("Window Layouts", "Zone for \"" + g.key + "\" is not valid (" + s + "): " + ex.getMessage()
					+ "\nNothing was saved.");
				return;
			}
		}
		String exact = serializeExact(seed); // windows saved exactly are kept
		if (zones.isEmpty() && exact.isEmpty()) { IJ.error("Window Layouts", "All zones are blank. Nothing was saved."); return; }
		storeCustom(slot, name.isEmpty() ? "Layout " + slot : name, zones, exact);
		IJ.showStatus("Window Layouts: slot " + slot + " saved");
	}

	/** Slots 1-6 back to their built-in design; slots 0, 7, 8, 9 emptied. */
	static void resetDialog() {
		GenericDialog gd = new GenericDialog("Reset layout");
		gd.addChoice("Slot", slotLabels(0, 9), slotLabel(activeLayout()));
		gd.addMessage("Slots 1-6 return to their original design. Slots 0, 7, 8 and 9 become empty.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		int slot = gd.getNextChoiceIndex();
		resetSlot(slot);
		IJ.showStatus("Window Layouts: slot " + slot + (builtin(slot) != null ? " reset to its original design" : " emptied"));
	}

	static void prefsDialog() {
		GenericDialog gd = new GenericDialog("Window Layouts preferences");
		gd.addChoice("Shortcut modifier", MODIFIERS, modifier());
		gd.addCheckbox("Place windows automatically as they open", autoEnabled());
		gd.addChoice("Image zoom", IMAGE_ZOOMS, imageZoom());
		gd.addCheckbox("Stretch the ImageJ toolbar across the screen", stretchToolbar());
		gd.addCheckbox("Write a key log to ~/fijilayouts-debug.log (troubleshooting)", debugEnabled());
		gd.addMessage("Image zoom: fill = the image covers its whole area (edges may be cropped; pan with the hand tool),\n"
			+ "fit = the whole image, as large as fits; steps = the whole image at ImageJ's standard zooms.\n"
			+ "Cmd+Alt is the default on macOS (Ctrl+Alt elsewhere). Pick another pair if it clashes with\n"
			+ "a window manager such as Magnet, which uses Ctrl+Alt.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		Prefs.set("fijilayouts.mod", gd.getNextChoice());
		Prefs.set("fijilayouts.auto", gd.getNextBoolean());
		Prefs.set("fijilayouts.imagezoom", gd.getNextChoice());
		Prefs.set("fijilayouts.toolbarwide", gd.getNextBoolean());
		Prefs.set("fijilayouts.debug", gd.getNextBoolean());
		persist();
		IJ.showStatus("Window Layouts: shortcuts now use " + modifier());
	}

	static String modifierLabel() {
		return modifier().replace("cmd", IJ.isMacOSX() ? "Cmd" : "Meta").replace("alt", IJ.isMacOSX() ? "Option" : "Alt")
			.replace("ctrl", "Ctrl").replace("shift", "Shift");
	}

	static void showHelp() {
		String html = "<html><body style='font-family:sans-serif;font-size:12px'>"
			+ "<b>Window Layouts</b> &mdash; shortcuts use <b>" + modifierLabel() + "</b> plus:<br><br>"
			+ "<table cellpadding=2>"
			+ "<tr><td><b>0&ndash;9</b></td><td>apply layout 0&ndash;9 (every slot can be saved over or edited)</td></tr>"
			+ "<tr><td><b>.</b> / <b>,</b></td><td>next / previous layout (also = / &minus;)</td></tr>"
			+ "<tr><td><b>A</b></td><td>turn automatic placement on/off</td></tr>"
			+ "<tr><td><b>arrows</b></td><td>snap the active window to a half</td></tr>"
			+ "<tr><td><b>U I J K</b></td><td>quarters (top-left, top-right, bottom-left, bottom-right)</td></tr>"
			+ "<tr><td><b>S (or D) F G</b></td><td>left, center, right third</td></tr>"
			+ "<tr><td><b>E T</b></td><td>left / right two-thirds</td></tr>"
			+ "<tr><td><b>C</b></td><td>center, <b>Return</b> maximize, <b>Delete</b> restore</td></tr>"
			+ "<tr><td><b>N</b> / <b>P</b></td><td>move to next / previous display (also ] / [)</td></tr>"
			+ "</table><br>Active layout: " + activeLayout() + ". Automatic placement: " + (autoEnabled() ? "on" : "off") + ".<br>"
			+ "Menus: Plugins &gt; Window Layouts. Full guide: FijiWindowLayouts/README.md</body></html>";
		new HTMLDialog("Window Layouts shortcuts", html, false);
	}
}
