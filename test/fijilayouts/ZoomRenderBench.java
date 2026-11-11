package fijilayouts;

import ij.*;
import ij.process.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/** Offscreen Retina-resolution rendering cost for a representative three-channel image. */
public final class ZoomRenderBench {
    public static void main(String[] args) {
        ImageStack stack=new ImageStack(2796,2796);
        for(int channel=0;channel<3;channel++) {
            ShortProcessor plane=new ShortProcessor(2796,2796);
            short[] pixels=(short[])plane.getPixels();
            for(int i=0;i<pixels.length;i++)pixels[i]=(short)(i*17+channel*7000);
            stack.addSlice(plane);
        }
        ImagePlus base=new ImagePlus("render benchmark",stack);base.setDimensions(3,1,1);
        CompositeImage image=new CompositeImage(base,CompositeImage.COMPOSITE);
        SmoothCanvas canvas=new SmoothCanvas(image);canvas.setSize(700,700);
        canvas.setSourceRect(new Rectangle(0,0,2796,2796));canvas.setMagnification(700.0/2796);
        BufferedImage destination=new BufferedImage(1400,1400,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=destination.createGraphics();g.scale(2,2);
        double[] milliseconds=new double[100];
        for(int i=-15;i<100;i++) {
            long start=System.nanoTime();canvas.zoom(1.002,300,300);canvas.paint(g);
            if(i>=0)milliseconds[i]=(System.nanoTime()-start)/1e6;
        }
        g.dispose();Arrays.sort(milliseconds);
        System.out.printf(java.util.Locale.ROOT,"Retina 1400x1400 output, 2796x2796x3 16-bit input: median=%.2f ms, p95=%.2f ms, max=%.2f ms%n",
                milliseconds[50],milliseconds[95],milliseconds[99]);
        System.exit(0);
    }
}
