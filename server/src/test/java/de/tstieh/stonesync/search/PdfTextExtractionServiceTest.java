package de.tstieh.stonesync.search;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PDFBox + real {@code tesseract} (see {@link OcrServiceTest}) - both text-layer extraction
 * and the scanned-page OCR fallback are exercised against actual PDFs built on the fly, not mocks.
 */
class PdfTextExtractionServiceTest {

    private final PdfTextExtractionService service = new PdfTextExtractionService(new OcrService());

    @Test
    void extractsEmbeddedTextWithoutNeedingOcr() throws IOException {
        byte[] pdf = pdfWithEmbeddedText("Quarterly report: revenue grew by twelve percent.");

        String text = service.extractText(pdf);

        assertThat(text).contains("Quarterly report");
    }

    @Test
    void ocrsAScannedPageThatHasNoEmbeddedTextLayer() throws IOException {
        byte[] pdf = pdfWithOnlyAScannedLookingImage("SCANNEDDOC");

        String text = service.extractText(pdf);

        assertThat(text.toUpperCase()).contains("SCANNEDDOC");
    }

    @Test
    void returnsEmptyStringForCorruptPdfBytesInsteadOfThrowing() {
        byte[] notAPdf = "definitely not a pdf".getBytes();

        String text = service.extractText(notAPdf);

        assertThat(text).isEmpty();
    }

    private static byte[] pdfWithEmbeddedText(String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** A PDF page whose only content is an embedded raster image with text drawn onto it, and no
     * PDF text-layer at all - the same shape as a scanned document. */
    private static byte[] pdfWithOnlyAScannedLookingImage(String text) throws IOException {
        BufferedImage image = new BufferedImage(800, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("SansSerif", Font.BOLD, 60));
        graphics.drawString(text, 20, 120);
        graphics.dispose();

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(pdImage, 50, 500, 400, 100);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
