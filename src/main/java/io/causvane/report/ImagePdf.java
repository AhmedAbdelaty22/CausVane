package io.causvane.report;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes pictures as a PDF, one picture per page, using only what Java itself provides. Used for the
 * meeting slides, which are drawn exactly as they appear on screen and then saved page by page.
 */
public final class ImagePdf {

    private ImagePdf() {}

    /**
     * @param pages      the pictures, all drawn for the same page size
     * @param pageWidth  page width in points (1/72 inch); the height follows each picture's proportions
     */
    public static void write(List<BufferedImage> pages, float pageWidth, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            write(pages, pageWidth, out);
        }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public static void write(List<BufferedImage> pages, float pageWidth, OutputStream target) throws IOException {
        Counting out = new Counting(target);
        List<Long> offsets = new ArrayList<>();
        int objects = 2 + pages.size() * 3;
        out.text("%PDF-1.4\n%âãÏÓ\n");

        offsets.add(out.count);
        out.text("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add(out.count);
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(3 + i * 3).append(" 0 R ");
        }
        out.text("2 0 obj\n<< /Type /Pages /Count " + pages.size() + " /Kids [" + kids.toString().strip() + "] >>\nendobj\n");

        for (int i = 0; i < pages.size(); i++) {
            BufferedImage image = pages.get(i);
            int page = 3 + i * 3;
            float width = pageWidth;
            float height = pageWidth * image.getHeight() / image.getWidth();
            byte[] jpeg = jpeg(image);
            String drawing = "q " + fmt(width) + " 0 0 " + fmt(height) + " 0 0 cm /Im0 Do Q\n";

            offsets.add(out.count);
            out.text(page + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + fmt(width) + " " + fmt(height) + "]"
                    + " /Resources << /XObject << /Im0 " + (page + 2) + " 0 R >> >> /Contents " + (page + 1) + " 0 R >>\nendobj\n");
            offsets.add(out.count);
            out.text((page + 1) + " 0 obj\n<< /Length " + drawing.length() + " >>\nstream\n" + drawing + "endstream\nendobj\n");
            offsets.add(out.count);
            out.text((page + 2) + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + image.getWidth() + " /Height " + image.getHeight()
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length + " >>\nstream\n");
            out.bytes(jpeg);
            out.text("\nendstream\nendobj\n");
        }

        long xref = out.count;
        StringBuilder table = new StringBuilder("xref\n0 " + (objects + 1) + "\n0000000000 65535 f \n");
        for (long offset : offsets) {
            table.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        }
        out.text(table.toString());
        out.text("trailer\n<< /Size " + (objects + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        out.flush();
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.92f);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static String fmt(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** Keeps count of the bytes written, for the cross-reference table. */
    private static final class Counting {
        private final OutputStream out;
        private long count;

        Counting(OutputStream out) {
            this.out = out;
        }

        void text(String s) throws IOException {
            bytes(s.getBytes(StandardCharsets.ISO_8859_1));
        }

        void bytes(byte[] b) throws IOException {
            out.write(b);
            count += b.length;
        }

        void flush() throws IOException {
            out.flush();
        }
    }
}
