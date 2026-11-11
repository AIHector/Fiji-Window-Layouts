package fijilayouts;

import ij.*;
import ij.gui.*;
import ij.process.ByteProcessor;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Runs the installed Undo History candidate with the new fractional canvas. */
public class CombinedZoomUndoTest {
    static int passed;
    static void waitFor(BooleanSupplier test)throws Exception {
        for(int i=0;i<150;i++){if(test.getAsBoolean())return;Thread.sleep(40);}
        throw new AssertionError("Timed out");
    }
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;}
    public static void main(String[] args)throws Exception {
      try {
        new ImageJ(ImageJ.NO_SHOW);Prefs.set("fijilayouts.auto",false);
        Prefs.set("undohistory.testMemoryMB",64);Prefs.set("undohistory.useDisk",false);
        new WindowLayouts_().run("install");
        ij.plugin.PlugIn undo=(ij.plugin.PlugIn)Class.forName("undohistory.Undo_History").newInstance();undo.run("start");
        Class<?> cls=Class.forName("undohistory.Engine");Method get=cls.getDeclaredMethod("get");get.setAccessible(true);Object engine=get.invoke(null);
        Method cursor=cls.getDeclaredMethod("cursorOf",int.class);cursor.setAccessible(true);
        ByteProcessor pixels=new ByteProcessor(128,128);for(int i=0;i<16384;i++)pixels.set(i,i%173);
        ImagePlus imp=new ImagePlus("Zoom and Undo integration",pixels);imp.show();
        waitFor(()->{try{return ((Integer)cursor.invoke(engine,imp.getID()))==0;}catch(Exception ex){return false;}});
        EventQueue.invokeAndWait(()->{
            ImageCanvas c=imp.getCanvas();c.dispatchEvent(new MouseWheelEvent(c,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),InputEvent.CTRL_DOWN_MASK,
                    50,50,50,50,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-3,-3.27));
        });
        check(imp.getCanvas() instanceof SmoothCanvas,"combined plugins use fractional canvas");
        SmoothCanvas canvas=(SmoothCanvas)imp.getCanvas();double origin=canvas.originX();
        byte[] initial=((byte[])imp.getProcessor().getPixels()).clone();IJ.run(imp,"Invert","");
        waitFor(()->{try{return ((Integer)cursor.invoke(engine,imp.getID()))==1;}catch(Exception ex){return false;}});
        byte[] inverted=((byte[])imp.getProcessor().getPixels()).clone();IJ.doCommand("Undo");
        waitFor(()->Arrays.equals(initial,(byte[])imp.getProcessor().getPixels()));
        check(imp.getCanvas()==canvas&&canvas.originX()==origin,"Undo restores pixels without dropping fractional view");
        undo.run("redo");
        waitFor(()->Arrays.equals(inverted,(byte[])imp.getProcessor().getPixels()));
        check(imp.getCanvas()==canvas&&canvas.originX()==origin,"Redo restores pixels without dropping fractional view");
        imp.changes=false;imp.close();System.out.println(passed+" combined Zoom/Undo checks passed");System.exit(0);
      }catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
}
