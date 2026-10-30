import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Scales a screenshot down to a small JPEG: java ci/Shrink.java in.png out.jpg 720 */
public class Shrink {
    public static void main(String[] args) throws Exception {
        BufferedImage in = ImageIO.read(new File(args[0]));
        int width = Integer.parseInt(args[2]);
        int height = Math.max(1, in.getHeight() * width / in.getWidth());
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(in, 0, 0, width, height, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.72f);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(new File(args[1]))) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(out, null, null), param);
        }
        writer.dispose();
    }
}
