package fijilayouts;

import ij.*;
import ij.gui.*;
import ij.process.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import javax.imageio.ImageIO;
import java.io.File;

/** Real windows, actual wheel dispatch, fractional rendering, tools and delayed layout interaction. */
public final class SmoothIntegrationTests {
    static int pass;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);pass++;}
    static int[] frame(ImageCanvas c) {
        BufferedImage image=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();c.paint(g);g.dispose();
        return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
    }
    static void wheel(ImageWindow win,double rotation,int x,int y) throws Exception {
        EventQueue.invokeAndWait(()->{
            ImageCanvas c=win.getCanvas();
            c.dispatchEvent(new MouseWheelEvent(c,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),InputEvent.CTRL_DOWN_MASK,
                    x,y,x,y,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,(int)rotation,rotation));
        });
    }
    static void pause(int ms)throws Exception{Thread.sleep(ms);EventQueue.invokeAndWait(()->{});}
    public static void main(String[] args)throws Exception {
      try {
        new ImageJ(ImageJ.NO_SHOW);Prefs.set("fijilayouts.auto",false);Prefs.set("fijilayouts.follow","fit");
        new WindowLayouts_().run("install");
        ByteProcessor pixels=new ByteProcessor(1024,768);
        for(int y=0;y<768;y++)for(int x=0;x<1024;x++)pixels.set(x,y,(x/8+y/8)%2*200+20);
        ImagePlus image=new ImagePlus("fractional zoom integration",pixels);image.show();pause(300);
        ImageWindow window=image.getWindow();int imageID=image.getID();
        final int[] extraClicks={0};
        MouseListener external=new MouseAdapter(){public void mousePressed(MouseEvent e){extraClicks[0]++;}};
        EventQueue.invokeAndWait(()->{
            Follow.install(window);Prefs.set("fijilayouts.imagezoom","fit");
            Engine.fitImage(window,image,new Rectangle(140,140,700,500),0);
            window.getCanvas().addMouseListener(external);
            Overlay shown=new Overlay(new Roi(50,50,30,30));window.getCanvas().setShowAllList(shown);
        });pause(100);
        ImageCanvas old=window.getCanvas();Dimension fitted=old.getSize();Point location=old.getLocation();
        Rectangle bounds=window.getBounds();
        wheel(window,-0.2,140,160);
        check(window.getCanvas() instanceof SmoothCanvas,"actual wheel dispatch installs fractional canvas");
        SmoothCanvas canvas=(SmoothCanvas)window.getCanvas();
        check(image.getWindow()==window&&image.getID()==imageID,"window and image identities preserved");
        check(canvas.getSize().equals(fitted)&&canvas.getLocation().equals(location),"canvas size and position stay fixed on adoption");
        check(canvas.getShowAllList()!=null&&canvas.getShowAllList().size()==1,"ROI Manager display list preserved");
        check(Arrays.asList(canvas.getMouseListeners()).contains(external),"external canvas mouse listener preserved");
        check(Math.abs(canvas.getMagnification()-(fitted.width/1024.0)*Math.exp(.008))<.004,"one wheel event is handled once");
        double anchorX=canvas.offScreenXD(140),anchorY=canvas.offScreenYD(160);
        for(int i=0;i<100;i++)wheel(window,-.07,140,160);
        check(Math.abs(canvas.offScreenXD(140)-anchorX)<1e-8&&Math.abs(canvas.offScreenYD(160)-anchorY)<1e-8,
                "fractional cursor anchor survives many small wheel events");
        check(canvas.getSize().equals(fitted)&&window.getBounds().equals(bounds),"whole gesture keeps canvas and window fixed");
        double ox=canvas.originX(),oy=canvas.originY(),mag=canvas.getMagnification();int[] before=frame(canvas);
        pause(220);pause(300);pause(500);
        check(canvas.originX()==ox&&canvas.originY()==oy&&canvas.getMagnification()==mag,"uneven pauses do not round or animate viewport");
        check(Arrays.equals(before,frame(canvas)),"native repaint after pause uses identical fractional image and overlays");
        EventQueue.invokeAndWait(()->{window.invalidate();window.validate();});
        check(canvas.getSize().equals(fitted)&&canvas.getLocation().equals(location)&&canvas.originX()==ox,
                "layout at unchanged window bounds does not grow canvas or move origin");
        check(canvas.getMagnification()==mag,"Fit does not override zoom on layout");
        // The 250 ms placement verifier must not overwrite a gesture made after placement.
        EventQueue.invokeAndWait(()->Engine.fitImage(window,image,window.getBounds(),1));
        wheel(window,-3,140,160);canvas=(SmoothCanvas)window.getCanvas();
        double afterPlacementZoom=canvas.getMagnification();pause(350);
        check(canvas.getMagnification()==afterPlacementZoom,"stale placement timer cannot overwrite a subsequent gesture");
        EventQueue.invokeAndWait(()->{window.setSize(window.getWidth()+60,window.getHeight()+40);window.validate();});pause(500);
        Dimension border=Engine.chrome(window);
        double expectedFit=Math.min((window.getWidth()-border.width)/1024.0,(window.getHeight()-border.height)/768.0);
        check(Math.abs(canvas.getMagnification()-expectedFit)<1e-9,"genuine window resizing still applies Fit");
        EventQueue.invokeAndWait(()->{ij.WindowManager.setCurrentWindow(window);Engine.switchFillFit();});pause(350);
        check(Follow.mode().equals("fill"),"Fill/Fit switch still selects Fill");
        border=Engine.chrome(window);
        double expectedFill=Math.max((window.getWidth()-border.width)/1024.0,(window.getHeight()-border.height)/768.0);
        check(Math.abs(canvas.getMagnification()-expectedFill)<1e-9,"Fill/Fit switch immediately fits requested mode");
        wheel(window,-2,140,160);double fillZoom=canvas.getMagnification();
        EventQueue.invokeAndWait(()->{window.invalidate();window.validate();});pause(350);
        check(canvas.getMagnification()==fillZoom,"Fill-mode layout also preserves explicit zoom");
        final SmoothCanvas c=canvas;
        EventQueue.invokeAndWait(()->{
            c.setSize(400,320);c.setSourceRect(new Rectangle(200,160,100,80));c.setMagnification(4);
            c.zoom(1.013,173,151);Follow.noteZoom(window);
            image.setRoi(new Roi(220,180,25,25));
            Overlay overlay=new Overlay(new Roi(250,180,20,20));image.setOverlay(overlay);
        });
        int[] withRoi=frame(c);
        Roi selection=(Roi)image.getRoi().clone();
        EventQueue.invokeAndWait(()->{image.deleteRoi();image.setHideOverlay(true);});
        int[] withoutRoi=frame(c);
        check(!Arrays.equals(withRoi,withoutRoi),"native selection and overlays visibly render on fractional image");
        EventQueue.invokeAndWait(()->{image.setRoi(selection);image.setHideOverlay(false);});
        check(Math.abs((c.offScreenXD(173)-c.originX())*c.getMagnification()-173)<1e-10,
                "screen-to-image coordinates retain fractional origin");
        double targetX=c.offScreenXD(173),targetY=c.offScreenYD(151);
        check(Math.abs(c.screenXD(targetX)-173)<=1&&Math.abs(c.screenYD(targetY)-151)<=1,"selection coordinate inverse agrees within one screen pixel");
        // Point-tool input reaches the same image coordinate as the fractional renderer.
        EventQueue.invokeAndWait(()->{
            image.deleteRoi();Toolbar.getInstance().setTool(Toolbar.POINT);
            long now=System.currentTimeMillis();
            c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_PRESSED,now,InputEvent.BUTTON1_DOWN_MASK,173,151,1,false,MouseEvent.BUTTON1));
            c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_RELEASED,now+20,0,173,151,1,false,MouseEvent.BUTTON1));
        });
        check(extraClicks[0]==1,"external listener receives tool press once");
        check(image.getRoi() instanceof PointRoi,"native point tool remains functional after canvas replacement");
        ij.process.FloatPolygon points=image.getRoi().getFloatPolygon();
        check(Math.abs(points.xpoints[0]-targetX)<=1&&Math.abs(points.ypoints[0]-targetY)<=1,"point tool records the displayed image position");
        EventQueue.invokeAndWait(()->{image.deleteRoi();c.zoomIn(150,150);});
        check(c.originX()==c.getSrcRect().x&&c.originY()==c.getSrcRect().y,"built-in zoom switches cleanly to its explicit native view");
        // A stack retains its sliders and channel structure.
        ImageStack stack=new ImageStack(2796,2796);
        for(int i=0;i<3;i++) {
            ShortProcessor plane=new ShortProcessor(2796,2796);
            short[] data=(short[])plane.getPixels();
            for(int pixel=0;pixel<data.length;pixel++)data[pixel]=(short)(pixel*17+i*7000);
            stack.addSlice(plane);
        }
        ImagePlus base=new ImagePlus("three-channel stack",stack);base.setDimensions(3,1,1);
        CompositeImage composite=new CompositeImage(base,CompositeImage.COMPOSITE);composite.show();pause(200);
        ImageWindow stackWindow=composite.getWindow();int components=stackWindow.getComponentCount();
        wheel(stackWindow,-1,100,100);
        check(stackWindow.getCanvas() instanceof SmoothCanvas&&stackWindow.getComponentCount()==components,
                "stack canvas replacement preserves slider components");
        check(composite.getNChannels()==3&&composite.getStackSize()==3,"three-channel 16-bit image structure preserved");
        int[] compositeFrame=frame(stackWindow.getCanvas());pause(220);
        check(Arrays.equals(compositeFrame,frame(stackWindow.getCanvas())),"three-channel image has no finish-frame change");
        EventQueue.invokeAndWait(()->{
            BufferedImage screenshot=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=screenshot.createGraphics();c.paint(g);g.dispose();
            try{ImageIO.write(screenshot,"png",new File(args[0],"fractional-canvas.png"));}catch(Exception ex){throw new RuntimeException(ex);}
        });
        image.changes=false;image.close();composite.changes=false;composite.close();
        System.out.println(pass+" fractional zoom integration checks passed");System.exit(0);
      }catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
}
