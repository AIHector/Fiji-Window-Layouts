package fijilayouts;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * Headless unit tests for the pure parts of the plugin (no Fiji window needed).
 * Run with ./test.sh. Exits with status 1 if any check fails.
 */
public class LayoutTests {
	static int passed, failed;

	static void check(boolean ok, String what) {
		if (ok) passed++;
		else { failed++; System.out.println("FAIL  " + what); }
	}

	static void eq(Object expected, Object actual, String what) {
		check(expected == null ? actual == null : expected.equals(actual), what + "  expected=" + expected + " actual=" + actual);
	}

	static void near(double expected, double actual, String what) {
		check(Math.abs(expected - actual) < 1e-9, what + "  expected=" + expected + " actual=" + actual);
	}

	static void throwsIAE(String input) {
		try {
			Zone.parse(input);
			check(false, "parse should reject \"" + input + "\"");
		} catch (IllegalArgumentException e) {
			check(e.getMessage() != null && !e.getMessage().isEmpty(), "rejects \"" + input + "\" with a message: " + e.getMessage());
		}
	}

	/** The band of the Side Toolbar plugin is removed from the left or right of the usable area. */
	static void sideToolbarBand() {
		Rectangle u = new Rectangle(0, 34, 1512, 902);
		try {
			System.getProperties().remove("sidetoolbar.reserved");
			eq(u, Engine.withoutSideToolbar(u), "no side toolbar: area unchanged");
			System.getProperties().put("sidetoolbar.reserved", new Rectangle(0, 34, 42, 902));
			eq(new Rectangle(42, 34, 1470, 902), Engine.withoutSideToolbar(u), "left bar removed");
			System.getProperties().put("sidetoolbar.reserved", new Rectangle(1470, 34, 42, 902));
			eq(new Rectangle(0, 34, 1470, 902), Engine.withoutSideToolbar(u), "right bar removed");
			System.getProperties().put("sidetoolbar.reserved", new Rectangle(-1512, 0, 42, 900));
			eq(u, Engine.withoutSideToolbar(u), "bar on another display: area unchanged");
			// toolbar + a column beside it + a bottom bar: all three bands are removed
			System.getProperties().put("sidetoolbar.reserved", new Rectangle(0, 34, 40, 902));
			java.util.List<Rectangle> all = new java.util.ArrayList<Rectangle>();
			all.add(new Rectangle(0, 34, 40, 902));
			all.add(new Rectangle(40, 34, 56, 902));
			all.add(new Rectangle(96, 880, 1416, 56));
			System.getProperties().put("sidetoolbar.reservedAll", all);
			eq(new Rectangle(96, 34, 1416, 846), Engine.withoutSideToolbar(u), "toolbar, side column and bottom bar removed");
			all.set(2, new Rectangle(96, 34, 1416, 56));
			eq(new Rectangle(96, 90, 1416, 846), Engine.withoutSideToolbar(u), "a bar at the top is removed from the top");
			all.set(2, new Rectangle(96, 89, 1416, 56)); // a top bar under the 55 px status strip
			eq(new Rectangle(96, 145, 1416, 791), Engine.withoutSideToolbar(u), "a top bar below the status strip: the area starts under it");
			all.set(2, new Rectangle(1456, 34, 56, 902)); // a column at the right edge
			eq(new Rectangle(96, 34, 1360, 902), Engine.withoutSideToolbar(u), "a column at the right edge is removed from the right");
		} finally {
			System.getProperties().remove("sidetoolbar.reserved");
			System.getProperties().remove("sidetoolbar.reservedAll");
		}
	}

