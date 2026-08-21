package de.tstieh.stonesync.search;

import de.tstieh.stonesync.logging.AppLog;
import de.tstieh.stonesync.sync.DocumentService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Decides how (if at all) to extract search-indexable text from a freshly uploaded attachment,
 * and runs it off the request thread (see {@link AsyncConfig}) - OCR can take seconds, and the
 * plugin is waiting on the upload HTTP response, not on search indexing.
 */
@Service
public class AttachmentTextExtractionService {

    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of("png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp");

    private final PdfTextExtractionService pdfTextExtractionService;
    private final OcrService ocrService;
    private final DocumentService documentService;

    public AttachmentTextExtractionService(PdfTextExtractionService pdfTextExtractionService,
                                            OcrService ocrService, DocumentService documentService) {
        this.pdfTextExtractionService = pdfTextExtractionService;
        this.ocrService = ocrService;
        this.documentService = documentService;
    }

    @Async("textExtractionExecutor")
    public void extractAndIndex(UUID documentId, byte[] bytes, String path) {
        String extension = extensionOf(path);
        String text;
        if ("pdf".equals(extension)) {
            text = pdfTextExtractionService.extractText(bytes);
        } else if (IMAGE_EXTENSIONS.contains(extension)) {
            text = ocrService.extractText(bytes, extension);
        } else {
            // Unsupported format for content extraction - the filename alone is still indexed
            // (search_vector weight B, see migration V7), so it stays findable by name.
            return;
        }

        if (text == null || text.isBlank()) {
            return;
        }
        AppLog.info("Indexed {} extracted characters for attachment {} ('{}')", text.length(), documentId, path);
        documentService.updatePlainText(documentId, text);
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
