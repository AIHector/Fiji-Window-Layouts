package fijilayouts;

import ij.*;
import ij.gui.*;
import ij.measure.Calibration;
import ij.process.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Source-pointer samples, inert labels, native plot hover, live redraws, fractional views and source identity. */
public final class HistogramInteractionTests {
    static int passed;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
    static void pause(int ms)throws Exception{Thread.sleep(ms);EventQueue.invokeAndWait(()->{});}
    static void waitFor(BooleanSupplier condition)throws Exception{for(int i=0;i<120;i++){if(condition.getAsBoolean())return;pause(30);}throw new AssertionError("Timed out");}
    static Object field(HistogramWindow window,String name)throws Exception{Field f=HistogramWindow.class.getDeclaredField(name);f.setAccessible(true);return f.get(window);}
    static void motion(ImageCanvas canvas,int x,int y,int kind)throws Exception{
        EventQueue.invokeAndWait(()->canvas.dispatchEvent(new MouseEvent(canvas,kind,System.currentTimeMillis(),0,x,y,0,false)));pause(80);
    }
    static void hover(ImageCanvas canvas,int x,int y)throws Exception{motion(canvas,x,y,MouseEvent.MOUSE_MOVED);}
    static void click(HistogramWindow window,String name)throws Exception{
        Button button=(Button)field(window,name);EventQueue.invokeAndWait(()->window.actionPerformed(new ActionEvent(button,ActionEvent.ACTION_PERFORMED,button.getLabel())));pause(120);
    }
    static int[] pixels(HistogramWindow window){return ((int[])window.getImagePlus().getProcessor().getPixels()).clone();}
    static HistogramWindow histogram(ImagePlus source)throws Exception{
        IJ.run(source,"Histogram","");HistogramWindow histogram=(HistogramWindow)WindowManager.getCurrentImage().getWindow();
        waitFor(()->HistogramInteraction.binding(histogram)!=null);return histogram;
    }
    static HistogramInteraction.Sample sample(HistogramWindow window){return HistogramInteraction.binding(window).last;}
    static void sampleIs(HistogramWindow window,double value,long count,String why){
        HistogramInteraction.Sample sample=sample(window);check(sample!=null&&sample.value==value&&sample.count==count,why+" (got "+(sample==null?"none":sample.value+" / "+sample.count)+")");
    }
    public static void main(String[] args)throws Exception{
      try{
        new ImageJ(ImageJ.NO_SHOW);Prefs.set("fijilayouts.auto",false);Prefs.set("fijilayouts.follow","fit");Prefs.set(HistogramInteraction.PREF,true);
        new WindowLayouts_().run("install");
        ByteProcessor data=new ByteProcessor(32,24);for(int i=0;i<data.getPixelCount();i++)data.set(i,i<96?6:i<224?187:42);
        ImagePlus source=new ImagePlus("Source pointer test",data);source.show();pause(120);
        HistogramWindow histogram=histogram(source);ImageCanvas canvas=histogram.getCanvas();Dimension size=histogram.getSize();
        check(canvas.getClass()==ImageCanvas.class,"native histogram canvas is retained");
        check(HistogramInteraction.binding(histogram).sourceID==source.getID(),"binding is the original source ID");
        int motionListeners=canvas.getMouseMotionListeners().length;
        EventQueue.invokeAndWait(()->{HistogramInteraction.install(histogram);HistogramInteraction.install(histogram);});
        check(canvas.getMouseMotionListeners().length==motionListeners,"repeat installation does not duplicate listeners");
        byte[] original=((byte[])data.getPixels()).clone();
        hover(source.getCanvas(),0,0);sampleIs(histogram,6,96,"source pointer reports actual pixel value and independently known source frequency");
        check(((int[])histogram.getImagePlus().getProcessor().getPixels())[0]!=6,"sample comes from source pixels rather than histogram rendering pixels");
        ImageIO.write(histogram.getImagePlus().getBufferedImage(),"png",new File(args[0],"histogram-source-hover.png"));
        int[] shown=pixels(histogram);Rectangle plot=(Rectangle)field(histogram,"frame");
        int valueY=((Integer)field(histogram,"row4"))+15;
        hover(canvas,plot.x+100,valueY);
        check(Arrays.equals(shown,pixels(histogram)),"hovering over Value/Count text cannot change readout");
        hover(canvas,plot.x+60,plot.y+plot.height+45);
        check(Arrays.equals(shown,pixels(histogram)),"hovering statistics below graph cannot select a bin");
        hover(canvas,plot.x+100,plot.y-1);hover(canvas,plot.x-1,plot.y+20);
        check(Arrays.equals(shown,pixels(histogram)),"hovering plot margins leaves source readout intact");
        hover(canvas,plot.x+187,plot.y+30);
        check(!Arrays.equals(shown,pixels(histogram))&&!HistogramInteraction.binding(histogram).fromSource,"hovering inside graph still inspects native bins");
        ImageIO.write(histogram.getImagePlus().getBufferedImage(),"png",new File(args[0],"histogram-plot-hover.png"));
        int[] plotShown=pixels(histogram);hover(canvas,plot.x+2,valueY);
        check(Arrays.equals(plotShown,pixels(histogram)),"label hover preserves a graph-selected readout too");
        hover(source.getCanvas(),0,4);sampleIs(histogram,187,128,"returning to source selects that pixel's intensity");
        check(Arrays.equals(original,(byte[])data.getPixels())&&source.getRoi()==null,"pointer inspection changes neither source pixels nor selection");
        check(histogram.getSize().equals(size)&&histogram.getCanvas()==canvas,"pointer interaction preserves native tool size and canvas identity");
        ByteProcessor otherData=new ByteProcessor(16,16);otherData.setValue(200);otherData.fill();
        ImagePlus other=new ImagePlus("Unrelated image",otherData);other.show();HistogramWindow otherHistogram=histogram(other);
        WindowManager.setCurrentWindow(other.getWindow());hover(source.getCanvas(),0,0);
        sampleIs(histogram,6,96,"another active image cannot redirect original histogram source");
        check(sample(otherHistogram)==null,"source hover does not update an unrelated histogram");
        hover(other.getCanvas(),2,2);sampleIs(otherHistogram,200,256,"second histogram follows its own source");
        sampleIs(histogram,6,96,"second source does not alter first histogram");
        Prefs.set(HistogramInteraction.PREF,false);hover(source.getCanvas(),0,4);sampleIs(histogram,6,96,"source linking can be disabled independently of native plot hover");
        Prefs.set(HistogramInteraction.PREF,true);hover(source.getCanvas(),0,4);sampleIs(histogram,187,128,"source linking resumes when enabled");
        EventQueue.invokeAndWait(()->Engine.fitImage(source.getWindow(),source,new Rectangle(120,120,400,320),0));pause(150);
        final SmoothCanvas[] smooth={null};EventQueue.invokeAndWait(()->{smooth[0]=SmoothCanvas.install(source.getWindow());smooth[0].zoom(1.137,100,100);});
        int sx=smooth[0].screenXD(5.2),sy=smooth[0].screenYD(4.2);
        int ix=smooth[0].offScreenX(sx),iy=smooth[0].offScreenY(sy),value=data.get(ix,iy),count=data.getHistogram()[value];
        hover(smooth[0],sx,sy);sampleIs(histogram,value,count,"fractional source zoom samples the displayed image coordinate");
        check(source.getCanvas()==smooth[0],"linking does not replace the accepted fractional canvas");
        motion(smooth[0],sx,sy,MouseEvent.MOUSE_DRAGGED);sampleIs(histogram,value,count,"source drag events retain correct coordinate mapping");
        hover(smooth[0],sx,sy);
        click(histogram,"log");sampleIs(histogram,value,count,"native Log redraw retains source-pointer readout");click(histogram,"log");
        click(histogram,"live");data.set(ix,iy,77);source.updateAndDraw();
        waitFor(()->sample(histogram)!=null&&sample(histogram).value==77&&sample(histogram).count==1);
        sampleIs(histogram,77,1,"Live redraw recomputes count and retains sampled source position");
        check(histogram.getHistogram()[value]==count-1,"native Live histogram still reflects source edit");
        click(histogram,"live");data.set(ix,iy,89);source.updateAndDraw();pause(250);
        sampleIs(histogram,89,0,"with Live off value follows pixel but count retains the histogram snapshot");
        int[] held=pixels(histogram);hover(smooth[0],-1,0);check(Arrays.equals(held,pixels(histogram)),"outside-image pointer cannot sample a clamped edge pixel");
        source.changes=false;source.close();pause(250);
        check(Double.isNaN(sample(histogram).value)&&sample(histogram).count==-1,"closing source clears sampled readout without rebinding");
        ImagePlus reopened=new ImagePlus("Source pointer test",new ByteProcessor(32,24));reopened.show();pause(100);hover(reopened.getCanvas(),0,0);
        check(Double.isNaN(sample(histogram).value),"new image with same title does not impersonate closed source");
        histogram.getImagePlus().changes=false;histogram.close();pause(150);
        check(HistogramInteraction.binding(histogram)==null&&Arrays.asList(canvas.getMouseMotionListeners()).contains(canvas),"closing histogram removes binding and restores native listener");
        ShortProcessor shorts=new ShortProcessor(4,4);for(int i=0;i<16;i++)shorts.set(i,i<8?1000:2000);
        ImagePlus calibrated=new ImagePlus("Calibrated 16-bit source",shorts);calibrated.getCalibration().setFunction(Calibration.STRAIGHT_LINE,new double[]{10,2},"units");calibrated.show();
        HistogramWindow calibratedHistogram=new HistogramWindow("Calibrated histogram",calibrated,256);waitFor(()->HistogramInteraction.binding(calibratedHistogram)!=null);
        hover(calibrated.getCanvas(),0,0);
        check(sample(calibratedHistogram).value==2010&&sample(calibratedHistogram).count==8,"calibrated 16-bit value and grouped-bin count use source calibration");
        ImageStatistics stats=new ImageStatistics();stats.histMin=-2;stats.histMax=2;stats.binSize=1;
        check(HistogramInteraction.bin(-2,stats,4)==0&&HistogramInteraction.bin(2,stats,4)==3,"histogram endpoints map to first and last bins");
        check(HistogramInteraction.bin(-3,stats,4)==-1&&HistogramInteraction.bin(Double.NaN,stats,4)==-1,"out-of-range and missing values cannot invent a bin");
        stats.histMin=stats.histMax=7;stats.binSize=0;
        check(HistogramInteraction.bin(7,stats,256)==0,"constant-valued histogram has a defined bin");
        ColorProcessor colors=new ColorProcessor(1,1,new int[]{0xff0a141e});colors.setRGBWeights(1d/3,1d/3,1d/3);
        check(HistogramInteraction.rawValue(colors,0,0,3)==10&&HistogramInteraction.rawValue(colors,0,0,4)==20&&HistogramInteraction.rawValue(colors,0,0,5)==30,"individual RGB channels sample their own component");
        check(HistogramInteraction.rawValue(colors,0,0,0)==20&&HistogramInteraction.rawValue(colors,0,0,1)==18,"RGB intensity modes follow native rounding and weighting");
        check(Double.isNaN(HistogramInteraction.rawValue(colors,0,0,2)),"combined RGB does not pretend three components are one scalar sample");
        System.out.println(passed+" linked histogram interaction checks passed");System.exit(0);
      }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
