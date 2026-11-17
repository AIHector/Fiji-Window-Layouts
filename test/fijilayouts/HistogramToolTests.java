package fijilayouts;

import ij.*;
import ij.gui.*;
import ij.measure.Calibration;
import ij.plugin.frame.ContrastAdjuster;
import ij.process.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Reproduce native contrast corruption, then exercise the real menu and B&C against a non-image tool. */
public final class HistogramToolTests {
    static int passed;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
    static void pause(int ms)throws Exception{Thread.sleep(ms);EventQueue.invokeAndWait(()->{});}
    static void await(BooleanSupplier condition)throws Exception{for(int i=0;i<180;i++){if(condition.getAsBoolean())return;pause(30);}throw new AssertionError("Timed out");}
    static Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Object invoke(String clazz,String method,Class<?>[] types,Object...args)throws Exception{Method m=Class.forName(clazz).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);}
    static void brightness(ContrastAdjuster adjuster,int value)throws Exception{
        Scrollbar slider=(Scrollbar)field(adjuster,"brightnessSlider");
        EventQueue.invokeAndWait(()->{slider.setValue(value);adjuster.adjustmentValueChanged(new AdjustmentEvent(slider,AdjustmentEvent.ADJUSTMENT_VALUE_CHANGED,AdjustmentEvent.TRACK,value));});pause(250);
    }
    static int[] raster(HistogramTool tool){return tool.model.raster.getRGB(0,0,tool.model.raster.getWidth(),tool.model.raster.getHeight(),null,0,tool.model.raster.getWidth());}
    static HistogramTool open(ImagePlus source,String options)throws Exception{
        int before=HistogramTool.openTools().size();IJ.run(source,"Histogram",options);
        await(()->HistogramTool.openTools().size()==before+1);
        HistogramTool tool=HistogramTool.openTools().get(before);ready(tool);return tool;
    }
    static void ready(HistogramTool tool)throws Exception{await(()->tool.model!=null&&tool.worker==null);pause(100);}
    static void hover(ImageCanvas canvas,int x,int y)throws Exception{EventQueue.invokeAndWait(()->canvas.dispatchEvent(new MouseEvent(canvas,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,x,y,0,false)));pause(100);}
    public static void main(String[] args)throws Exception{
      Thread watchdog=new Thread(()->{try{Thread.sleep(90000);}catch(InterruptedException ignored){}System.err.println("Histogram tool test watchdog");System.exit(2);});watchdog.setDaemon(true);watchdog.start();
      try{
        new ImageJ(ImageJ.NO_SHOW);Prefs.set("fijilayouts.auto",false);Prefs.set("fijilayouts.follow","fit");Prefs.set(HistogramInteraction.PREF,true);
        // Establish why classification guards alone cannot solve this: native Histogram is an editable RGB image.
        ByteProcessor data=new ByteProcessor(32,24);for(int i=0;i<data.getPixelCount();i++)data.set(i,i<96?6:i<224?187:42);
        ImagePlus source=new ImagePlus("Grayscale source",data);source.show();pause(100);
        HistogramWindow nativeHistogram=new HistogramWindow(source);pause(150);
        int[] nativePixels=((int[])nativeHistogram.getImagePlus().getProcessor().getPixels()).clone();
        WindowManager.setCurrentWindow(nativeHistogram);ContrastAdjuster bc=new ContrastAdjuster();bc.run("");pause(150);brightness(bc,20);
        check(!Arrays.equals(nativePixels,(int[])nativeHistogram.getImagePlus().getProcessor().getPixels()),"native baseline reproduces B&C altering Histogram rendering pixels");
        check(data.get(0)==6&&data.get(100)==187,"native failure edits graph instead of source pixels");
        EventQueue.invokeAndWait(()->bc.close());nativeHistogram.getImagePlus().changes=false;nativeHistogram.close();
        new WindowLayouts_().run("install");
        ((ij.plugin.PlugIn)Class.forName("undohistory.Undo_History").newInstance()).run("start");
        check(Menus.getCommands().get("Histogram").equals(HistogramToolCommand.class.getName()),"Analyze Histogram command resolves to actual tool implementation");
        byte[] original=((byte[])data.getPixels()).clone();int count=WindowManager.getImageCount();Rectangle sourceBounds=source.getWindow().getBounds();
        HistogramTool tool=open(source,"");
        check(!(tool instanceof Object && ((Object)tool) instanceof ImageWindow),"Histogram is a tool Frame, not an ImageWindow");
        check(!((Object)tool.graph instanceof ImageCanvas),"graph is a presentation component, not an editable ImageCanvas");
        check(WindowManager.getImageCount()==count&&WindowManager.getCurrentImage()==source,"opening Histogram adds no image and retains source as active image");
        check(Arrays.asList(WindowManager.getNonImageWindows()).contains(tool),"Histogram is registered in Fiji's non-image tool windows");
        check(!Arrays.asList(WindowManager.getImageTitles()).contains(tool.getTitle()),"Histogram does not appear in Fiji image selectors");
        check(((java.util.List<?>)invoke("sidetoolbar.ShelfView","toolWindows",new Class<?>[0])).contains(tool),"Histogram appears in toolbar Tools category");
        check(((java.util.List<?>)invoke("sidetoolbar.ShelfView","images",new Class<?>[0])).size()==count,"toolbar Images category contains only source data");
        check(!Engine.visibleImages().contains(tool.model.plot)&&tool.model.plot.getWindow()==null&&tool.model.view.getWindow()==null,"private statistics renderer never owns or registers an image window");
        Object engine=invoke("undohistory.Engine","get",new Class<?>[0]);Method history=engine.getClass().getDeclaredMethod("view",int.class);history.setAccessible(true);
        check(history.invoke(engine,tool.model.plot.getID())==null&&history.invoke(engine,tool.model.view.getID())==null,"private graph renderer never enters Undo History");
        check(sourceBounds.equals(source.getWindow().getBounds()),"opening tool leaves source window view intact");
        check(tool.model.counts[6]==96&&tool.model.counts[187]==128&&tool.model.counts[42]==544,"tool counts match independently constructed source distribution");
        BufferedImage panelImage=new BufferedImage(tool.getComponent(0).getWidth(),tool.getComponent(0).getHeight(),BufferedImage.TYPE_INT_RGB);
        EventQueue.invokeAndWait(()->{Graphics2D g=panelImage.createGraphics();tool.getComponent(0).printAll(g);g.dispose();});ImageIO.write(panelImage,"png",new File(args[0],"histogram-tool-panel.png"));
        check(tool.table().size()==256&&tool.tableText().contains("187.0\t128"),"List and Copy use source histogram data");
        EventQueue.invokeAndWait(()->tool.list.doClick());pause(100);
        check(WindowManager.getWindow(tool.getTitle())!=null,"List opens a separate histogram results table");
        hover(source.getCanvas(),0,0);
        check(tool.sampledValue==6&&tool.sampledCount==96,"source-pointer readout reports source value and frequency");
        int[] graphBefore=raster(tool);double value=tool.sampledValue;long frequency=tool.sampledCount;
        EventQueue.invokeAndWait(()->tool.inspectGraph(25,tool.graph.getHeight()-5));
        check(tool.sampledValue==value&&tool.sampledCount==frequency,"hover over labels and graph margins leaves readout unchanged");
        EventQueue.invokeAndWait(()->tool.graph.dispatchEvent(new MouseEvent(tool.graph,MouseEvent.MOUSE_DRAGGED,System.currentTimeMillis(),InputEvent.BUTTON1_DOWN_MASK,20,20,0,false)));
        check(Arrays.equals(original,(byte[])data.getPixels())&&Arrays.equals(graphBefore,raster(tool))&&source.getRoi()==null,"graph dragging cannot draw on either graph or source");
        EventQueue.invokeAndWait(()->tool.windowActivated(new WindowEvent(tool,WindowEvent.WINDOW_ACTIVATED)));
        check(WindowManager.getCurrentImage()==source&&WindowManager.getFrontWindow()==tool,"activating Histogram selects its source as image and itself as tool");
        ContrastAdjuster adjuster=new ContrastAdjuster();adjuster.run("");pause(100);double min=source.getDisplayRangeMin();brightness(adjuster,20);
        check(source.getDisplayRangeMin()!=min,"actual B&C slider changes source display range after Histogram activation");
        check(Arrays.equals(original,(byte[])data.getPixels()),"B&C display adjustment leaves grayscale source values intact");
        check(Arrays.equals(graphBefore,raster(tool)),"actual B&C slider cannot alter Histogram graph colours or pixels");
        EventQueue.invokeAndWait(()->tool.live.doClick());ready(tool);
        int[] liveBefore=raster(tool);brightness(adjuster,200);ready(tool);
        check(Arrays.equals(liveBefore,raster(tool))&&tool.model.counts[6]==96,"Live Histogram graph and counts remain invariant under display contrast changes");
        data.set(0,77);source.updateAndDraw();await(()->tool.model.counts[77]==1&&tool.worker==null);
        check(tool.model.counts[6]==95,"Live still recalculates when source pixel data actually changes");
        hover(source.getCanvas(),0,0);check(tool.sampledValue==77&&tool.sampledCount==1,"Live pointer readout follows updated source data");
        EventQueue.invokeAndWait(()->tool.live.doClick());data.set(0,89);source.updateAndDraw();pause(180);
        check(tool.model.counts[77]==1&&tool.sampledValue==89&&tool.sampledCount==0,"Live off preserves distribution while sampling current source pixels");
        EventQueue.invokeAndWait(()->tool.refresh.doClick());ready(tool);check(tool.model.counts[89]==1&&tool.model.counts[77]==0,"Refresh explicitly recalculates frozen distribution");
        int[] linear=raster(tool);EventQueue.invokeAndWait(()->tool.log.doClick());check(!Arrays.equals(linear,raster(tool)),"Log changes graph scale deliberately");
        EventQueue.invokeAndWait(()->tool.log.doClick());check(Arrays.equals(linear,raster(tool)),"Log toggles back without changing histogram data");
        ImageIO.write(tool.model.raster,"png",new File(args[0],"histogram-tool-graph.png"));
        ImagePlus other=new ImagePlus("Other source",new ByteProcessor(32,24));other.show();HistogramTool otherTool=open(other,"");
        hover(other.getCanvas(),0,0);check(otherTool.sampledValue==0&&otherTool.sampledCount==768&&tool.sampledValue==89,"multiple Histogram tools remain bound to distinct sources");
        Prefs.set(HistogramInteraction.PREF,false);hover(source.getCanvas(),0,4);check(tool.sampledValue==89,"pointer preference disables source sampling in tool");Prefs.set(HistogramInteraction.PREF,true);
        SmoothCanvas[] smooth={null};EventQueue.invokeAndWait(()->{smooth[0]=SmoothCanvas.install(source.getWindow());smooth[0].zoom(1.137,16,16);});hover(smooth[0],16,16);
        int x=smooth[0].offScreenX(16),y=smooth[0].offScreenY(16);check(tool.sampledValue==data.get(x,y),"source sampling respects fractional smooth-zoom coordinates");
        check(source.getCanvas()==smooth[0],"Histogram retains accepted smooth-zoom canvas");
        int beforeClose=WindowManager.getImageCount();EventQueue.invokeAndWait(()->otherTool.close());check(WindowManager.getImageCount()==beforeClose&&other.getWindow()!=null,"closing Histogram does not close or remove its source");
        source.changes=false;source.close();pause(150);
        check(tool.source==null&&!tool.refresh.isEnabled()&&!tool.live.isEnabled()&&Double.isNaN(tool.sampledValue),"closing source freezes snapshot and disables sampling without rebinding");
        ImagePlus reopened=new ImagePlus("Grayscale source",new ByteProcessor(32,24));reopened.show();hover(reopened.getCanvas(),0,0);check(tool.source==null,"same-title reopened image cannot take over a closed-source Histogram");
        EventQueue.invokeAndWait(()->{adjuster.close();tool.close();});check(!HistogramTool.openTools().contains(tool)&&tool.model==null,"closing tool releases private rendering and listener ownership");
        ShortProcessor shorts=new ShortProcessor(4,4);for(int i=0;i<16;i++)shorts.set(i,i<8?1000:2000);ImagePlus calibrated=new ImagePlus("Calibrated",shorts);calibrated.getCalibration().setFunction(Calibration.STRAIGHT_LINE,new double[]{10,2},"units");calibrated.show();
        HistogramTool calibratedTool=open(calibrated,"");hover(calibrated.getCanvas(),0,0);check(calibratedTool.sampledValue==2010&&calibratedTool.sampledCount==8,"calibrated 16-bit source values and grouped bin counts stay correct");
        HistogramTool calibratedRange=open(calibrated,"bins=4 x_min=2010 x_max=4010 y_max=Auto");
        check(calibratedRange.model.stats.histMin==1000&&calibratedRange.model.stats.histMax==2000&&Arrays.stream(calibratedRange.model.counts).sum()==16,"explicit calibrated range is converted exactly once by ImageJ statistics");
        int[] sixteen=raster(calibratedTool);calibrated.setDisplayRange(1250,1750);calibrated.updateAndDraw();EventQueue.invokeAndWait(()->calibratedTool.refresh.doClick());ready(calibratedTool);check(Arrays.equals(sixteen,raster(calibratedTool)),"16-bit histogram is independent of source display range even after Refresh");
        ImagePlus floating=new ImagePlus("Floating",new FloatProcessor(4,1,new float[]{-2,0,1,2}));floating.show();HistogramTool floats=open(floating,"bins=4 use x_min=-2 x_max=2 y_max=Auto");
        check(floats.model.counts.length==4&&Arrays.stream(floats.model.counts).sum()==4,"float Histogram command supports bins and native macro option labels");
        ImageStack stack=new ImageStack(2,2);stack.addSlice(new ByteProcessor(2,2,new byte[]{1,1,1,1}));stack.addSlice(new ByteProcessor(2,2,new byte[]{2,2,2,2}));ImagePlus stacked=new ImagePlus("Stack",stack);stacked.show();HistogramTool stackTool=open(stacked,"stack");
        check(stackTool.model.counts[1]==4&&stackTool.model.counts[2]==4&&!stackTool.live.isEnabled(),"whole-stack histogram counts all planes and exposes frozen stack semantics");
        ImagePlus color=new ImagePlus("Colour",new ColorProcessor(2,1,new int[]{0x0a141e,0x0a141e}));color.show();HistogramTool rgb=open(color,"");
        for(int i=0;i<3;i++){EventQueue.invokeAndWait(()->rgb.channel.doClick());ready(rgb);}
        hover(color.getCanvas(),0,0);check(rgb.model.mode==3&&rgb.sampledValue==10&&rgb.sampledCount==2,"RGB control selects individual channels with correct source sampling");
        EventQueue.invokeAndWait(()->other.setRoi(new OvalRoi(0,0,10,10)));
        HistogramTool roi=open(other,"");long expected=other.getStatistics().pixelCount;
        check(Arrays.stream(roi.model.counts).sum()==expected&&expected<768,"nonrectangular source selection limits distribution to selected pixels");
        EventQueue.invokeAndWait(()->roi.live.doClick());ready(roi);EventQueue.invokeAndWait(()->other.setRoi(0,0,2,3));await(()->Arrays.stream(roi.model.counts).sum()==6&&roi.worker==null);
        check(Arrays.stream(roi.model.counts).sum()==6,"Live responds to source selection changes");
        if(args.length>1){
            ImagePlus actual=IJ.openImage(args[1]);check(actual!=null,"provided source TIFF opens read-only for regression");actual.show();HistogramTool actualTool=open(actual,"");
            check(Arrays.equals(actual.getStatistics().histogram,actualTool.model.plot.getHistogram()),"actual TIFF distribution matches ImageJ source statistics");
            int[] before=raster(actualTool);EventQueue.invokeAndWait(()->actualTool.windowActivated(new WindowEvent(actualTool,WindowEvent.WINDOW_ACTIVATED)));
            ContrastAdjuster actualBC=new ContrastAdjuster();actualBC.run("");pause(150);brightness(actualBC,20);
            check(WindowManager.getCurrentImage()==actual&&Arrays.equals(before,raster(actualTool)),"actual TIFF B&C changes target the source while Histogram stays unchanged");
            EventQueue.invokeAndWait(()->actualBC.close());actual.changes=false;actual.close();EventQueue.invokeAndWait(()->actualTool.close());
        }
        System.out.println(passed+" actual Histogram tool checks passed");System.exit(0);
      }catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
}
