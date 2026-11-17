package fijilayouts;

import ij.ImagePlus;
import ij.IJ;
import ij.Prefs;
import ij.gui.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;

/** A persistent fractional viewport. No preview is handed back to a different renderer at gesture end. */
final class SmoothCanvas extends ImageCanvas {
    private ViewportLayout viewportLayout;
    private double x, y, publishedMag;
    private Rectangle published;
    private int publishedWidth, publishedHeight, publishedImageWidth, publishedImageHeight;
    private boolean fractional;
    private BufferedImage buffer;
    private static final Method DRAW_ROI = method("drawRoi", Roi.class, Graphics.class);
    private static final Method DRAW_OVERLAY = method("drawOverlay", Overlay.class, Graphics.class);
    private static final Method UPDATE_PASTE = roiMethod("updatePaste");
    private static final Method DRAW_INDICATOR = method("drawZoomIndicator", Graphics.class);

    SmoothCanvas(ImagePlus image) { super(image); }

    private static Method method(String name, Class<?>... types) {
        try { Method method = ImageCanvas.class.getDeclaredMethod(name, types); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException | RuntimeException ex) { return null; }
    }

    private static Method roiMethod(String name) {
        try { Method method = Roi.class.getDeclaredMethod(name); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException | RuntimeException ex) { return null; }
    }

