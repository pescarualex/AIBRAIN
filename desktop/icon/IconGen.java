import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Renders the AIBRAIN app icon ("Constellation", amber) to a PNG.
 *
 * The icon is the memory list (bars = topics) hung on a column of connected
 * memory nodes, with the most-recent node active (lit, ringed) and radiating to
 * a couple of recalled memories. Drawn in a 0..100 space and scaled to any size;
 * the rounded-square tile leaves the corners transparent.
 *
 *   javac IconGen.java && java IconGen 512 aibrain.png
 */
public final class IconGen {

    static final Color INK0 = new Color(0x24, 0x29, 0x34);
    static final Color INK1 = new Color(0x0d, 0x0f, 0x13);
    static final Color AMBER0 = new Color(0xff, 0xcd, 0x87);
    static final Color AMBER1 = new Color(0xef, 0x96, 0x36);

    public static void main(String[] args) throws Exception {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 512;
        String out = args.length > 1 ? args[1] : "aibrain.png";

        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.scale(size / 100.0, size / 100.0);

        // Rounded tile, transparent outside; everything clips to it.
        RoundRectangle2D tile = new RoundRectangle2D.Double(0, 0, 100, 100, 48, 48);
        g.setClip(tile);
        g.setPaint(new GradientPaint(0, 0, INK0, 0, 100, INK1));
        g.fill(tile);
        g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 31), 0, 50, new Color(255, 255, 255, 0)));
        g.fill(tile);

        Paint amber = new GradientPaint(0, 0, AMBER0, 100, 100, AMBER1);

        // Connectors (node column + two recall branches from the active node).
        g.setPaint(amber);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        alpha(g, 0.42f);
        line(g, 30, 36, 30, 52);
        line(g, 30, 36, 18, 24);
        line(g, 30, 36, 17, 46);
        line(g, 30, 52, 30, 68);

        // Bars = topics (most recent is full strength).
        alpha(g, 1f);  bar(g, amber, 40, 32.3, 36);
        alpha(g, 0.5f); bar(g, amber, 40, 48.3, 28);
        alpha(g, 0.5f); bar(g, amber, 40, 64.3, 32);

        // Recalled memories.
        alpha(g, 0.70f); dot(g, amber, 18, 24, 2.4);
        alpha(g, 0.55f); dot(g, amber, 17, 46, 2.1);

        // Older memory nodes.
        alpha(g, 0.50f); dot(g, amber, 30, 52, 3.6);
        alpha(g, 0.50f); dot(g, amber, 30, 68, 3.6);

        // Active node: faint ring + solid core.
        alpha(g, 0.30f);
        g.setStroke(new BasicStroke(1.6f));
        g.setPaint(amber);
        g.draw(new Ellipse2D.Double(30 - 7.4, 36 - 7.4, 14.8, 14.8));
        alpha(g, 1f);
        dot(g, amber, 30, 36, 4.8);

        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("wrote " + out + " (" + size + "x" + size + ")");
    }

    static void alpha(Graphics2D g, float a) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
    }
    static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }
    static void bar(Graphics2D g, Paint p, double x, double y, double w) {
        g.setPaint(p);
        g.fill(new RoundRectangle2D.Double(x, y, w, 7.4, 7.4, 7.4));
    }
    static void dot(Graphics2D g, Paint p, double cx, double cy, double r) {
        g.setPaint(p);
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    private IconGen() {}
}