	/** Layouts saved exactly: image windows with their zoom, other windows by title. */
	static void exactLayouts() {
		Rectangle wa = new Rectangle(40, 89, 1472, 847);
		Rectangle win = new Rectangle(40, 89, 1276, 847), hang = new Rectangle(1300, 60, 300, 500);
		Zone z = Engine.exactZone(win, wa, 0);
		eq(win, Engine.toRect(z, wa), "exact zone gives back the window rectangle");
		Zone back = Zone.parseExact(z.serializeExact());
		eq(win, Engine.toRect(back, wa), "exact zone survives saving, to the pixel");
		Zone h = Zone.parseExact(Engine.exactZone(hang, wa, 0).serializeExact());
		eq(hang, Engine.toRect(h, wa), "a window hanging off the work area is kept as it is");
		Layout l = new Layout("Mine");
		l.zones.put(Group.LOG, Zone.parse("0,70,30,30"));
		l.images.add(new ImageSlot(z, 4.22, 0.1, 0.25, 300, 200, "cells|DAPI;1=2.tif"));
		l.windows.put("Undo History", Engine.exactZone(new Rectangle(1316, 89, 196, 847), wa, 0));
		l.windows.put("odd;title=x%y", Engine.exactZone(new Rectangle(100, 100, 200, 100), wa, 0));
		String s = Engine.serializeCustom(l.zones) + Engine.serializeExact(l);
		Layout p = Engine.parseCustom("Mine", s);
		eq(1, p.images.size(), "one image window");
		near(4.22, p.images.get(0).mag, "zoom kept");
		near(0.25, p.images.get(0).sy, "visible part kept");
		eq(300, p.images.get(0).iw, "image width kept");
		eq(win, Engine.toRect(p.images.get(0).zone, wa), "image window rectangle kept");
		eq("cells|DAPI;1=2.tif", p.images.get(0).title, "image title kept, with | ; = in it");
		// images go back to their own window by title; the others fill the free windows top-left first
		java.util.List<ImageSlot> slots = new java.util.ArrayList<ImageSlot>();
		for (String n : new String[] {"B", "C", "A"}) slots.add(new ImageSlot(z, 1, 0, 0, 10, 10, n));
		eq("[2, 0, 1]", java.util.Arrays.toString(Engine.assignSlots(slots, new String[] {"A", "B", "C"})), "same images: own windows");
		eq("[1, 0, 2, -1]", java.util.Arrays.toString(Engine.assignSlots(slots, new String[] {"X", "B", "Y", "Z"})), "new images fill free windows, extras none");
		eq("[0]", java.util.Arrays.toString(Engine.assignSlots(slots.subList(0, 1), new String[] {"Q"})), "any image takes a free window");
		eq(new Rectangle(1316, 89, 196, 847), Engine.toRect(p.windows.get("Undo History"), wa), "Undo History kept by title");
		check(p.windows.containsKey("odd;title=x%y"), "titles with ; = % survive");
		check(p.zones.containsKey(Group.LOG), "kind zones still kept");
		eq(null, Engine.parseCustom("E", "img=bad"), "nothing valid: empty");
		Layout onlyExact = Engine.parseCustom("X", "win:Log=0,0,10,10");
		check(onlyExact != null && onlyExact.zones.isEmpty() && onlyExact.windows.size() == 1, "a layout with only exact windows is not empty");
	}

