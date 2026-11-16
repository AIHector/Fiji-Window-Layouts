package fijilayouts;

import ij.ImageListener;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.HistogramWindow;
import ij.gui.ImageCanvas;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import java.awt.Cursor;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Source-bound histogram inspection. Native canvases, buttons, plots and statistics stay in place. */
final class HistogramInteraction implements ImageListener {
    static final String PREF = "fijilayouts.histogramSourcePointer";
    private static final HistogramInteraction INSTANCE = new HistogramInteraction();
    private static final Map<HistogramWindow, Binding> bindings = new HashMap<>();
    private static final Map<HistogramWindow, Timer> pending = new HashMap<>();
    private static final Field SOURCE = field("srcImageID"), FRAME = field("frame"), STATS = field("stats"),
            COUNTS = field("histogram"), CAL = field("cal"), RGB_MODE = field("rgbMode");
    private static final Method DRAW = drawMethod();
    private static boolean started;

    private static Field field(String name) {
        try { Field f=HistogramWindow.class.getDeclaredField(name);f.setAccessible(true);return f; }
        catch(ReflectiveOperationException|RuntimeException ex){return null;}
    }
    private static Method drawMethod() {
        try { Method m=HistogramWindow.class.getDeclaredMethod("drawValueAndCount",ImageProcessor.class,double.class,long.class);m.setAccessible(true);return m; }
        catch(ReflectiveOperationException|RuntimeException ex){return null;}
    }
    static boolean followsSource() { return Prefs.get(PREF,true); }
    static void start() {
        if(started)return;started=true;ImagePlus.addImageListener(INSTANCE);
    }
    static void install(Window window) {
        if(window==null||window.getClass()!=HistogramWindow.class||DRAW==null||SOURCE==null||FRAME==null
                ||STATS==null||COUNTS==null||CAL==null||RGB_MODE==null)return;
        if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->install(window));return;}
        HistogramWindow histogram=(HistogramWindow)window;
        if(bindings.containsKey(histogram)||pending.containsKey(histogram))return;
        if(bind(histogram))return;
        // WINDOW_OPENED can precede the HistogramWindow constructor's source/statistics initialization.
        final int[] attempts={0};
        Timer retry=new Timer(50,event->{
            if(!histogram.isDisplayable()||bind(histogram)||++attempts[0]>=30){
                ((Timer)event.getSource()).stop();pending.remove(histogram);
            }
        });
        pending.put(histogram,retry);retry.start();
    }
    private static boolean bind(HistogramWindow window) {
        try {
            ImageCanvas canvas=window.getCanvas();
            if(canvas==null||canvas.getClass()!=ImageCanvas.class||FRAME.get(window)==null
                    ||STATS.get(window)==null||COUNTS.get(window)==null)return false;
            int sourceID=SOURCE.getInt(window);
            ImagePlus source=WindowManager.getImage(sourceID);
            if(source==null||!ImageWindows.isDataWindow(source.getWindow()))return false;
            MouseMotionListener[] listeners=canvas.getMouseMotionListeners();boolean nativeListener=false;
            for(MouseMotionListener listener:listeners)if(listener==canvas)nativeListener=true;
            if(!nativeListener)return false;
            Binding binding=new Binding(window,canvas,sourceID);
            // Replace only the native motion listener, in its existing position; every other listener is retained.
            for(MouseMotionListener listener:listeners)canvas.removeMouseMotionListener(listener);
            for(MouseMotionListener listener:listeners)canvas.addMouseMotionListener(listener==canvas?binding:listener);
            bindings.put(window,binding);return true;
        } catch(ReflectiveOperationException|RuntimeException ex){Engine.debug("Histogram pointer link unavailable: "+ex);return false;}
    }
    static void closed(Window window) {
        if(!(window instanceof HistogramWindow))return;
        Timer retry=pending.remove(window);if(retry!=null)retry.stop();
        Binding binding=bindings.remove(window);if(binding!=null)binding.detach();
    }
    /** Called from the shared event listener; it also sees a later SmoothCanvas without attaching to it. */
    static void onMotion(MouseEvent event) {
        if(!followsSource()||bindings.isEmpty()||!(event.getSource() instanceof ImageCanvas))return;
        ImageCanvas canvas=(ImageCanvas)event.getSource();ImagePlus source=canvas.getImage();
        if(source==null||!ImageWindows.isDataWindow(source.getWindow()))return;
        int x=canvas.offScreenX(event.getX()),y=canvas.offScreenY(event.getY());
        if(event.getX()<0||event.getY()<0||event.getX()>=canvas.getWidth()||event.getY()>=canvas.getHeight()
                ||x<0||y<0||x>=source.getWidth()||y>=source.getHeight())return;
        for(Binding binding:bindings.values())if(binding.sourceID==source.getID()){
            binding.x=x;binding.y=y;binding.fromSource=true;binding.queue();
        }
    }
    public void imageOpened(ImagePlus image) {}
    public void imageClosed(ImagePlus image) {
        SwingUtilities.invokeLater(()->{
            for(Binding binding:new ArrayList<>(bindings.values())){
                if(binding.window.getImagePlus()==image)closed(binding.window);
                else if(binding.sourceID==image.getID()){
                    binding.fromSource=false;binding.draw(new Sample(Double.NaN,-1));
                }
            }
        });
    }
    public void imageUpdated(ImagePlus image) {
        if(!followsSource())return;
        SwingUtilities.invokeLater(()->{
            for(Binding binding:bindings.values())if(binding.fromSource
                    &&(binding.sourceID==image.getID()||binding.window.getImagePlus()==image))binding.queue();
        });
    }
    static Binding binding(HistogramWindow window) { return bindings.get(window); }
    static final class Sample {
        final double value;final long count;
        Sample(double value,long count){this.value=value;this.count=count;}
    }
    static int bin(double raw,ImageStatistics stats,int length) {
        if(!Double.isFinite(raw)||length<=0||raw<stats.histMin||raw>stats.histMax)return -1;
        if(!(stats.binSize>0))return raw==stats.histMin?0:-1;
        return Math.min(length-1,(int)Math.floor((raw-stats.histMin)/stats.binSize));
    }
    static double rawValue(ImageProcessor processor,int x,int y,int mode) {
        if(!(processor instanceof ColorProcessor))return processor.getf(x,y);
        int rgb=processor.getPixel(x,y),r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
        if(mode>=3&&mode<=5)return mode==3?r:mode==4?g:b;
        if(mode==2)return Double.NaN; // Combined RGB has three component values, not one scalar pixel value.
        ColorProcessor cp=(ColorProcessor)processor;
        double[] weights=cp.getRGBWeights();if(weights==null)weights=ColorProcessor.getWeightingFactors();
        if(mode==1)weights=cp.weightedHistogram()?new double[]{1d/3,1d/3,1d/3}:new double[]{.299,.587,.114};
        return Math.floor(r*weights[0]+g*weights[1]+b*weights[2]+.5);
    }
    static final class Binding implements MouseMotionListener {
        final HistogramWindow window;final ImageCanvas canvas;final int sourceID;
        int x,y;boolean fromSource,queued;Sample last;
        Binding(HistogramWindow window,ImageCanvas canvas,int sourceID){this.window=window;this.canvas=canvas;this.sourceID=sourceID;}
        void detach() {
            MouseMotionListener[] listeners=canvas.getMouseMotionListeners();
            for(MouseMotionListener listener:listeners)canvas.removeMouseMotionListener(listener);
            for(MouseMotionListener listener:listeners)canvas.addMouseMotionListener(listener==this?canvas:listener);
            fromSource=false;
        }
        public void mouseMoved(MouseEvent event) {
            try {
                Rectangle plot=(Rectangle)FRAME.get(window);
                if(plot==null||!plot.contains(canvas.offScreenX(event.getX()),canvas.offScreenY(event.getY()))){
                    canvas.setCursor(Cursor.getDefaultCursor());return; // Labels and statistics never select bins.
                }
                fromSource=false;canvas.setShowCursorStatus(true);canvas.mouseMoved(event);
            } catch(ReflectiveOperationException|RuntimeException ex){Engine.debug("Histogram hover: "+ex);}
        }
        public void mouseDragged(MouseEvent event){canvas.mouseDragged(event);}
        void queue() {
            if(queued)return;queued=true;
            SwingUtilities.invokeLater(()->{
                queued=false;
                if(!fromSource||!followsSource()||bindings.get(window)!=this||!window.isVisible())return;
                ImagePlus source=WindowManager.getImage(sourceID);
                if(source==null||!ImageWindows.isDataWindow(source.getWindow())||x>=source.getWidth()||y>=source.getHeight())return;
                try {
                    double raw=rawValue(source.getProcessor(),x,y,RGB_MODE.getInt(window));
                    long[] counts=(long[])COUNTS.get(window);ImageStatistics stats=(ImageStatistics)STATS.get(window);
                    int index=bin(raw,stats,counts.length);
                    Calibration calibration=(Calibration)CAL.get(window);
                    draw(new Sample(Double.isNaN(raw)?Double.NaN:calibration.getCValue(raw),Double.isNaN(raw)?-1:index<0?0:counts[index]));
                } catch(ReflectiveOperationException|RuntimeException ex){Engine.debug("Histogram source readout: "+ex);}
            });
        }
        void draw(Sample sample) {
            try {
                ImagePlus image=window.getImagePlus();if(image==null)return;
                DRAW.invoke(window,image.getProcessor(),sample.value,sample.count);last=sample;
                // Repaint the tool without recursively announcing another ImagePlus update.
                canvas.setImageUpdated();canvas.repaint();
            } catch(ReflectiveOperationException|RuntimeException ex){Engine.debug("Histogram readout drawing: "+ex);}
        }
    }
}