    /** Replace only the standard canvas, preserving the window, its layout, state and external listeners. */
    static SmoothCanvas install(ImageWindow window) {
        ImageCanvas old = window.getCanvas();
        if (old instanceof SmoothCanvas) return (SmoothCanvas) old;
        if (old == null || old.getClass() != ImageCanvas.class || window instanceof PlotWindow
                || window.getLayout() == null || window.getLayout().getClass() != ImageLayout.class
                || DRAW_ROI == null || DRAW_OVERLAY == null || DRAW_INDICATOR == null || UPDATE_PASTE == null) return null;
        Field canvas = null;
        SmoothCanvas next = null;
        LayoutManager layout = window.getLayout();
        Rectangle bounds = old.getBounds();
        int index = window.getComponentZOrder(old);
        boolean changed = false;
        try {
            canvas = ImageWindow.class.getDeclaredField("ic"); canvas.setAccessible(true);
            next = new SmoothCanvas(window.getImagePlus());
            next.viewportLayout = new ViewportLayout(next);
            for (Field field : ImageLayout.class.getDeclaredFields())
                if (!Modifier.isStatic(field.getModifiers()) && !field.getName().equals("ic")) {
                    field.setAccessible(true); field.set(next.viewportLayout, field.get(layout));
                }
            // Canvas internals include the ROI Manager overlay, popup policy, scale flag and mouse status.
            for (Field field : ImageCanvas.class.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true); field.set(next, field.get(old));
            }
            for (MouseListener listener : old.getMouseListeners()) if (listener != old) next.addMouseListener(listener);
            for (MouseMotionListener listener : old.getMouseMotionListeners()) if (listener != old) next.addMouseMotionListener(listener);
            for (MouseWheelListener listener : old.getMouseWheelListeners()) next.addMouseWheelListener(listener);
            // The constructor already added ImageJ's key listener; transfer any others without duplication.
            for (KeyListener listener : old.getKeyListeners()) {
                boolean present = false;
                for (KeyListener existing : next.getKeyListeners()) if (existing == listener) present = true;
                if (!present) next.addKeyListener(listener);
            }
            for (ComponentListener listener : old.getComponentListeners()) next.addComponentListener(listener);
            for (FocusListener listener : old.getFocusListeners()) next.addFocusListener(listener);
            next.srcRect = new Rectangle(old.getSrcRect());
            next.resetDoubleBuffer();
            next.setCursor(old.getCursor()); next.setBackground(old.getBackground()); next.setForeground(old.getForeground());
            next.setFont(old.getFont()); next.setEnabled(old.isEnabled());
            boolean focus = old.hasFocus();
            synchronized (window.getTreeLock()) {
                changed = true;
                window.remove(old); canvas.set(window, next); window.setLayout(next.viewportLayout);
                window.add(next, index); next.setBounds(bounds); next.holdViewport(); window.validate();
            }
            Roi roi = window.getImagePlus().getRoi();
            if (roi != null) roi.setImage(window.getImagePlus());
            if (focus) next.requestFocus();
            return next;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (changed) {
                try {
                    synchronized (window.getTreeLock()) {
                        if (next != null && next.getParent() == window) window.remove(next);
                        canvas.set(window, old); window.setLayout(layout);
                        if (old.getParent() != window) window.add(old, index);
                        old.setBounds(bounds); window.validate();
                    }
                    Roi roi = window.getImagePlus().getRoi();
                    if (roi != null) roi.setImage(window.getImagePlus());
                } catch (ReflectiveOperationException | RuntimeException rollback) { ex.addSuppressed(rollback); }
            }
            IJ.log("Window Layouts: fractional zoom canvas unavailable: " + ex);
            return null;
        }
    }

    private void holdViewport() { if (viewportLayout != null) viewportLayout.hold(imp.getWindow()); }
    private void releaseViewport() { if (viewportLayout != null) viewportLayout.release(); }

    /** Keep the existing native layout policy and sizing, but do not reinterpret repaint as a resize request. */
    private static final class ViewportLayout extends ImageLayout {
        private Dimension heldSize;
        private int heldComponents;
        ViewportLayout(ImageCanvas canvas) { super(canvas); }
        void hold(ImageWindow window) {
            heldSize = window == null ? null : window.getSize();
            heldComponents = window == null ? 0 : window.getComponentCount();
        }
        void release() { heldSize = null; }
        @Override public void layoutContainer(Container target) {
            if (heldSize != null && heldSize.equals(target.getSize()) && heldComponents == target.getComponentCount()) return;
            release(); super.layoutContainer(target);
        }
    }

    @Override public void zoomIn(int sx, int sy) { fractional = false; releaseViewport(); super.zoomIn(sx,sy); }
    @Override public void zoomOut(int sx, int sy) { fractional = false; releaseViewport(); super.zoomOut(sx,sy); }
    @Override public void unzoom() { fractional = false; releaseViewport(); super.unzoom(); }
    @Override public void zoom100Percent() { fractional = false; releaseViewport(); super.zoom100Percent(); }

    private void sync() {
        if (fractional && (published == null || srcRect.x != published.x || srcRect.y != published.y
                || magnification != publishedMag || getWidth() != publishedWidth || getHeight() != publishedHeight
                || imp.getWidth() != publishedImageWidth || imp.getHeight() != publishedImageHeight)) fractional = false;
        if (!fractional) { x = srcRect.x; y = srcRect.y; }
    }

    double originX() { sync(); return x; }
    double originY() { sync(); return y; }

    void zoom(double factor, double px, double py) {
        if (!Double.isFinite(factor) || factor <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
        sync();
        int iw = imp.getWidth(), ih = imp.getHeight(), cw = getWidth(), ch = getHeight();
        double minimum = Math.max(cw / (double) iw, ch / (double) ih);
        double next = Math.max(Math.min(minimum, magnification), Math.min(Pan.MAX_MAG, magnification * factor));
        px = Math.max(0, Math.min(cw, px)); py = Math.max(0, Math.min(ch, py));
        double nx = Pan.clamp(x + px / magnification - px / next, iw - cw / next);
        double ny = Pan.clamp(y + py / magnification - py / next, ih - ch / next);
        publish(nx, ny, next);
    }

    void pan(double dx, double dy) {
        sync();
        publish(Pan.clamp(x + dx, imp.getWidth() - getWidth() / magnification),
                Pan.clamp(y + dy, imp.getHeight() - getHeight() / magnification), magnification);
    }

    private void publish(double nx, double ny, double mag) {
        int iw = imp.getWidth(), ih = imp.getHeight();
        int sw = Math.max(1, Math.min(iw, (int) Math.ceil(getWidth() / mag)));
        int sh = Math.max(1, Math.min(ih, (int) Math.ceil(getHeight() / mag)));
        // This rectangle remains the native compatibility view. Rendering and coordinate mapping retain nx/ny.
        srcRect.setBounds((int) Math.max(0, Math.min(iw-sw, Math.round(nx))),
                (int) Math.max(0, Math.min(ih-sh, Math.round(ny))), sw, sh);
        magnification = mag; x = nx; y = ny; fractional = true;
        published = new Rectangle(srcRect); publishedMag = mag;
        publishedWidth = getWidth(); publishedHeight = getHeight();
        publishedImageWidth = iw; publishedImageHeight = ih;
        holdViewport(); imp.setTitle(imp.getTitle()); repaint();
    }

    @Override public void setSourceRect(Rectangle rect) { fractional = false; releaseViewport(); super.setSourceRect(rect); }
    @Override public void setMagnification(double mag) { fractional = false; releaseViewport(); super.setMagnification(mag); }
    @Override public int offScreenX(int sx) { return (int) Math.floor(originX() + sx / magnification); }
    @Override public int offScreenY(int sy) { return (int) Math.floor(originY() + sy / magnification); }
    @Override public int offScreenX2(int sx) { return (int) Math.round(originX() + sx / magnification); }
    @Override public int offScreenY2(int sy) { return (int) Math.round(originY() + sy / magnification); }
    @Override public double offScreenXD(int sx) { return originX() + sx / magnification; }
    @Override public double offScreenYD(int sy) { return originY() + sy / magnification; }
    @Override public int screenX(int ox) { return (int)((ox - originX()) * magnification); }
    @Override public int screenY(int oy) { return (int)((oy - originY()) * magnification); }
    @Override public int screenXD(double ox) { return (int)((ox - originX()) * magnification); }
    @Override public int screenYD(double oy) { return (int)((oy - originY()) * magnification); }

    private double dragX, dragY;
    private int dragScreenX, dragScreenY;
    @Override public void mousePressed(MouseEvent event) {
        dragX = originX(); dragY = originY(); dragScreenX = event.getX(); dragScreenY = event.getY();
        super.mousePressed(event);
    }
    @Override protected void scroll(int sx, int sy) {
        publish(Pan.clamp(dragX+(dragScreenX-sx)/magnification,imp.getWidth()-getWidth()/magnification),
                Pan.clamp(dragY+(dragScreenY-sy)/magnification,imp.getHeight()-getHeight()/magnification),magnification);
    }

    @Override public void update(Graphics graphics) { paint(graphics); }

    @Override public void paint(Graphics graphics) {
        sync();
        if (!fractional) { super.paint(graphics); return; }
        try {
            if (imageUpdated) { imageUpdated = false; imp.updateImage(); }
            int width = getWidth(), height = getHeight();
            if (width <= 0 || height <= 0) return;
            Graphics2D destination = (Graphics2D) graphics;
            AffineTransform device = destination.getTransform();
            double scaleX = Math.max(1, Math.abs(device.getScaleX())), scaleY = Math.max(1, Math.abs(device.getScaleY()));
            int bw = (int)Math.ceil(width*scaleX), bh = (int)Math.ceil(height*scaleY);
            if (buffer == null || buffer.getWidth() != bw || buffer.getHeight() != bh)
                buffer = new BufferedImage(bw,bh,BufferedImage.TYPE_INT_RGB);
            Graphics2D g = buffer.createGraphics();
            try {
                g.scale(scaleX,scaleY); g.setColor(getBackground()); g.fillRect(0,0,width,height);
                boolean smooth = magnification < 1 || Prefs.interpolateScaledImages && magnification < 4;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
                        ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                AffineTransform imageTransform = new AffineTransform();
                imageTransform.scale(magnification,magnification); imageTransform.translate(-x,-y);
                g.drawImage(imp.getImage(),imageTransform,null);
                Roi roi = imp.getRoi(); if (roi != null) UPDATE_PASTE.invoke(roi);
                if (imp.getOverlay() != null) DRAW_OVERLAY.invoke(this,imp.getOverlay(),g);
                if (getShowAllList() != null) DRAW_OVERLAY.invoke(this,getShowAllList(),g);
                if (roi != null) DRAW_ROI.invoke(this,roi,g);
                if (srcRect.width < imp.getWidth() || srcRect.height < imp.getHeight()) DRAW_INDICATOR.invoke(this,g);
            } finally { g.dispose(); }
            destination.drawImage(buffer,0,0,width,height,null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("ImageJ overlay drawing failed",ex);
        } finally { setPaintPending(false); }
    }
}
