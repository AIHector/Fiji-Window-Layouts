package fijilayouts;

import ij.gui.ImageWindow;
import ij.gui.StackWindow;
import java.awt.Window;

/** A native tool may inherit ImageWindow without being an editable image. Do not alter unknown subclasses. */
final class ImageWindows {
    static boolean isDataWindow(Window window) {
        if (window == null) return false;
        Class<?> type = window.getClass();
        return type == ImageWindow.class || type == StackWindow.class;
    }
}
