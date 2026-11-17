package fijilayouts;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.Menus;
import ij.gui.GenericDialog;
import ij.gui.HistogramWindow;
import ij.gui.YesNoCancelDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import java.awt.EventQueue;

/** Analyze > Histogram opens a non-image tool; the native statistics/plot engine stays private. */
public final class HistogramToolCommand implements PlugIn {
    @SuppressWarnings("unchecked")
    static void install() {
        if (Menus.getCommands() == null || !HistogramTool.supported()) return;
        Object command = Menus.getCommands().get("Histogram");
        if ("ij.plugin.Histogram".equals(command) || HistogramToolCommand.class.getName().equals(command))
            Menus.getCommands().put("Histogram", HistogramToolCommand.class.getName());
    }

    static final class Options {
        int bins = 256, yMax;
        double min, max;
        boolean stack;
    }

    public void run(String arg) {
        ImagePlus source = IJ.getImage();
        if (source == null || !ImageWindows.isDataWindow(source.getWindow())) {
            IJ.error("Histogram", "Select a source image first."); return;
        }
        Options options = options(source);
        if (options == null) return;
        EventQueue.invokeLater(() -> new HistogramTool(source, options));
    }

    private Options options(ImagePlus source) {
        Options result = new Options();
        String macro = Macro.getOptions();
        boolean detailedMacro = macro != null && java.util.regex.Pattern.compile("(?:^|\\s)(?:bins=|x_min=|x_max=|y_max=|use(?:\\s|$))").matcher(macro).find();
        if (source.getBitDepth() == 32 || IJ.altKeyDown() || (IJ.isMacro() && detailedMacro)) {
            IJ.setKeyUp(java.awt.event.KeyEvent.VK_ALT);
            GenericDialog dialog = new GenericDialog("Histogram");
            dialog.addNumericField("Bins:", HistogramWindow.nBins, 0);
            dialog.addCheckbox("Use pixel value range", true);
            // Defaults use raw pixel extrema, not the display range selected in Brightness/Contrast.
            double min = source.getProcessor().getStatistics().min;
            double max = source.getProcessor().getStatistics().max;
            dialog.addNumericField("X_min:", source.getCalibration().getCValue(min), 4);
            dialog.addNumericField("X_max:", source.getCalibration().getCValue(max), 4);
            dialog.addStringField("Y_max:", "Auto");
            if (source.getStackSize() > 1) dialog.addCheckbox("Stack histogram", false);
            dialog.showDialog();
            if (dialog.wasCanceled()) return null;
            double bins = dialog.getNextNumber();
            boolean auto = dialog.getNextBoolean();
            if (macro != null && !macro.contains("x_min=") && !macro.contains("x_max=")) auto = true;
            double low = dialog.getNextNumber(), high = dialog.getNextNumber();
            String y = dialog.getNextString().trim();
            double ceiling = y.equalsIgnoreCase("Auto") ? 0 : ij.util.Tools.parseDouble(y);
            if (source.getStackSize() > 1) result.stack = dialog.getNextBoolean();
            if (!Double.isFinite(bins) || bins != Math.rint(bins) || bins < 2 || bins > 65536
                    || !Double.isFinite(ceiling) || ceiling < 0 || ceiling > Integer.MAX_VALUE
                    || (!auto && (!Double.isFinite(low) || !Double.isFinite(high) || high <= low))) {
                IJ.error("Histogram", "Use 2–65536 bins, a finite increasing range, and Auto or a nonnegative Y maximum.");
                return null;
            }
            result.bins = (int) bins; result.yMax = (int) ceiling;
            if (!auto) {
                // ImageJ's statistics APIs accept calibrated range endpoints and convert them internally.
                result.min = low; result.max = high;
            }
            if (!IJ.isMacro() && result.bins <= 1000) HistogramWindow.nBins = result.bins;
        } else if (source.getStackSize() > 1) {
            if (macro != null) result.stack = java.util.regex.Pattern.compile("(?:^|\\s)stack(?:\\s|$)").matcher(macro).find();
            else {
                YesNoCancelDialog dialog = new YesNoCancelDialog(IJ.getInstance(), "Histogram", "Include all " + source.getStackSize() + " images?");
                if (dialog.cancelPressed()) return null;
                result.stack = dialog.yesPressed();
                if (IJ.recording()) Recorder.recordOption(result.stack ? "stack" : "slice");
            }
        }
        if (result.stack && result.min == 0 && result.max == 0 && ((source.getBitDepth() == 8 && !source.getCalibration().calibrated()) || source.isRGB()))
            result.max = 256;
        if (result.min == 0 && result.max == 0 && source.getBitDepth() == 16 && ImagePlus.getDefault16bitRange() != 0) {
            result.min = source.getCalibration().getCValue(0);
            result.max = source.getCalibration().getCValue(Math.pow(2, ImagePlus.getDefault16bitRange()) - 1);
        }
        return result;
    }
}
