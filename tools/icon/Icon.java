import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;

/**
 * Family Feed launcher icon and TV banner: a thick white TV outline on deep navy, with an amber play triangle and
 * two white feed lines on the screen. Built to be read from across a room: high contrast, big shapes, nothing thin.
 *
 * All geometry is in the 108x108 adaptive-icon viewport and stays inside the 66-unit safe circle, so whatever mask
 * the launcher applies (rounded square, circle on Google TV) never cuts into it.
 *
 * Usage (from the project root): java -Djava.awt.headless=true tools/icon/Icon.java app/src/main/res <preview dir>
 * Writes the adaptive icon layers, PNG icons for Android 7, the 320x180 banner, and preview PNGs.
 */
public class Icon {
    static final Color NAVY_TOP = new Color(0x16245A);
    static final Color NAVY_BOTTOM = new Color(0x0C1638);
    static final Color WHITE = Color.WHITE;
    static final Color AMBER = new Color(0xFFB627);

    static final double OUTLINE_WIDTH = 5.5;

    // Shapes, in Android vector path syntax (also parsed for Java2D below).
    /** TV body: drawn as a thick outline (stroke), not filled. */
    static final String TV = "M33,33 L75,33 Q82,33 82,40 L82,64 Q82,71 75,71 L33,71 Q26,71 26,64 L26,40 Q26,33 33,33 Z";
    /** Stand: one rounded bar under the TV. */
    static final String STAND = "M42,76 L66,76 Q68.5,76 68.5,78.5 Q68.5,81 66,81 L42,81 Q39.5,81 39.5,78.5 Q39.5,76 42,76 Z";
    /** Play triangle, left half of the screen, corners slightly rounded. */
    static final String PLAY = "M36.5,43.5 Q36.5,40.5 39.2,42 L52.5,50 Q55,52 52.5,54 L39.2,62 Q36.5,63.5 36.5,60.5 Z";
    /** Two feed lines, right half of the screen: a long one above a shorter one. */
    static final String LINE_1 = "M60.5,43.5 L71.5,43.5 Q74,43.5 74,46 Q74,48.5 71.5,48.5 L60.5,48.5 Q58,48.5 58,46 Q58,43.5 60.5,43.5 Z";
    static final String LINE_2 = "M60.5,55.5 L66.5,55.5 Q69,55.5 69,58 Q69,60.5 66.5,60.5 L60.5,60.5 Q58,60.5 58,58 Q58,55.5 60.5,55.5 Z";

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

    static Graphics2D smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    /** Navy background over the given area (in icon units). */
    static void drawBackground(Graphics2D g, double height) {
        g.setPaint(new GradientPaint(0, 0, NAVY_TOP, 0, (float) height, NAVY_BOTTOM));
        g.fill(new Rectangle2D.Double(-1000, -1000, 3000, 3000));
    }

    /** The mark itself, in icon units. */
    static void drawMark(Graphics2D g) {
        g.setColor(WHITE);
        g.setStroke(new BasicStroke((float) OUTLINE_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(parse(TV));
        g.fill(parse(STAND));
        g.fill(parse(LINE_1));
        g.fill(parse(LINE_2));
        g.setColor(AMBER);
        g.fill(parse(PLAY));
    }

    /** The full icon (background + mark) at size x size px, clipped to a mask given in icon units. */
    static BufferedImage icon(int size, Shape mask, double from, double span) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img.createGraphics());
        g.scale(size / span, size / span);
        g.translate(-from, -from);
        g.setClip(mask);
        drawBackground(g, 108);
        drawMark(g);
        g.dispose();
        return img;
    }

    public static void main(String[] a) throws Exception {
        Path res = Paths.get(a[0]), prev = Paths.get(a[1]);
        Files.createDirectories(prev);

        // Adaptive icon layers (Android 8+).
        write(res.resolve("drawable/ic_launcher_background.xml"), "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- Generated by tools/icon/Icon.java. Deep navy, slightly darker towards the bottom. -->\n"
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    xmlns:aapt=\"http://schemas.android.com/aapt\"\n"
            + "    android:width=\"108dp\"\n    android:height=\"108dp\"\n"
            + "    android:viewportWidth=\"108\"\n    android:viewportHeight=\"108\">\n"
            + "    <path android:pathData=\"M0,0h108v108h-108z\">\n"
            + "        <aapt:attr name=\"android:fillColor\">\n"
            + "            <gradient android:type=\"linear\" android:startX=\"54\" android:startY=\"0\"\n"
            + "                android:endX=\"54\" android:endY=\"108\"\n"
            + "                android:startColor=\"" + hex(NAVY_TOP) + "\" android:endColor=\"" + hex(NAVY_BOTTOM) + "\" />\n"
            + "        </aapt:attr>\n    </path>\n</vector>\n");
        write(res.resolve("drawable/ic_launcher_foreground.xml"), vector(
            "A thick white TV outline with an amber play triangle and two white feed lines on the screen.",
            stroke(TV, hex(WHITE)) + fill(STAND, hex(WHITE)) + fill(LINE_1, hex(WHITE)) + fill(LINE_2, hex(WHITE))
                + fill(PLAY, hex(AMBER))));
        write(res.resolve("drawable/ic_launcher_monochrome.xml"), vector(
            "Themed-icon layer: the same mark in one colour.",
            stroke(TV, "#FFFFFFFF") + fill(STAND, "#FFFFFFFF") + fill(LINE_1, "#FFFFFFFF") + fill(LINE_2, "#FFFFFFFF")
                + fill(PLAY, "#FFFFFFFF")));
        String adaptive = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n"
            + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
            + "    <monochrome android:drawable=\"@drawable/ic_launcher_monochrome\" />\n"
            + "</adaptive-icon>\n";
        write(res.resolve("mipmap-anydpi-v26/ic_launcher.xml"), adaptive);
        write(res.resolve("mipmap-anydpi-v26/ic_launcher_round.xml"), adaptive);

        // PNG icons for Android 7: the visible 72-unit area (18..90) as a rounded square, and as a circle.
        String[] dens = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] px = {48, 72, 96, 144, 192};
        Shape roundedSquare = new RoundRectangle2D.Double(18, 18, 72, 72, 22, 22);
        Shape circle = new Ellipse2D.Double(18, 18, 72, 72);
        for (int i = 0; i < dens.length; i++) {
            Path dir = res.resolve("mipmap-" + dens[i]);
            Files.createDirectories(dir);
            ImageIO.write(icon(px[i], roundedSquare, 18, 72), "png", dir.resolve("ic_launcher.png").toFile());
            ImageIO.write(icon(px[i], circle, 18, 72), "png", dir.resolve("ic_launcher_round.png").toFile());
        }

