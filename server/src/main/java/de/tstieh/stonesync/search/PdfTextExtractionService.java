package de.tstieh.stonesync.search;

import de.tstieh.stonesync.logging.AppLog;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Extracts text from a PDF for search indexing: the embedded text layer when there is one (fast,
 * exact), or - for a scanned PDF with no text layer - OCR over each rendered page (capped at
 * {@link #MAX_OCR_PAGES}, since OCR-ing an arbitrarily long scanned document could take minutes
 * and this runs on a small background thread pool - see {@link AsyncConfig}).
 */
@Service
public class PdfTextExtractionService {

    private static final int MAX_OCR_PAGES = 20;
    private static final int OCR_RENDER_DPI = 150;

    private final OcrService ocrService;

    public PdfTextExtractionService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public String extractText(byte[] pdfBytes) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String embedded = new PDFTextStripper().getText(document);
            if (embedded != null && !embedded.isBlank()) {
                return embedded;
            }
            return ocrScannedPages(document);
        } catch (IOException e) {
            AppLog.warn("PDF text extraction failed: {}", e.getMessage());
            return "";
        }
    }

    private String ocrScannedPages(PDDocument document) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        int pageCount = Math.min(document.getNumberOfPages(), MAX_OCR_PAGES);
        StringBuilder text = new StringBuilder();
        for (int page = 0; page < pageCount; page++) {
            BufferedImage image = renderer.renderImageWithDPI(page, OCR_RENDER_DPI, ImageType.GRAY);
            text.append(ocrService.extractText(toPng(image), "png")).append('\n');
        }
        return text.toString();
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
