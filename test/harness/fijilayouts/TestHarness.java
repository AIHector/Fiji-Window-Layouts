package fijilayouts;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageWindow;

import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Development-only live-test harness (NOT part of the installed plugin).
 * Started by a temporary AutoRun macro: call("fijilayouts.TestHarness.start", dir).
 * Polls dir/cmd.ijm, runs it as a macro, writes dir/done.txt. Macros call dump(path) to record
 * every visible window's bounds and the plugin's view of it (group, magnification, displays).
 */
public class TestHarness {
	private static Timer timer;
	private static String dir;

	public static String start(String d) {
		dir = d;
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				timer = new Timer(300, new java.awt.event.ActionListener() {
					public void actionPerformed(java.awt.event.ActionEvent e) { poll(); }
				});
				timer.start();
			}
		});
		return "started";
	}

	static void poll() {
		final File cmd = new File(dir, "cmd.ijm");
		if (!cmd.exists()) return;
		final File run = new File(dir, "running.ijm");
		run.delete();
		if (!cmd.renameTo(run)) return;
		new Thread(new Runnable() {
			public void run() {
				String out;
				try {
					out = "OK " + IJ.runMacroFile(run.getPath());
				} catch (Throwable t) {
					out = "EXC " + t;
				}
				write(new File(dir, "done.txt"), out + "\n");
			}
		}, "wl-test").start();
	}

	static void write(File f, String s) {
		try {
			FileWriter w = new FileWriter(f);
			w.write(s);
			w.close();
		} catch (IOException ignored) { }
	}

	/** Writes one line per visible window plus display and plugin state. */
	public static String dump(final String path) {
		final StringBuilder sb = new StringBuilder();
		try {
			SwingUtilities.invokeAndWait(new Runnable() {
				public void run() {
					List<GraphicsDevice> ds = Engine.orderedDevices();
					for (int i = 0; i < ds.size(); i++) {
						GraphicsDevice d = ds.get(i);
						Rectangle b = d.getDefaultConfiguration().getBounds();
						Insets in = Toolkit.getDefaultToolkit().getScreenInsets(d.getDefaultConfiguration());
						Rectangle wa = Engine.workArea(d);
						sb.append("DISPLAY\t").append(i + 1).append('\t').append(r(b)).append("\tinsets=")
							.append(in.top).append(',').append(in.left).append(',').append(in.bottom).append(',').append(in.right)
							.append("\twork=").append(r(wa)).append('\n');
					}
					sb.append("STATE\tactive=").append(Engine.activeLayout()).append("\tauto=").append(Engine.autoEnabled())
						.append("\tmod=").append(Engine.modifier()).append("\tdebug=").append(Engine.debugEnabled()).append('\n');
					sb.append("STATUS\t").append(statusText()).append('\n');
					Window fw = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
					sb.append("FOCUS\t").append(fw == null ? "none" : Engine.titleOf(fw)).append('\n');
					for (Window w : Window.getWindows()) {
						if (!w.isVisible()) continue;
						Group g = Engine.classify(w);
						sb.append("WIN\t").append(Engine.titleOf(w)).append('\t').append(w.getClass().getSimpleName()).append('\t')
							.append(r(w.getBounds())).append('\t').append(g == null ? "-" : g.key).append('\t')
							.append(Engine.isResizable(w) ? "resizable" : "fixed").append('\t')
							.append("pref=").append(w.getPreferredSize().width).append("x").append(w.getPreferredSize().height).append("\t")
							.append("display=").append(Engine.displayIndex(Engine.deviceOf(w)) + 1);
						if (w instanceof ImageWindow) {
							ImageWindow iw = (ImageWindow) w;
							ImagePlus imp = iw.getImagePlus();
							if (iw.getCanvas() != null && imp != null)
								sb.append("\tmag=").append(iw.getCanvas().getMagnification()).append("\timg=")
									.append(imp.getWidth()).append('x').append(imp.getHeight()).append("\tcanvas=")
									.append(iw.getCanvas().getWidth()).append('x').append(iw.getCanvas().getHeight());
						}
						sb.append('\n');
					}
				}
			});
		} catch (Exception e) {
			sb.append("ERROR ").append(e).append('\n');
		}
		write(new File(path), sb.toString());
		return "dumped";
	}

	/** Text in the ImageJ status bar (IJ.getStatusBarText, else the first label found in the status panel). */
	static String statusText() {
		try {
			Object o = IJ.class.getMethod("getStatusBarText").invoke(null);
			if (o != null) return o.toString();
		} catch (Throwable ignored) { }
		try {
			java.awt.Panel p = IJ.getInstance().getStatusBar();
			StringBuilder sb = new StringBuilder();
			for (java.awt.Component c : p.getComponents()) collect(c, sb);
			return sb.toString();
		} catch (Throwable t) { return "?" + t; }
	}

	static void collect(java.awt.Component c, StringBuilder sb) {
		if (c instanceof java.awt.Label) sb.append(((java.awt.Label) c).getText()).append(' ');
		else if (c instanceof javax.swing.JLabel) sb.append(((javax.swing.JLabel) c).getText()).append(' ');
		if (c instanceof java.awt.Container) for (java.awt.Component k : ((java.awt.Container) c).getComponents()) collect(k, sb);
	}

	static String r(Rectangle b) { return b.x + "," + b.y + "," + b.width + "," + b.height; }

	/**
	 * Injects a key press (Java key code, with the current modifier pair) into Fiji's event queue, aimed at the
	 * window with this title, as if it were focused. Goes through the plugin's own AWT key listener.
	 */
	public static String key(final String title, String keyCode) { return keyMods(title, keyCode, Engine.modifier()); }

	/** Same as key, with an explicit modifier pair such as "cmd+alt" or "ctrl+alt+cmd". */
	public static String keyMods(final String title, String keyCode, final String m) {
		final int code = Integer.parseInt(keyCode);
		final String[] res = {"not found"};
		focus(title); // when Fiji is the active app, the plugin acts on the focused window
		try { Thread.sleep(300); } catch (InterruptedException ignored) { }
		try {
			SwingUtilities.invokeAndWait(new Runnable() {
				public void run() {
					for (Window w : Window.getWindows()) {
						if (!w.isVisible() || !title.equals(Engine.titleOf(w))) continue;
						Engine.lastTarget = w;
						int mods = (m.contains("cmd") ? java.awt.event.InputEvent.META_DOWN_MASK : 0)
							| (m.contains("alt") ? java.awt.event.InputEvent.ALT_DOWN_MASK : 0)
							| (m.contains("ctrl") ? java.awt.event.InputEvent.CTRL_DOWN_MASK : 0)
							| (m.contains("shift") ? java.awt.event.InputEvent.SHIFT_DOWN_MASK : 0);
						java.awt.event.KeyEvent e = new java.awt.event.KeyEvent(w, java.awt.event.KeyEvent.KEY_PRESSED,
							System.currentTimeMillis(), mods, code, java.awt.event.KeyEvent.CHAR_UNDEFINED);
						w.dispatchEvent(e);
						Window aw = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
						res[0] = (e.isConsumed() ? "consumed" : "not consumed") + ", active=" + (aw == null ? "none" : Engine.titleOf(aw));
						return;
					}
				}
			});
		} catch (Exception e) { res[0] = e.toString(); }
		return res[0];
	}

	/** Writes to stderr, which makes Fiji open its Console window. */
	public static String stderr(String s) {
		System.err.println(s);
		return "ok";
	}

	/** Brings a window to the front by title (so keyboard snaps act on it). */
	public static String focus(final String title) {
		final String[] res = {"not found"};
		try {
			SwingUtilities.invokeAndWait(new Runnable() {
				public void run() {
					for (Window w : Window.getWindows())
						if (w.isVisible() && title.equals(Engine.titleOf(w))) { w.toFront(); w.requestFocus(); res[0] = "ok"; }
				}
			});
		} catch (Exception e) { res[0] = e.toString(); }
		return res[0];
	}
}