        // TV banner, 320x180 (xhdpi): the mark on the left, the app name in large plain text on the right.
        BufferedImage banner = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(banner.createGraphics());
        g.setPaint(new GradientPaint(0, 0, NAVY_TOP, 0, 180, NAVY_BOTTOM));
        g.fillRect(0, 0, 320, 180);
        double scale = 1.5;  // the mark spans x 23..85, y 30..81 in icon units: about 93 x 77 px
        AffineTransform saved = g.getTransform();
        g.translate(14 - 23 * scale, 90 - 55.5 * scale);
        g.scale(scale, scale);
        drawMark(g);
        g.setTransform(saved);
        int textX = 122, maxWidth = 320 - textX - 14;
        Font font = new Font("Helvetica", Font.BOLD, 60);
        for (int size = 60; g.getFontMetrics(font).stringWidth("Family") > maxWidth; size--) {
            font = new Font("Helvetica", Font.BOLD, size);
        }
        g.setFont(font);
        g.setColor(WHITE);
        FontMetrics fm = g.getFontMetrics();
        int lineHeight = Math.round(fm.getAscent() * 1.12f);
        int firstBaseline = 90 - lineHeight / 2 + fm.getAscent() / 2 - 2;
        g.drawString("Family", textX, firstBaseline);
        g.drawString("Feed", textX, firstBaseline + lineHeight);
        g.dispose();
        ImageIO.write(banner, "png", res.resolve("drawable-xhdpi/banner.png").toFile());

        // Previews: rounded square and circle (Google TV), large and at TV tile size, plus a 3x banner.
        Shape fullRounded = new RoundRectangle2D.Double(0, 0, 108, 108, 32, 32);
        Shape fullCircle = new Ellipse2D.Double(0, 0, 108, 108);
        ImageIO.write(icon(512, fullRounded, 0, 108), "png", prev.resolve("rounded512.png").toFile());
        ImageIO.write(icon(512, fullCircle, 0, 108), "png", prev.resolve("circle512.png").toFile());
        ImageIO.write(icon(64, fullRounded, 0, 108), "png", prev.resolve("rounded64.png").toFile());
        ImageIO.write(icon(64, fullCircle, 0, 108), "png", prev.resolve("circle64.png").toFile());
        BufferedImage big = new BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB);
        Graphics2D bg = big.createGraphics();
        bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        bg.drawImage(banner, 0, 0, 960, 540, null);
        bg.dispose();
        ImageIO.write(big, "png", prev.resolve("banner3x.png").toFile());
        System.out.println("Wrote icon resources to " + res + " and previews to " + prev);
    }

    static String hex(Color c) { return String.format("#FF%06X", c.getRGB() & 0xFFFFFF); }

    static String vector(String comment, String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- Generated by tools/icon/Icon.java. " + comment + " -->\n"
            + "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
            + "    android:width=\"108dp\"\n    android:height=\"108dp\"\n"
            + "    android:viewportWidth=\"108\"\n    android:viewportHeight=\"108\">\n" + body + "</vector>\n";
    }

    static String fill(String d, String color) {
        return "    <path\n        android:fillColor=\"" + color + "\"\n        android:pathData=\"" + d + "\" />\n";
    }

    static String stroke(String d, String color) {
        return "    <path\n        android:fillColor=\"#00000000\"\n        android:strokeColor=\"" + color + "\"\n"
            + "        android:strokeWidth=\"" + OUTLINE_WIDTH + "\"\n        android:strokeLineCap=\"round\"\n"
            + "        android:strokeLineJoin=\"round\"\n        android:pathData=\"" + d + "\" />\n";
    }

    static void write(Path p, String s) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s);
    }
}
