import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class MakeIcons {
    public static void main(String[] args) throws Exception {
        int[] sizes = {48, 72, 96, 144, 192};
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};

        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // Background rounded rectangle
            float corner = s * 0.22f;
            RoundRectangle2D.Float bg = new RoundRectangle2D.Float(1, 1, s - 2, s - 2, corner, corner);
            g.setColor(new Color(0x0C, 0x0F, 0x0E));
            g.fill(bg);

            // Subtle border
            g.setColor(new Color(0x2C, 0x3A, 0x35));
            g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.03f)));
            g.draw(bg);

            // Inner fortress design based on SVG:
            // "M5 3h3v2h2V3h4v2h2V3h3v5l-2 2v7l2 2v2H5v-2l2-2v-7L5 8z" (viewbox 24x24)
            Graphics2D g2 = (Graphics2D) g.create();
            float scale = (s * 0.65f) / 24.0f;
            float tx = (s - 24 * scale) / 2.0f;
            float ty = (s - 24 * scale) / 2.0f;
            g2.translate(tx, ty);
            g2.scale(scale, scale);

            Path2D.Float path = new Path2D.Float();
            path.moveTo(5, 3);
            path.lineTo(8, 3);
            path.lineTo(8, 5);
            path.lineTo(10, 5);
            path.lineTo(10, 3);
            path.lineTo(14, 3);
            path.lineTo(14, 5);
            path.lineTo(16, 5);
            path.lineTo(16, 3);
            path.lineTo(19, 3);
            path.lineTo(19, 8);
            path.lineTo(17, 10);
            path.lineTo(17, 17);
            path.lineTo(19, 19);
            path.lineTo(19, 21);
            path.lineTo(5, 21);
            path.lineTo(5, 19);
            path.lineTo(7, 17);
            path.lineTo(7, 10);
            path.lineTo(5, 8);
            path.closePath();

            // Fill fortress emblem
            g2.setColor(new Color(0x4E, 0xD8, 0xAF));
            g2.fill(path);

            // Fortress gate cutout
            RoundRectangle2D.Float gate = new RoundRectangle2D.Float(10, 15, 4, 6, 1.5f, 1.5f);
            g2.setColor(new Color(0x0C, 0x0F, 0x0E));
            g2.fill(gate);

            g2.dispose();
            g.dispose();

            File outDir = new File("android/src/main/res/" + dirs[i]);
            outDir.mkdirs();
            ImageIO.write(img, "PNG", new File(outDir, "ic_launcher.png"));
            ImageIO.write(img, "PNG", new File(outDir, "ic_launcher_round.png"));
            System.out.println("Generated icon for " + dirs[i] + " (" + s + "x" + s + ")");
        }
    }
}
