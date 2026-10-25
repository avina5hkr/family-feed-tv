import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/**
 * Facebook Feed icon: a white TV (screen + stand) on Facebook blue, with a bold blue "f" in the screen whose stem
 * runs off the bottom of the screen like Facebook's own mark. All geometry is in the 108x108 adaptive-icon
 * viewport; the shapes stay inside the 66-unit safe circle so a circular launcher crop never cuts them.
 * Usage (from the project root): java -Djava.awt.headless=true tools/icon/Icon.java app/src/main/res <preview dir>
 */
public class Icon {
    static final Color BLUE = new Color(0x1877F2);
    static final Color BLUE_DARK = new Color(0x0B5FD3);

    // Shared path data (Android vector syntax and Java2D use the same coordinates).
    static final String SCREEN = "M34,34 L74,34 Q80,34 80,40 L80,62 Q80,68 74,68 L34,68 Q28,68 28,62 L28,40 Q28,34 34,34 Z";
    static final String STAND = "M50,68 L58,68 L58,73 L65,73 Q67,73 67,75 L67,75 Q67,77 65,77 L43,77 Q41,77 41,75 L41,75 Q41,73 43,73 L50,73 Z";
    static final String F = "M52,68 L52,55 L47.5,55 L47.5,49.5 L52,49.5 L52,47.5 C52,42.3 55.2,39.5 60.3,39.5 L63,39.5 L63,44.8 "
            + "L61.2,44.8 C59.4,44.8 58.5,45.7 58.5,47.6 L58.5,49.5 L62.6,49.5 L61.8,55 L58.5,55 L58.5,68 Z";

    static Shape parse(String d) {
        Path2D.Double p = new Path2D.Double();
        String[] t = d.replace(",", " ").replaceAll("([MLQCZ])", " $1 ").trim().split("\\s+");
        int i = 0;
        while (i < t.length) {
            String c = t[i++];
            switch (c) {
                case "M": p.moveTo(n(t[i++]), n(t[i++])); break;
                case "L": p.lineTo(n(t[i++]), n(t[i++])); break;
                case "Q": p.quadTo(n(t[i++]), n(t[i++]), n(t[i++]), n(t[i++])); break;
                case "C": p.curveTo(n(t[i++]), n(t[i++]), n(t[i++]), n(t[i++]), n(t[i++]), n(t[i++])); break;
                case "Z": p.closePath(); break;
                default: throw new IllegalArgumentException(c);
            }
        }
        return p;
    }
    static double n(String s) { return Double.parseDouble(s); }