	public static void main(String[] args) {
		exactLayouts();
		sideToolbarBand();
		zoneParsing();
		serialization();
		builtinLayouts();
		zoneMaths();
		customLayouts();
		keys();
		classification();
		misc();
		System.out.println(passed + " passed, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}

	static void zoneParsing() {
		Zone z = Zone.parse("10,20,30,40");
		near(0.1, z.x, "x"); near(0.2, z.y, "y"); near(0.3, z.w, "w"); near(0.4, z.h, "h"); eq(0, z.display, "display default");
		eq(1, Zone.parse("0,0,50,40@2").display, "@2 is display index 1");
		eq(2, Zone.parse(" 5 , 5 , 5 , 5 @ 3 ").display, "spaces allowed, @3");
		near(0.125, Zone.parse("12.5,0,10,10").x, "decimals");
		check(Engine.BUILTIN[1].zones.get(Group.BC).sameAs(Engine.BUILTIN[1].zones.get(Group.THRESHOLD)), "B&C and Threshold share a slot in layout 2");
		check(!Engine.BUILTIN[1].zones.get(Group.BC).sameAs(Engine.BUILTIN[1].zones.get(Group.ROI)), "B&C and ROI do not");
		check(!Zone.parse("0,0,10,10").sameAs(Zone.parse("0,0,10,10@2")), "different display is a different slot");
		eq(null, Zone.parse(""), "blank is unmanaged");
		eq(null, Zone.parse("   "), "spaces only is unmanaged");
		eq(null, Zone.parse(null), "null is unmanaged");
		check(Zone.parse("0,0,100,100") != null, "full area accepted");
		throwsIAE("1,2,3");
		throwsIAE("1,2,3,4,5");
		throwsIAE("a,b,c,d");
		throwsIAE("10,10,0,10");
		throwsIAE("10,10,10,-5");
		throwsIAE("-1,0,10,10");
		throwsIAE("60,0,50,10");
		throwsIAE("0,60,10,50");
		throwsIAE("0,0,10,10@0");
		throwsIAE("0,0,10,10@10");
		throwsIAE("0,0,10,10@x");
		throwsIAE("12,5,0,10,10"); // Italian/Spanish decimal comma gives five fields
	}

	static void serialization() {
		eq("10.0,20.0,30.0,40.0", Zone.parse("10,20,30,40").serialize(), "serialize");
		eq("0.0,0.0,50.0,40.0@2", Zone.parse("0,0,50,40@2").serialize(), "serialize keeps display");
		for (Layout l : Engine.BUILTIN) {
			for (Map.Entry<Group, Zone> e : l.zones.entrySet()) {
				Zone a = e.getValue(), b = Zone.parse(a.serialize());
				boolean ok = Math.abs(a.x - b.x) < 6e-4 && Math.abs(a.y - b.y) < 6e-4 && Math.abs(a.w - b.w) < 6e-4
					&& Math.abs(a.h - b.h) < 6e-4 && a.display == b.display;
				check(ok, l.name + " " + e.getKey().key + " survives serialize/parse: " + a.serialize());
			}
		}
	}

	static void builtinLayouts() {
		eq(6, Engine.BUILTIN.length, "six built-in layouts");
		for (int n = 1; n <= 6; n++) {
			Layout l = Engine.layout(n);
			eq("Layout " + n, l.name, "name of layout " + n);
			check(l.zones.containsKey(Group.IMAGES), l.name + " places images");
			for (Group g : new Group[] {Group.BC, Group.ROI, Group.RESULTS, Group.LOG, Group.THRESHOLD, Group.CHANNELS, Group.SNT})
				check(l.zones.containsKey(g), l.name + " places " + g.key);
			check(!l.zones.containsKey(Group.TOOLBAR), l.name + " leaves the toolbar alone");
			for (Map.Entry<Group, Zone> e : l.zones.entrySet()) {
				Zone z = e.getValue();
				check(z.x >= 0 && z.y >= -1e-9 && z.x + z.w <= 1 + 1e-9 && z.y + z.h <= 1 + 1e-9,
					l.name + " " + e.getKey().key + " inside the work area");
			}
			// two zones are either the same slot (alternative tools) or do not overlap at all
			Group[] gs = l.zones.keySet().toArray(new Group[0]);
			for (int i = 0; i < gs.length; i++) for (int j = i + 1; j < gs.length; j++) {
				Zone a = l.zones.get(gs[i]), b = l.zones.get(gs[j]);
				if (a.display != b.display) continue;
				boolean same = Math.abs(a.x - b.x) < 1e-9 && Math.abs(a.y - b.y) < 1e-9 && Math.abs(a.w - b.w) < 1e-9 && Math.abs(a.h - b.h) < 1e-9;
				double ix = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
				double iy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
				check(same || ix <= 1e-9 || iy <= 1e-9, l.name + ": " + gs[i].key + " and " + gs[j].key + " do not overlap");
			}
		}
		for (Group g : Engine.BUILTIN[5].zones.keySet())
			eq(g == Group.IMAGES ? 0 : 1, Engine.BUILTIN[5].zones.get(g).display, "layout 6 " + g.key + " display");
	}

	/** The real screen of this Mac: 1512x900 usable at y=34, ImageJ toolbar 86 px high, gap 6. */
	static final Rectangle WA = new Rectangle(0, 34 + 92, 1512, 900 - 92);

	static void zoneMaths() {
		// hand-computed: round(fraction * size)
		eq(new Rectangle(0, 126, 877, 808), Engine.toRect(Engine.BUILTIN[0].zones.get(Group.IMAGES), WA), "layout 1 images");
		eq(new Rectangle(877, 393, 635, 299), Engine.toRect(Engine.BUILTIN[0].zones.get(Group.RESULTS), WA), "layout 1 results");
		eq(new Rectangle(0, 756, 1240, 178), Engine.toRect(Engine.BUILTIN[3].zones.get(Group.RESULTS), WA), "layout 4 results");
		eq(new Rectangle(1089, 449, 423, 275), Engine.toRect(Engine.BUILTIN[1].zones.get(Group.RESULTS), WA), "layout 2 results");
		eq(new Rectangle(1255, 126, 257, 323), Engine.toRect(Engine.BUILTIN[1].zones.get(Group.ROI), WA), "layout 2 ROI beside B&C");
		// no space between windows: on each display the distinct zones of a layout tile the whole work area
		for (Layout l : Engine.BUILTIN) {
			for (int d = 0; d <= 1; d++) {
				java.util.List<Rectangle> rs = new java.util.ArrayList<Rectangle>();
				for (Zone z : l.zones.values()) {
					if (z.display != d) continue;
					Rectangle r = Engine.toRect(z, WA);
					if (!rs.contains(r)) rs.add(r);
				}
				if (rs.isEmpty()) continue;
				long area = 0;
				for (Rectangle r : rs) area += (long) r.width * r.height;
				eq((long) WA.width * WA.height, area, l.name + " display " + (d + 1) + ": zones cover the work area with no gaps");
			}
		}
		// snap zones
		eq(new Rectangle(0, 126, 756, 808), Engine.zoneRect("left-half", WA, 0, 0), "left half");
		eq(new Rectangle(756, 126, 756, 808), Engine.zoneRect("right-half", WA, 0, 0), "right half");
		eq(new Rectangle(0, 126, 1512, 404), Engine.zoneRect("top-half", WA, 0, 0), "top half");
		eq(new Rectangle(0, 530, 1512, 404), Engine.zoneRect("bottom-half", WA, 0, 0), "bottom half");
		eq(new Rectangle(756, 126, 756, 404), Engine.zoneRect("top-right", WA, 0, 0), "top-right");
		eq(new Rectangle(756, 530, 756, 404), Engine.zoneRect("bottom-right", WA, 0, 0), "bottom-right");
		eq(new Rectangle(0, 126, 504, 808), Engine.zoneRect("left-third", WA, 0, 0), "left third");
		eq(new Rectangle(504, 126, 504, 808), Engine.zoneRect("center-third", WA, 0, 0), "center third");
		eq(new Rectangle(1008, 126, 504, 808), Engine.zoneRect("right-third", WA, 0, 0), "right third");
		eq(new Rectangle(0, 126, 1008, 808), Engine.zoneRect("left-two-thirds", WA, 0, 0), "left two-thirds");
		eq(new Rectangle(504, 126, 1008, 808), Engine.zoneRect("right-two-thirds", WA, 0, 0), "right two-thirds");
		eq(WA, Engine.zoneRect("maximize", WA, 0, 0), "maximize");
		eq(new Rectangle(556, 430, 400, 200), Engine.zoneRect("center", WA, 400, 200), "center keeps size");
		eq(new Rectangle(0, 126, 1512, 808), Engine.zoneRect("center", WA, 3000, 2000), "center clips oversize");
		eq(null, Engine.zoneRect("restore", WA, 0, 0), "restore has no rectangle");
		Rectangle odd = new Rectangle(0, 0, 1001, 601);
		for (String z : new String[] {"right-half", "bottom-half", "right-third", "bottom-right"}) {
			Rectangle r = Engine.zoneRect(z, odd, 0, 0);
			check(r.x + r.width == 1001 || r.y + r.height == 601, z + " reaches the far edge on odd sizes");
		}
		// image grid
		eq(1, Engine.grid(WA, 1).length, "grid 1");
		eq(new Rectangle(0, 126, 1512, 808), Engine.grid(WA, 1)[0], "grid 1 fills the area");
		Rectangle[] g3 = Engine.grid(new Rectangle(0, 0, 800, 600), 3);
		eq(new Rectangle(0, 0, 266, 600), g3[0], "grid 3: three columns, cell 1");
		eq(new Rectangle(266, 0, 266, 600), g3[1], "grid 3 cell 2");
		eq(new Rectangle(532, 0, 266, 600), g3[2], "grid 3 cell 3");
		Rectangle[] g5 = Engine.grid(new Rectangle(0, 0, 900, 600), 5);
		eq(new Rectangle(300, 300, 300, 300), g5[4], "grid 5 is 3x2, row by row");
		eq(0, Engine.grid(WA, 0).length, "grid 0");
		// zoom
		near(1, Engine.zoomFor(1), "zoom 1");
		near(0.75, Engine.zoomFor(0.99), "zoom 0.99");
		near(3, Engine.zoomFor(3.18), "zoom 3.18 -> 3 (blobs in a half)");
		near(8, Engine.zoomFor(100), "zoom capped at 8");
		near(0.03125, Engine.zoomFor(0.001), "zoom floor");
		// toZone is the inverse of toRect
		Zone z = Engine.BUILTIN[2].zones.get(Group.ROI);
		Zone back = Engine.toZone(Engine.toRect(z, WA), WA, 0);
		check(Math.abs(back.x - z.x) < 2e-3 && Math.abs(back.y - z.y) < 2e-3 && Math.abs(back.w - z.w) < 2e-3 && Math.abs(back.h - z.h) < 2e-3,
			"toZone(toRect(z)) == z");
		Zone clipped = Engine.toZone(new Rectangle(1400, 100, 300, 300), WA, 0);
		check(clipped.x + clipped.w <= 1 + 1e-9 && clipped.y >= 0, "toZone clips windows that hang off the screen");
	}

	static void customLayouts() {
		Map<Group, Zone> zones = new EnumMap<Group, Zone>(Group.class);
		zones.put(Group.IMAGES, Zone.parse("0,0,56,53.6"));
		zones.put(Group.BC, Zone.parse("81,34,10.2,37.5"));
		zones.put(Group.LOG, Zone.parse("0,60,100,40@2"));
		String s = Engine.serializeCustom(zones);
		eq("images=0.0,0.0,56.0,53.6;b&c=81.0,34.0,10.2,37.5;log=0.0,60.0,100.0,40.0@2", s, "serializeCustom");
		Layout l = Engine.parseCustom("Mine", s);
		eq("Mine", l.name, "custom name");
		eq(3, l.zones.size(), "custom zone count");
		eq(1, l.zones.get(Group.LOG).display, "custom display kept");
		Layout junk = Engine.parseCustom("J", "images=0,0,50,50;nonsense;foo=1,2,3,4;roi=bad;toolbar=0,0,10,10");
		eq(1, junk.zones.size(), "bad entries are skipped");
		eq(null, Engine.parseCustom("E", ""), "empty slot");
		eq(null, Engine.parseCustom("E", "roi=bad"), "slot with nothing valid counts as empty");
		eq(null, Engine.layout(8), "unsaved slot 8 is empty");
		eq(null, Engine.layout(0), "layout 0 does not exist");
		eq(null, Engine.layout(10), "layout 10 does not exist");
		// next/previous skip empty slots (no custom layouts saved in this test JVM)
		eq(1, Engine.nextExisting(6, 1), "next after 6 wraps to 1 when 7-9 are empty");
		eq(6, Engine.nextExisting(1, -1), "previous before 1 wraps to 6");
		eq(3, Engine.nextExisting(2, 1), "next after 2");
		ij.Prefs.set("fijilayouts.custom.8", "images=0,0,50,50");
		eq(8, Engine.nextExisting(6, 1), "next after 6 reaches saved slot 8");
		eq(null, Engine.layout(0), "slot 0 starts empty");
		eq(1, Engine.nextExisting(9, 1), "next after 9 skips empty 0");
		// every slot is editable: saving over a built-in slot replaces it, resetting brings the design back
		ij.Prefs.set("fijilayouts.custom.3", "images=0,0,50,100;log=50,0,50,100");
		ij.Prefs.set("fijilayouts.name.3", "My three");
		eq("My three", Engine.layout(3).name, "slot 3 saved over");
		eq(2, Engine.layout(3).zones.size(), "slot 3 has the saved zones");
		ij.Prefs.set("fijilayouts.custom.0", "images=0,0,100,100");
		eq("Layout 0", Engine.layout(0).name, "slot 0 saved, default name");
		eq(0, Engine.nextExisting(9, 1), "next after 9 now reaches saved slot 0");
		ij.Prefs.set("fijilayouts.custom.6", "images=0,0,100,100");
		eq(false, Engine.fallsBack(6, 1), "a saved slot 6 is used as is on one display");
		ij.Prefs.set("fijilayouts.custom.3", ""); ij.Prefs.set("fijilayouts.name.3", "");
		ij.Prefs.set("fijilayouts.custom.6", ""); ij.Prefs.set("fijilayouts.custom.0", "");
		check(Engine.layout(3) == Engine.BUILTIN[2], "reset slot 3 is the built-in design again");
		eq(null, Engine.layout(0), "reset slot 0 is empty again");
		eq("8: Layout 8", Engine.slotLabel(8), "slot label of an unnamed saved layout");
		eq("7: (empty)", Engine.slotLabel(7), "slot label of an empty slot");
		eq("3: Layout 3", Engine.slotLabel(3), "slot label of a built-in");
	}

	static void keys() {
		for (int d = 1; d <= 9; d++) {
			eq("layout:" + d, Engine.actionFor(KeyEvent.VK_0 + d), "digit " + d);
			eq("layout:" + d, Engine.actionFor(KeyEvent.VK_NUMPAD0 + d), "keypad " + d);
		}
		eq("layout:0", Engine.actionFor(KeyEvent.VK_0), "0 applies layout 0");
		eq("layout:0", Engine.actionFor(KeyEvent.VK_NUMPAD0), "keypad 0 applies layout 0");
		eq("toggle", Engine.actionFor(KeyEvent.VK_A), "A toggles automatic placement");
		eq("next", Engine.actionFor(KeyEvent.VK_PERIOD), ". next");
		eq("next", Engine.actionFor(KeyEvent.VK_EQUALS), "= next");
		eq("previous", Engine.actionFor(KeyEvent.VK_COMMA), ", previous");
		eq("previous", Engine.actionFor(KeyEvent.VK_MINUS), "- previous");
		eq("previous", Engine.actionFor(KeyEvent.VK_SLASH), "/ previous (the key labelled - on Spanish keyboards)");
		eq("left-third", Engine.actionFor(KeyEvent.VK_S), "S left third");
		eq("next-display", Engine.actionFor(KeyEvent.VK_N), "N next display");
		eq("previous-display", Engine.actionFor(KeyEvent.VK_P), "P previous display");
		eq(null, Engine.actionFor(KeyEvent.VK_Q), "Q unused");
		eq(null, Engine.actionFor(KeyEvent.VK_H), "H unused (macOS Hide Others)");
		eq(null, Engine.actionFor(KeyEvent.VK_M), "M unused (macOS minimize)");
		eq(null, Engine.actionFor(KeyEvent.VK_SPACE), "Space unused (Finder search)");
		eq(null, Engine.actionFor(KeyEvent.VK_ESCAPE), "Esc unused (Force Quit)");
		eq(null, Engine.actionFor(KeyEvent.VK_8 + 100), "unknown key");
		// every snap zone has a key
		java.util.Set<String> reachable = new java.util.HashSet<String>();
		for (int k = 0; k < 0x10000; k++) { String a = Engine.actionFor(k); if (a != null) reachable.add(a); }
		for (String z : Engine.SNAP_ZONES) check(reachable.contains(z), "snap zone " + z + " has a key");
		// modifier pairs
		eq(true, Engine.modifierMatches("cmd+alt", false, true, false, true), "cmd+alt");
		eq(false, Engine.modifierMatches("cmd+alt", true, true, false, true), "cmd+alt+ctrl is Magnet's, not ours");
		eq(false, Engine.modifierMatches("cmd+alt", false, true, true, true), "cmd+alt+shift is not ours");
		eq(false, Engine.modifierMatches("cmd+alt", true, true, false, false), "ctrl+alt (Magnet) does not match cmd+alt");
		eq(true, Engine.modifierMatches("ctrl+alt", true, true, false, false), "ctrl+alt");
		eq(true, Engine.modifierMatches("ctrl+shift", true, false, true, false), "ctrl+shift");
		eq(true, Engine.modifierMatches("alt+shift", false, true, true, false), "alt+shift");
		eq(false, Engine.modifierMatches("alt+shift", false, true, false, false), "alt alone");
		eq(false, Engine.modifierMatches("bogus", true, true, true, true), "unknown modifier never matches");
	}

	static void classification() {
		eq(Group.BC, Engine.classifyTitle("B&C", false), "B&C");
		eq(Group.ROI, Engine.classifyTitle("ROI Manager", false), "ROI Manager");
		eq(Group.THRESHOLD, Engine.classifyTitle("Threshold", false), "Threshold");
		eq(Group.CHANNELS, Engine.classifyTitle("Channels", false), "Channels");
		eq(Group.LOG, Engine.classifyTitle("Log", true), "Log (a TextWindow) goes to the log slot");
		eq(Group.CONSOLE, Engine.classifyTitle("Console", false), "Console has its own kind");
		check(Engine.resizableGroup(Group.CONSOLE), "Console is resized to its zone like the Log");
		eq(Engine.BUILTIN[0].zones.get(Group.LOG), Engine.zoneOf(Engine.BUILTIN[0], Group.CONSOLE), "Console falls back to the Log zone");
		Layout own = Engine.parseCustom("c", "log=0,0,50,50;console=50,50,50,50");
		near(0.5, Engine.zoneOf(own, Group.CONSOLE).x, "Console uses its own zone when it has one");
		eq(Group.RESULTS, Engine.classifyTitle("Results", true), "Results");
		eq(Group.RESULTS, Engine.classifyTitle("Summary of blobs", true), "any other table");
		eq(Group.SNT, Engine.classifyTitle("Path Manager", false), "Path Manager");
		eq(Group.SNT, Engine.classifyTitle("Fill Manager", false), "Fill Manager");
		eq(null, Engine.classifyTitle("Snap active window", false), "own dialog unmanaged");
		eq(null, Engine.classifyTitle("Snapshot", false), "titles that merely start with SN are unmanaged");
		eq(null, Engine.classifyTitle(null, false), "untitled");
		eq(Group.SNT, Engine.classifyTitle("SNT 5.0.21", false), "SNT main window (SNT 5)");
		eq(Group.SNT, Engine.classifyTitle("SNT v4.3.0", false), "SNT main window (older)");
		eq(Group.SNT, Engine.classifyTitle("Neuroanatomy Shortcuts", false), "Neuroanatomy Shortcuts");
		eq(null, Engine.classifyTitle("SNT Startup Prompt", false), "SNT startup prompt unmanaged");
		eq(null, Engine.classifyTitle("Loading SNT...", false), "SNT loading message unmanaged");
		eq(true, Engine.fallsBack(6, 1), "layout 6 on one display falls back");
		eq(false, Engine.fallsBack(6, 2), "layout 6 on two displays does not");
		eq(false, Engine.fallsBack(7, 1), "only layout 6 falls back");
	}

	static void misc() {
		check(Engine.compareBounds(new Rectangle(-1440, -1578, 1440, 2560), new Rectangle(0, -1440, 2560, 1440)) < 0,
			"left display comes before the one above");
		check(Engine.compareBounds(new Rectangle(0, -1440, 10, 10), new Rectangle(0, 982, 10, 10)) < 0, "same x: upper first");
		eq(0, Engine.compareBounds(new Rectangle(5, 5, 1, 1), new Rectangle(5, 5, 9, 9)), "same origin");
	}
}
