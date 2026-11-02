package eapdf;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

final class ImageOrientation {
  private ImageOrientation() {}

  /** Returns a lossless display raster; the associated original JPEG remains untouched. */
  static byte[] orientedPng(byte[] jpeg) throws IOException {
    Integer orientation;
    try (var input = new ByteArrayInputStream(jpeg)) {
      var metadata = JpegMetadataReader.readMetadata(input, List.of(new ExifReader()));
      var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      orientation = directory == null ? null : directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
    } catch (com.drew.imaging.jpeg.JpegProcessingException error) {
      return null;
    }
    if (orientation == null || orientation <= 1 || orientation > 8) return null;
    // Check dimensions before allocating either decoded image.
    PdfMailWriter.sniffImageSize(jpeg);
    BufferedImage source = ImageIO.read(new ByteArrayInputStream(jpeg));
    if (source == null) return null;
    int width = source.getWidth();
    int height = source.getHeight();
    boolean swapped = orientation >= 5;
    BufferedImage target = new BufferedImage(swapped ? height : width, swapped ? width : height, BufferedImage.TYPE_INT_RGB);
    AffineTransform transform = switch (orientation) {
      case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
      case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
      case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
      case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
      case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
      case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
      case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
      default -> new AffineTransform();
    };
    var graphics = target.createGraphics();
    try {
      graphics.drawImage(source, transform, null);
    } finally {
      graphics.dispose();
    }
    var output = new ByteArrayOutputStream();
    return ImageIO.write(target, "png", output) ? output.toByteArray() : null;
  }
}
