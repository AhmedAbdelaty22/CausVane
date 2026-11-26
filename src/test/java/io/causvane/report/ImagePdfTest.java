package io.causvane.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Pictures saved as a PDF")
class ImagePdfTest {

    @Test
    void onePagePerPictureWithACorrectIndex() throws IOException {
        List<BufferedImage> pages = List.of(new BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB),
                new BufferedImage(160, 90, BufferedImage.TYPE_INT_ARGB));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImagePdf.write(pages, 842f, out);
        String pdf = out.toString(StandardCharsets.ISO_8859_1);
        assertTrue(pdf.startsWith("%PDF-1.4"));
        assertTrue(pdf.contains("/Type /Pages /Count 2"));
        assertTrue(pdf.contains("/MediaBox [0 0 842.00 473.63]"), "the page keeps the picture's proportions");
        assertTrue(pdf.endsWith("%%EOF\n"));
        // every object starts exactly where the cross-reference table says
        int xref = Integer.parseInt(pdf.substring(pdf.lastIndexOf("startxref\n") + 10, pdf.lastIndexOf("\n%%EOF")).strip());
        String[] rows = pdf.substring(xref).split("\n");
        for (int object = 1; object <= 8; object++) {
            int offset = Integer.parseInt(rows[2 + object].substring(0, 10));
            assertEquals(object + " 0 obj", pdf.substring(offset, offset + (object + " 0 obj").length()));
        }
    }
}