    /** Draws the full icon (background + foreground) into a size x size image, cropped to the given mask. */
    static BufferedImage render(int size, Shape mask108) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(size / 108.0, size / 108.0);
        if (mask108 != null) g.setClip(mask108);
        g.setPaint(new GradientPaint(0, 0, BLUE, 0, 108, BLUE_DARK));
        g.fill(new Rectangle2D.Double(0, 0, 108, 108));
        drawForeground(g);
        g.dispose();
        return img;
    }

    static final double MARK_SCALE = 1.1, PIVOT_X = 54, PIVOT_Y = 55.5;

    static void drawForeground(Graphics2D g) {
        AffineTransform saved = g.getTransform();
        g.translate(PIVOT_X, PIVOT_Y);
        g.scale(MARK_SCALE, MARK_SCALE);
        g.translate(-PIVOT_X, -PIVOT_Y);
        g.setColor(Color.WHITE);
        g.fill(parse(SCREEN));
        g.fill(parse(STAND));
        g.setColor(BLUE);
        g.fill(parse(F));
        g.setTransform(saved);
    }

    public static void main(String[] a) throws Exception {
        Path res = Paths.get(a[0]), prev = Paths.get(a[1]);
        Files.createDirectories(prev);

        // Adaptive icon layers.
        write(res.resolve("drawable/ic_launcher_background.xml"),
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- Facebook blue, slightly darker towards the bottom. -->\n"
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    xmlns:aapt=\"http://schemas.android.com/aapt\"\n"
            + "    android:width=\"108dp\"\n    android:height=\"108dp\"\n"
            + "    android:viewportWidth=\"108\"\n    android:viewportHeight=\"108\">\n"
            + "    <path android:pathData=\"M0,0h108v108h-108z\">\n"
            + "        <aapt:attr name=\"android:fillColor\">\n"
            + "            <gradient android:type=\"linear\" android:startX=\"54\" android:startY=\"0\" android:endX=\"54\" android:endY=\"108\"\n"
            + "                android:startColor=\"#FF1877F2\" android:endColor=\"#FF0B5FD3\" />\n"
            + "        </aapt:attr>\n    </path>\n</vector>\n");
        write(res.resolve("drawable/ic_launcher_foreground.xml"), vector(
            "<!-- A white TV with a bold Facebook-blue \"f\" on its screen. -->\n",
            path("#FFFFFFFF", SCREEN, null) + path("#FFFFFFFF", STAND, null) + path("#FF1877F2", F, null)));
        // Themed (monochrome) icon: the TV as one shape with the "f" cut out of the screen.
        write(res.resolve("drawable/ic_launcher_monochrome.xml"), vector(
            "<!-- Themed-icon layer: the TV with the \"f\" cut out. -->\n",
            path("#FFFFFFFF", SCREEN + " " + F, "evenOdd") + path("#FFFFFFFF", STAND, null)));
        String adaptive = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n"
            + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
            + "    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n"
            + "</adaptive-icon>\n";
        write(res.resolve("mipmap-anydpi-v26/ic_launcher.xml"), adaptive);
        write(res.resolve("mipmap-anydpi-v26/ic_launcher_round.xml"), adaptive);

        // Legacy icons for Android 7 (minSdk 24): the full-bleed 108 viewport shown at the 72-unit visible area.
        String[] dens = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] px = {48, 72, 96, 144, 192};
        for (int i = 0; i < dens.length; i++) {
            Path dir = res.resolve("mipmap-" + dens[i]);
            for (String old : new String[]{"ic_launcher.webp", "ic_launcher_round.webp"}) Files.deleteIfExists(dir.resolve(old));
            ImageIO.write(legacy(px[i], new RoundRectangle2D.Double(18, 18, 72, 72, 16, 16)), "png", dir.resolve("ic_launcher.png").toFile());
            ImageIO.write(legacy(px[i], new Ellipse2D.Double(18, 18, 72, 72)), "png", dir.resolve("ic_launcher_round.png").toFile());
        }

        // TV banner (320x180, xhdpi): the icon mark on the left, the name on the right.
        BufferedImage banner = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = banner.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setPaint(new GradientPaint(0, 0, BLUE, 0, 180, BLUE_DARK));
        g.fillRect(0, 0, 320, 180);
        AffineTransform t = g.getTransform();
        double s = 1.45;            // the TV (x 28..80, y 34..77 in icon units) at 1.45x: about 75 x 62 px
        g.translate(18 - 28 * MARK_SCALE * s + (MARK_SCALE - 1) * PIVOT_X * s, 90 - 55.5 * s);
        g.scale(s, s);
        drawForeground(g);
        g.setTransform(t);
        g.setColor(Color.WHITE);
        int x = 116, maxWidth = 320 - x - 14, size = 48;
        Font font;
        do { font = new Font("Helvetica", Font.BOLD, size--); } while (g.getFontMetrics(font).stringWidth("Facebook") > maxWidth);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int lineGap = fm.getAscent() + 2;
        int top = 90 - lineGap + fm.getAscent() / 2;
        g.drawString("Facebook", x, top);
        g.drawString("Feed", x, top + lineGap);
        g.dispose();
        ImageIO.write(banner, "png", res.resolve("drawable-xhdpi/banner.png").toFile());

        // Previews: as Google TV shows it (circle), small, and the banner.
        ImageIO.write(render(512, new Ellipse2D.Double(0, 0, 108, 108)), "png", prev.resolve("circle512.png").toFile());
        ImageIO.write(render(96, new Ellipse2D.Double(0, 0, 108, 108)), "png", prev.resolve("circle96.png").toFile());
        ImageIO.write(render(512, new RoundRectangle2D.Double(0, 0, 108, 108, 24, 24)), "png", prev.resolve("squircle512.png").toFile());
        System.out.println("done");
    }

    static BufferedImage legacy(int size, Shape mask) {
        // Render the 72-unit visible area (18..90) of the adaptive icon at `size` px.
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 72.0, size / 72.0);
        g.translate(-18, -18);
        g.setClip(mask);
        g.setPaint(new GradientPaint(0, 0, BLUE, 0, 108, BLUE_DARK));
        g.fill(new Rectangle2D.Double(0, 0, 108, 108));
        drawForeground(g);
        g.dispose();
        return img;
    }

    static String vector(String comment, String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + comment
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:width=\"108dp\"\n    android:height=\"108dp\"\n"
            + "    android:viewportWidth=\"108\"\n    android:viewportHeight=\"108\">\n"
            + "    <group\n        android:pivotX=\"" + (int) PIVOT_X + "\"\n        android:pivotY=\"" + PIVOT_Y + "\"\n"
            + "        android:scaleX=\"" + MARK_SCALE + "\"\n        android:scaleY=\"" + MARK_SCALE + "\">\n"
            + body.replaceAll("(?m)^", "    ") + "    </group>\n</vector>\n";
    }

    static String path(String color, String d, String fillType) {
        return "    <path\n        android:fillColor=\"" + color + "\"\n"
            + (fillType != null ? "        android:fillType=\"" + fillType + "\"\n" : "")
            + "        android:pathData=\"" + d + "\" />\n";
    }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s);
    }
}
