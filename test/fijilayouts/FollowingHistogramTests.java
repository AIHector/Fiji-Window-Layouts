package fijilayouts;

import ij.*;
import ij.gui.*;
import ij.plugin.frame.PlugInFrame;
import ij.process.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** A reusable tool follows real image identity and survives source/window/toolbar lifecycle changes. */
public final class FollowingHistogramTests {
    static int passed;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
    static void pause(int ms)throws Exception{Thread.sleep(ms);EventQueue.invokeAndWait(()->{});}
    static void await(BooleanSupplier condition)throws Exception{for(int i=0;i<180;i++){if(condition.getAsBoolean())return;pause(25);}throw new AssertionError("Timed out");}
    static void ready(HistogramTool tool,ImagePlus source)throws Exception{await(()->tool.source==source&&tool.model!=null&&tool.worker==null);}
    static void select(ImagePlus source)throws Exception{EventQueue.invokeAndWait(()->{source.getWindow().toFront();WindowManager.setCurrentWindow(source.getWindow());});}
    static ImagePlus image(String title,int value){ByteProcessor p=new ByteProcessor(16,16);p.setValue(value);p.fill();ImagePlus i=new ImagePlus(title,p);i.show();return i;}
    static Method method(String clazz,String name,Class<?>...args)throws Exception{Method m=Class.forName(clazz).getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    static Object field(Object item,String name)throws Exception{Field f=item.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(item);}
    static void hover(ImagePlus image,int x,int y)throws Exception{ImageCanvas c=image.getCanvas();EventQueue.invokeAndWait(()->c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_MOVED,System.currentTimeMillis(),0,x,y,0,false)));pause(80);}
    public static void main(String[] args)throws Exception{
      Thread watchdog=new Thread(()->{try{Thread.sleep(60000);}catch(InterruptedException ignored){}System.err.println("Following Histogram watchdog");System.exit(2);});watchdog.setDaemon(true);watchdog.start();
      try{
        new ImageJ(ImageJ.NO_SHOW);Prefs.set("fijilayouts.auto",false);Prefs.set(HistogramInteraction.PREF,true);
        Prefs.set("fijilayouts.debug",true);
        new WindowLayouts_().run("install");
        String config=new String(Files.readAllBytes(Paths.get(args[0]).getParent().resolve("src/plugins.config")),"UTF-8");
        String entry="fijilayouts.HistogramToolCommand(\"follow\")";
        check(config.contains("\"Histogram Tool\", "+entry),"plugin configuration registers a persistent Histogram Tool command");
        Menus.getCommands().put("Histogram Tool",entry);
        IJ.run("Histogram Tool","");await(()->HistogramTool.followingTool()!=null);
        HistogramTool tool=HistogramTool.followingTool();
        check(tool.isVisible()&&tool.source==null&&tool.model==null,"tool can open with no image without an error dialog");
        check(tool.getTitle().equals("Histogram Tool")&&tool.followsSelection,"general tool has stable source-independent window title");
        check(WindowManager.getImageCount()==0&&!((Object)tool instanceof ImageWindow),"opening empty tool creates no image document");
        IJ.run("Histogram Tool","");pause(100);check(HistogramTool.followingTool()==tool&&HistogramTool.openTools().size()==1,"repeated command reuses the same tool window");
        check(tool.live.isSelected()&&!tool.live.isEnabled()&&!tool.refresh.isEnabled(),"Live is the default and waits for a data image");
        ImagePlus a=image("Selected A",6);ready(tool,a);
        check(tool.model.counts[6]==256&&tool.sourceLabel.getText().contains("Selected A"),"opening first image automatically binds its data and label");
        ImagePlus b=image("Selected B",187);ready(tool,b);
        check(tool.model.counts[187]==256&&tool.getTitle().equals("Histogram Tool"),"selecting a second image replaces distribution without renaming tool");
        select(a);ready(tool,a);check(tool.model.counts[6]==256,"programmatic selection follows without needing an OS focus event");
        select(b);EventQueue.invokeAndWait(()->tool.windowActivated(new WindowEvent(tool,WindowEvent.WINDOW_ACTIVATED)));ready(tool,b);
        check(WindowManager.getCurrentImage()==b,"clicking tool cannot switch back to its previous source");
        PlugInFrame otherTool=new PlugInFrame("Other analysis tool");EventQueue.invokeAndWait(()->{otherTool.setSize(180,80);otherTool.setVisible(true);WindowManager.setWindow(otherTool);});pause(220);
        check(tool.source==b&&tool.model.counts[187]==256,"another tool window does not replace the last selected image");
        b.getProcessor().set(0,77);b.updateAndDraw();await(()->tool.source==b&&tool.model!=null&&tool.model.counts[77]==1&&tool.worker==null);
        check(tool.model.counts[187]==255,"default Live updates actual source pixel edits");
        hover(b,0,0);check(tool.sampledValue==77&&tool.sampledCount==1,"pointer readout samples the current followed image");
        hover(a,0,0);check(tool.sampledValue==77,"hovering an unselected image does not change source or readout");
        EventQueue.invokeAndWait(()->tool.live.doClick());b.getProcessor().set(0,89);b.updateAndDraw();pause(180);
        check(tool.model.counts[77]==1&&tool.sampledValue==89,"Live off freezes distribution within the same source (count77="+(tool.model==null?-1:tool.model.counts[77])+", value="+tool.sampledValue+", source="+(tool.source==null?"none":tool.source.getTitle())+")");
        select(a);ready(tool,a);check(tool.model.counts[6]==256&&!tool.live.isSelected(),"new image selection always refreshes even with Live off");
        EventQueue.invokeAndWait(()->{WindowManager.setCurrentWindow(b.getWindow());tool.followCurrent();check(tool.model==null&&tool.source==b,"switching source immediately clears the previous distribution");WindowManager.setCurrentWindow(a.getWindow());tool.followCurrent();WindowManager.setCurrentWindow(b.getWindow());tool.followCurrent();});
        ready(tool,b);check(tool.model.counts[89]==1&&tool.model.counts[6]==0,"rapid source changes cannot publish a stale worker result");
        EventQueue.invokeAndWait(()->tool.live.doClick());ready(tool,b);
        int[] before=tool.model.raster.getRGB(0,0,tool.model.raster.getWidth(),tool.model.raster.getHeight(),null,0,tool.model.raster.getWidth());
        b.setDisplayRange(100,220);b.updateAndDraw();ready(tool,b);pause(200);
        check(Arrays.equals(before,tool.model.raster.getRGB(0,0,tool.model.raster.getWidth(),tool.model.raster.getHeight(),null,0,tool.model.raster.getWidth())),"following Live tool remains independent of source display contrast");
        ImagePlus color=new ImagePlus("Selected colour",new ColorProcessor(2,1,new int[]{0x0a141e,0x0a141e}));color.show();ready(tool,color);
        check(tool.channel.isVisible()&&tool.mode==0,"switching to colour enables channel controls");
        for(int i=0;i<3;i++){EventQueue.invokeAndWait(()->tool.channel.doClick());await(()->tool.worker==null);}
        hover(color,0,0);check(tool.sampledValue==10&&tool.sampledCount==2,"followed colour image supports source-bound channel sampling");
        select(a);ready(tool,a);check(!tool.channel.isVisible()&&tool.mode==-1,"switching back to grayscale resets channel state");
        a.setProcessor(new ColorProcessor(16,16));a.updateAndDraw();await(()->tool.model!=null&&tool.worker==null&&tool.mode==0&&tool.channel.isVisible());
        check(tool.model.counts[0]==256,"converting the followed image to RGB updates controls and statistics without changing identity");
        ByteProcessor restoredGray=new ByteProcessor(16,16);restoredGray.setValue(6);restoredGray.fill();a.setProcessor(restoredGray);a.updateAndDraw();await(()->tool.model!=null&&tool.worker==null&&tool.mode==-1&&!tool.channel.isVisible());
        check(tool.model.counts[6]==256,"converting the same image back to grayscale clears RGB mode safely");
        HistogramToolCommand.Options opts=new HistogramToolCommand.Options();final HistogramTool[] snapshot={null};EventQueue.invokeAndWait(()->snapshot[0]=new HistogramTool(a,opts));ready(snapshot[0],a);
        select(b);ready(tool,b);check(snapshot[0].source==a&&snapshot[0].model.counts[6]==256,"per-image comparison histogram remains independent of general tool");
        Object launcher=method("sidetoolbar.ShelfView","launcherFor",Window.class).invoke(null,tool);
        check(field(launcher,"target").equals("Histogram Tool")&&field(launcher,"args").equals("Histogram Tool"),"Keep in Shelf resolves matching stable command and window title");
        StringBuilder saved=new StringBuilder();method("sidetoolbar.Item","write",StringBuilder.class,String.class).invoke(launcher,saved,"");
        Object restored=method("sidetoolbar.Item","parseLine",String.class).invoke(null,saved.toString().trim());
        check(field(restored,"target").equals("Histogram Tool")&&field(restored,"args").equals("Histogram Tool"),"pinned launcher survives toolbar serialization and reload");
        BufferedImage panel=new BufferedImage(tool.getComponent(0).getWidth(),tool.getComponent(0).getHeight(),BufferedImage.TYPE_INT_RGB);
        EventQueue.invokeAndWait(()->{Graphics2D g=panel.createGraphics();tool.getComponent(0).printAll(g);g.dispose();});ImageIO.write(panel,"png",new File(args[0],"following-histogram-panel.png"));
        EventQueue.invokeAndWait(tool::close);check(HistogramTool.followingTool()==null&&b.getWindow()!=null,"closing general tool releases singleton and keeps source open");
        method("sidetoolbar.ShelfView","openTool",launcher.getClass()).invoke(null,restored);
        await(()->HistogramTool.followingTool()!=null&&HistogramTool.followingTool()!=tool);HistogramTool reopened=HistogramTool.followingTool();ready(reopened,b);
        check(reopened.model.counts[89]==1,"actual pinned toolbar launcher reopens tool on last selected image");
        EventQueue.invokeAndWait(()->snapshot[0].close());b.changes=false;b.close();pause(180);
        await(()->reopened.source!=b);check(reopened.source==null||reopened.source.getWindow()!=null,"closing followed source never retains a closed image");
        select(a);ready(reopened,a);a.changes=false;a.close();color.changes=false;color.close();pause(220);
        await(()->reopened.source==null&&reopened.worker==null);
        check(reopened.model==null&&!reopened.refresh.isEnabled()&&!reopened.list.isEnabled()&&Double.isNaN(reopened.sampledValue),"closing last image clears graph and disables stale-source actions");
        ImagePlus replacement=image("Selected A",42);ready(reopened,replacement);
        check(reopened.source==replacement&&reopened.model.counts[42]==256,"general tool intentionally follows a newly selected same-title image by its own identity");
        EventQueue.invokeAndWait(()->{reopened.close();otherTool.close();});
        System.out.println(passed+" following Histogram checks passed");System.exit(0);
      }catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
}
