package com.skillforge.institution.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.skillforge.runtime.api.media.ModelImagePolicy;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

/** 实际解码、方向与颜色规范化；声明尺寸和文件扩展名不能替代像素验证。 */
final class SessionMediaImages {
    static final int MAX_BYTES = 50 * 1024 * 1024;
    static final long MAX_PIXELS = 40_000_000;
    static final String TRANSFORM_VERSION = "image-v1";
    record Decoded(BufferedImage pixels, String mime, int orientation) { }
    record Encoded(byte[] bytes, String mime, int width, int height) { }

    static Decoded decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String mime = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "webp" -> "image/webp";
                    default -> throw invalid();
                };
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) throw invalid();
                BufferedImage pixels = reader.read(0);
                if (pixels == null || pixels.getWidth() != width || pixels.getHeight() != height) throw invalid();
                int orientation = 1;
                if (mime.equals("image/jpeg")) {
                    var exif = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes))
                            .getFirstDirectoryOfType(ExifIFD0Directory.class);
                    if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION))
                        orientation = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                }
                return new Decoded(pixels, mime, orientation);
            } finally { reader.dispose(); }
        } catch (IOException | com.drew.imaging.ImageProcessingException | com.drew.metadata.MetadataException error) {
            throw new IllegalArgumentException("image_decode_failed", error);
        }
    }

    static BufferedImage normalize(Decoded source) {
        var input = source.pixels();
        int w = input.getWidth(), h = input.getHeight(), orientation = source.orientation();
        boolean swap = orientation >= 5 && orientation <= 8;
        var output = new BufferedImage(swap ? h : w, swap ? w : h,
                input.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        // getRGB/setRGB 将源色彩空间转换为 sRGB；同时保留 EXIF 镜像与旋转语义。
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int dx = switch (orientation) { case 2, 3 -> w - 1 - x; case 5, 8 -> y; case 6, 7 -> h - 1 - y; default -> x; };
            int dy = switch (orientation) { case 3, 4 -> h - 1 - y; case 5, 6 -> x; case 7, 8 -> w - 1 - x; default -> y; };
            output.setRGB(dx, dy, input.getRGB(x, y));
        }
        return output;
    }

    static Encoded variant(BufferedImage pixels, ModelImagePolicy policy) {
        int width = pixels.getWidth(), height = pixels.getHeight();
        double scale = Math.min(1d, (double) policy.maxDimension() / Math.max(width, height));
        for (;;) {
            int w = Math.max(1, (int) Math.floor(width * scale)), h = Math.max(1, (int) Math.floor(height * scale));
            var resized = new BufferedImage(w, h, pixels.getColorModel().hasAlpha()
                    ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var graphics = resized.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(pixels, 0, 0, w, h, null);
            } finally { graphics.dispose(); }
            var result = encode(resized, resized.getColorModel().hasAlpha() ? "image/png" : "image/jpeg");
            if (result.bytes().length <= policy.maxImageBytes()) return result;
            if (w == 1 && h == 1) throw new IllegalArgumentException("image_request_limit_exceeded");
            scale *= Math.min(0.8d, Math.sqrt((double) policy.maxImageBytes() / result.bytes().length) * 0.9d);
        }
    }

    static Encoded encode(BufferedImage pixels, String mime) {
        try (var bytes = new ByteArrayOutputStream(); var output = ImageIO.createImageOutputStream(bytes)) {
            var writer = ImageIO.getImageWritersByMIMEType(mime).next();
            try {
                writer.setOutput(output);
                var parameters = writer.getDefaultWriteParam();
                if (mime.equals("image/jpeg")) {
                    parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    parameters.setCompressionQuality(0.85f);
                }
                writer.write(null, new IIOImage(pixels, null, null), parameters);
                output.flush();
            } finally { writer.dispose(); }
            byte[] actual = bytes.toByteArray();
            var verified = decode(actual);
            if (!verified.mime().equals(mime) || verified.pixels().getWidth() != pixels.getWidth()
                    || verified.pixels().getHeight() != pixels.getHeight()) throw invalid();
            return new Encoded(actual, mime, pixels.getWidth(), pixels.getHeight());
        } catch (IOException error) { throw new IllegalStateException("image_encode_failed", error); }
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("image_content_invalid"); }
}
