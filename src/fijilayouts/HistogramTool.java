package fijilayouts;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.HistogramPlot;
import ij.gui.ImageCanvas;
import ij.gui.Roi;
import ij.gui.RoiListener;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.frame.PlugInFrame;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.StackStatistics;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/** A histogram is a tool Frame, never a registered ImagePlus or an ImageCanvas. */
final class HistogramTool extends PlugInFrame implements ImageListener, RoiListener {
    private static final Set<HistogramTool> tools = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
    private static HistogramTool following;
    private static final Field STATS = field("stats"), COUNTS = field("histogram"), FRAME = field("frame"),
            RGB = field("rgbMode"), LOG = field("logScale");
    private static Field field(String name) {
        try { Field f = HistogramPlot.class.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException | RuntimeException ex) { return null; }
    }
    static boolean supported() { return STATS != null && COUNTS != null && FRAME != null && RGB != null && LOG != null; }
    static ArrayList<HistogramTool> openTools() { synchronized (tools) { return new ArrayList<>(tools); } }
    static HistogramTool followingTool() { return following; }
    static void showFollowing() {
        if (following == null || following.disposed)
            following = new HistogramTool(selectedImage(), new HistogramToolCommand.Options(), true);
        else { following.followCurrent(); following.setVisible(true); following.toFront(); }
    }
    private static ImagePlus selectedImage() {
        ImagePlus image = WindowManager.getCurrentImage();
        return image != null && ImageWindows.isDataWindow(image.getWindow()) && image.getWindow().isVisible() ? image : null;
    }
    static void selectedWindow(java.awt.Window window) {
        if (following != null && ImageWindows.isDataWindow(window))
            following.bindSource(((ij.gui.ImageWindow) window).getImagePlus());
    }

    volatile ImagePlus source;
    int sourceID;
    HistogramToolCommand.Options options;
    final boolean followsSelection;
    private int generation;
    private int sourceType;
    private final Timer selectionPoll = new Timer(150, event -> followCurrent());
    private final java.util.concurrent.atomic.AtomicBoolean updateQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final Timer updateTimer = new Timer(120, event -> updateAfterBurst());
    final JLabel sourceLabel = new JLabel(), readout = new JLabel("Value: —     Count: —"), status = new JLabel("Computing histogram…");
    final JCheckBox live = new JCheckBox("Live"), log = new JCheckBox("Log");
    final JButton refresh = new JButton("Refresh"), list = new JButton("List"), copy = new JButton("Copy"), channel = new JButton("RGB mode");
    final Graph graph = new Graph();
    Model model;
    SwingWorker<Model, Void> worker;
    boolean dirty, disposed, fromSource;
    int sampleX, sampleY, mode;
    double sampledValue = Double.NaN;
    long sampledCount = -1;

