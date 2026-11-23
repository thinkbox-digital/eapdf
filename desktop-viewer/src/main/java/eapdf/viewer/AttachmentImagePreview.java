package eapdf.viewer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

final class AttachmentImagePreview {
    static final int WIDTH = 760;
    static final int HEIGHT = 560;
    private static final int MAX_SOURCE_SIDE = 8192;
    private static final long MAX_SOURCE_PIXELS = 16_000_000;
    private static final long MAX_SOURCE_RASTER_BYTES = 64L * 1024 * 1024;
    private static final Set<String> FORMATS = Set.of("png", "jpeg", "gif");

    private AttachmentImagePreview() {
    }

    static BufferedImage decode(byte[] payload) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(payload))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("This file is not a supported preview image.");
            var reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new IOException("Only PNG, JPEG, and GIF images can be previewed.");
                }
                reader.setInput(input, true, true);
                // Read the first image only. Animated GIFs use a still preview.
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE
                        || pixels > MAX_SOURCE_PIXELS) {
                    throw new IOException("The image dimensions exceed the preview limit.");
                }
                var type = reader.getRawImageType(0);
                int bytesPerPixel = type == null ? 8 : Math.max(4, (type.getColorModel().getPixelSize() + 7) / 8);
                if (pixels * bytesPerPixel > MAX_SOURCE_RASTER_BYTES) {
                    throw new IOException("The image exceeds the decoded memory limit for previews.");
                }
                var parameters = reader.getDefaultReadParam();
                int stride = Math.max((width + WIDTH - 1) / WIDTH, (height + HEIGHT - 1) / HEIGHT);
                parameters.setSourceSubsampling(stride, stride, 0, 0);
                var thumbnail = reader.read(0, parameters);
                if (thumbnail.getWidth() > WIDTH || thumbnail.getHeight() > HEIGHT) {
                    throw new IOException("The decoder exceeded the maximum preview size.");
                }
                return thumbnail;
            } finally {
                reader.dispose();
            }
        }
    }
}