    HistogramTool(ImagePlus source, HistogramToolCommand.Options options) {
        this(source, options, false);
    }
    private HistogramTool(ImagePlus source, HistogramToolCommand.Options options, boolean followsSelection) {
        super(followsSelection ? "Histogram Tool" : "Histogram of " + source.getShortTitle());
        this.followsSelection = followsSelection;
        updateTimer.setRepeats(false);
        this.source = source; this.sourceID = source == null ? 0 : source.getID(); this.options = options;
        sourceType = source == null ? -1 : source.getType();
        if (followsSelection) this.options = defaultOptions(source);
        mode = source != null && source.isRGB() ? 0 : -1;
        setLayout(new BorderLayout());
        JPanel panel = new JPanel(new BorderLayout(0, 6)); panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        sourceLabel.setText(source == null ? "No image selected" : "Source: " + source.getTitle()); panel.add(sourceLabel, BorderLayout.NORTH);
        JPanel plotPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        plotPanel.add(graph); panel.add(plotPanel, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(readout, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttons.add(list); buttons.add(copy); buttons.add(log); buttons.add(live); buttons.add(refresh);
        buttons.add(channel); channel.setVisible(source != null && source.isRGB() && !this.options.stack);
        if (this.options.stack) live.setEnabled(false);
        if (followsSelection) live.setSelected(true);
        bottom.add(buttons, BorderLayout.CENTER); bottom.add(status, BorderLayout.SOUTH); panel.add(bottom, BorderLayout.SOUTH); add(panel);
        list.addActionListener(e -> { if (model != null) table().show(getTitle()); });
        copy.addActionListener(e -> { if (model != null) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(tableText()), null); });
        log.addActionListener(e -> { if (model != null) { model.render(log.isSelected()); graph.repaint(); } });
        live.addActionListener(e -> { if (live.isSelected()) requestUpdate(); });
        refresh.addActionListener(e -> requestUpdate());
        channel.addActionListener(e -> { mode = (mode + 1) % 6; requestUpdate(); });
        graph.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseMoved(MouseEvent e) { inspectGraph(e.getX(), e.getY()); }
        });
        tools.add(this); ImagePlus.addImageListener(this); Roi.addRoiListener(this);
        list.setEnabled(false); copy.setEnabled(false); log.setEnabled(false);
        if (source == null) { live.setEnabled(false); refresh.setEnabled(false); status.setText("Select an image to inspect"); }
        WindowManager.addWindow(this);
        pack(); setResizable(false);
        Rectangle b = source != null && source.getWindow() != null ? source.getWindow().getBounds() : new Rectangle(100, 100, 0, 0);
        setLocation(b.x + Math.min(80, b.width), b.y + 35);
        setVisible(true); requestUpdate();
        if (followsSelection) { selectionPoll.setCoalesce(true); selectionPoll.start(); }
    }

    private static HistogramToolCommand.Options defaultOptions(ImagePlus image) {
        HistogramToolCommand.Options options = new HistogramToolCommand.Options();
        if (image != null && image.getBitDepth() == 16 && ImagePlus.getDefault16bitRange() != 0) {
            options.min = image.getCalibration().getCValue(0);
            options.max = image.getCalibration().getCValue(Math.pow(2, ImagePlus.getDefault16bitRange()) - 1);
        }
        return options;
    }
    /** Tools do not become data sources. Polling also observes macro/menu selections without a focus event. */
    void followCurrent() {
        if (!followsSelection || disposed) return;
        ImagePlus selected = selectedImage();
        if (selected != null && (selected != source || selected.getType() != sourceType)) bindSource(selected);
        else if (source != null && WindowManager.getImage(sourceID) != source) bindSource(null);
    }
    private void bindSource(ImagePlus next) {
        if (source == next && (next == null || next.getType() == sourceType)) return;
        Engine.debug("Histogram follow source " + sourceID + " -> " + (next == null ? 0 : next.getID()));
        generation++; source = next; sourceID = next == null ? 0 : next.getID();
        sourceType = next == null ? -1 : next.getType();
        options = defaultOptions(next); mode = next != null && next.isRGB() ? 0 : -1;
        if (model != null) model.release(); model = null; clearReadout();
        list.setEnabled(false); copy.setEnabled(false); log.setEnabled(false);
        live.setEnabled(next != null); refresh.setEnabled(next != null); channel.setEnabled(next != null);
        channel.setVisible(next != null && next.isRGB());
        sourceLabel.setText(next == null ? "No image selected" : "Source: " + next.getTitle());
        status.setText(next == null ? "Select an image to inspect" : "Updating source statistics…");
        graph.repaint(); pack();
        dirty = worker != null;
        if (next != null) requestUpdate();
    }

    /** No ImageWindow activation: the source stays the image that image commands operate on. */
    private void activateSource() {
        if (followsSelection) {
            followCurrent();
            if (selectedImage() == null && source != null && source.getWindow() != null)
                WindowManager.setCurrentWindow(source.getWindow());
            WindowManager.setWindow(this); return;
        }
        if (source != null && WindowManager.getImage(sourceID) == source && source.getWindow() != null)
            WindowManager.setCurrentWindow(source.getWindow());
        WindowManager.setWindow(this);
    }
    public void windowActivated(WindowEvent e) { activateSource(); }
    public void focusGained(FocusEvent e) { activateSource(); }
    // The base class records run("Close"), which would close the source during macro replay.
    public void windowClosing(WindowEvent e) { close(); }

    void requestUpdate() {
        if (disposed || source == null) return;
        if (worker != null) { dirty = true; return; }
        final ImagePlus current = source;
        final int requestedMode = mode;
        final int requestedGeneration = generation;
        final boolean requestedLog = log.isSelected();
        final HistogramToolCommand.Options requestedOptions = options;
        status.setText("Updating source statistics…");
        worker = new SwingWorker<Model, Void>() {
            protected Model doInBackground() throws Exception { return new Model(current, requestedOptions, requestedMode, requestedLog); }
            protected void done() {
                worker = null;
                try {
                    Model next = get();
                    if (disposed || source != current || generation != requestedGeneration || mode != requestedMode) { next.release(); return; }
                    if (model != null) model.release();
                    model = next;
                    if (requestedLog != log.isSelected()) model.render(log.isSelected());
                    Dimension size = new Dimension(model.raster.getWidth(), model.raster.getHeight());
                    if (!size.equals(graph.getPreferredSize())) {
                        graph.setPreferredSize(size);
                        pack();
                    }
                    list.setEnabled(true); copy.setEnabled(true); log.setEnabled(true);
                    sourceLabel.setText("Source: " + current.getTitle());
                    status.setText(options.stack ? "Whole stack · snapshot" : live.isSelected() ? "Live · source pixels" : "Snapshot · Refresh to recalculate");
                    if (fromSource) inspectSource(sampleX, sampleY); else clearReadout();
                    graph.repaint();
                } catch (Exception ex) { if (!disposed && source == current && generation == requestedGeneration) { status.setText("Could not update histogram"); Engine.debug("Histogram tool: " + ex); } }
                finally { if (dirty && !disposed) { dirty = false; requestUpdate(); } }
            }
        };
        worker.execute();
    }
    public void imageOpened(ImagePlus image) { if (followsSelection) EventQueue.invokeLater(this::followCurrent); }
    public void imageUpdated(ImagePlus image) {
        if (image != source || !updateQueued.compareAndSet(false, true)) return;
        EventQueue.invokeLater(() -> { if (!disposed) updateTimer.start(); });
    }
    private void updateAfterBurst() {
        updateQueued.set(false);
        if (disposed || source == null) return;
        if (followsSelection && source.getType() != sourceType) bindSource(source);
        else if (live.isSelected()) requestUpdate();
        else if (fromSource && HistogramInteraction.followsSource()) inspectSource(sampleX, sampleY);
    }
    public void roiModified(ImagePlus image, int id) { imageUpdated(image); }
    public void imageClosed(ImagePlus image) {
        if (image != source) return;
        EventQueue.invokeLater(() -> {
            if (source != image || disposed) return;
            if (followsSelection) { bindSource(null); followCurrent(); return; }
            source = null; dirty = false;
            live.setSelected(false); live.setEnabled(false); refresh.setEnabled(false); channel.setEnabled(false);
            sourceLabel.setText("Source closed · " + image.getTitle()); status.setText("Frozen snapshot · source is closed");
            clearReadout();
        });
    }
    public void close() {
        if (disposed) return;
        disposed = true; tools.remove(this); ImagePlus.removeImageListener(this); Roi.removeRoiListener(this);
        selectionPoll.stop(); updateTimer.stop(); if (following == this) following = null;
        if (worker != null) worker.cancel(true);
        if (model != null) model.release(); model = null; source = null;
        super.close();
    }

    static void onMotion(MouseEvent event) {
        if (tools.isEmpty() || !HistogramInteraction.followsSource() || !(event.getSource() instanceof ImageCanvas)) return;
        ImageCanvas canvas = (ImageCanvas) event.getSource(); ImagePlus image = canvas.getImage();
        if (image == null || !ImageWindows.isDataWindow(image.getWindow()) || event.getX() < 0 || event.getY() < 0
                || event.getX() >= canvas.getWidth() || event.getY() >= canvas.getHeight()) return;
        int x = canvas.offScreenX(event.getX()), y = canvas.offScreenY(event.getY());
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) return;
        for (HistogramTool tool : openTools()) if (tool.source == image) tool.inspectSource(x, y);
    }
    void inspectSource(int x, int y) {
        if (source == null || model == null || x < 0 || y < 0 || x >= source.getWidth() || y >= source.getHeight()) return;
        sampleX = x; sampleY = y; fromSource = true;
        double raw = HistogramInteraction.rawValue(source.getProcessor(), x, y, model.mode);
        int bin = HistogramInteraction.bin(raw, model.stats, model.counts.length);
        showReadout(Double.isNaN(raw) ? Double.NaN : model.calibration.getCValue(raw), Double.isNaN(raw) ? -1 : bin < 0 ? 0 : model.counts[bin]);
    }
    void inspectGraph(int x, int y) {
        if (model == null || !model.plotBounds.contains(x, y)) return;
        fromSource = false;
        int bin = Math.min(model.counts.length - 1, (x - model.plotBounds.x) * model.counts.length / model.plotBounds.width);
        showReadout(model.calibration.getCValue(model.stats.histMin + bin * model.stats.binSize), model.counts[bin]);
    }
    private void clearReadout() { fromSource = false; showReadout(Double.NaN, -1); }
    private void showReadout(double value, long count) {
        if (Double.compare(sampledValue, value) == 0 && sampledCount == count) return;
        sampledValue = value; sampledCount = count;
        readout.setText("Value: " + (Double.isFinite(value) ? IJ.d2s(value, value == Math.rint(value) ? 0 : 4) : "—")
                + "     Count: " + (count >= 0 ? Long.toString(count) : "—"));
    }
    ResultsTable table() {
        ResultsTable table = new ResultsTable();
        if (model != null) for (int i = 0; i < model.counts.length; i++) {
            table.incrementCounter(); table.addValue("Value", model.calibration.getCValue(model.stats.histMin + i * model.stats.binSize));
            table.addValue("Count", model.counts[i]);
        }
        return table;
    }
    String tableText() {
        ResultsTable table = table(); StringBuilder text = new StringBuilder("Value\tCount\n");
        for (int i = 0; i < table.size(); i++) text.append(table.getValue("Value", i)).append('\t').append((long) table.getValue("Count", i)).append('\n');
        return text.toString();
    }
    final class Graph extends JComponent {
        Graph() { setPreferredSize(new Dimension((int)(300 * ij.Prefs.getGuiScale()), (int)(246 * ij.Prefs.getGuiScale()))); }
        protected void paintComponent(Graphics graphics) { super.paintComponent(graphics); if (model != null) graphics.drawImage(model.raster, 0, 0, null); }
    }

    /** The private plot is never shown or registered; it is only ImageJ's statistics renderer. */
    static final class Model {
        final HistogramPlot plot = new HistogramPlot();
        final ImagePlus view;
        final ImageStatistics stats;
        final long[] counts;
        final Calibration calibration;
        final Rectangle plotBounds;
        final int mode;
        BufferedImage raster;
        Model(ImagePlus source, HistogramToolCommand.Options options, int mode, boolean logarithmic) throws Exception {
            this.mode = mode;
            ImageProcessor raw = source.getProcessor().duplicate();
            // Display contrast belongs to the source view, never to histogram plot colours or bin ranges.
            if (!(raw instanceof ColorProcessor)) raw.resetMinAndMax();
            if (raw instanceof ColorProcessor && mode == 1) {
                ColorProcessor cp = (ColorProcessor) raw;
                cp.setRGBWeights(cp.weightedHistogram() ? new double[]{1d/3, 1d/3, 1d/3} : new double[]{.299, .587, .114});
            }
            view = new ImagePlus(source.getTitle(), raw); view.setCalibration(source.getCalibration().copy());
            if (source.getRoi() != null) view.setRoi((Roi) source.getRoi().clone());
            double threshold = source.getProcessor().getMinThreshold();
            if (threshold != ImageProcessor.NO_THRESHOLD) raw.setThreshold(threshold, source.getProcessor().getMaxThreshold(), source.getProcessor().getLutUpdateMode());
            RGB.setInt(plot, mode);
            LOG.setBoolean(plot, logarithmic);
            // StackStatistics reads the source stack; the drawing view owns only a duplicate of its current plane.
            if (options.stack || ((source.getBitDepth() == 8 || source.isRGB()) && (options.bins != 256 || options.min != 0 || options.max != 0 || options.yMax > 0))) {
                ImageStatistics stats = new StackStatistics(options.stack ? source : view, options.bins, options.min, options.max);
                stats.histYMax = options.yMax; plot.draw(view, stats);
            } else plot.draw(view, options.bins, options.min, options.max, options.yMax);
            stats = (ImageStatistics) STATS.get(plot); counts = ((long[]) COUNTS.get(plot)).clone();
            calibration = view.getCalibration().copy();
            plotBounds = new Rectangle((Rectangle) FRAME.get(plot));
            raster = plot.getProcessor().getBufferedImage();
        }
        void render(boolean logarithmic) {
            try { LOG.setBoolean(plot, logarithmic); plot.draw(view, stats); raster = plot.getProcessor().getBufferedImage(); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
        void release() { view.flush(); plot.flush(); }
    }
}
