package de.tstieh.stonesync.search;

import de.tstieh.stonesync.sync.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentTextExtractionServiceTest {

    @Mock
    private PdfTextExtractionService pdfTextExtractionService;
    @Mock
    private OcrService ocrService;
    @Mock
    private DocumentService documentService;

    private AttachmentTextExtractionService service;
    private final UUID documentId = UUID.randomUUID();
    private final byte[] bytes = {1, 2, 3};

    @BeforeEach
    void setUp() {
        service = new AttachmentTextExtractionService(pdfTextExtractionService, ocrService, documentService);
    }

    @Test
    void pdfsAreRoutedToThePdfExtractor() {
        when(pdfTextExtractionService.extractText(bytes)).thenReturn("extracted pdf text");

        service.extractAndIndex(documentId, bytes, "Reports/Q3.pdf");

        verify(documentService).updatePlainText(documentId, "extracted pdf text");
        verify(ocrService, never()).extractText(any(), any());
    }

    @Test
    void imagesAreRoutedDirectlyToOcr() {
        when(ocrService.extractText(bytes, "png")).thenReturn("text found in image");

        service.extractAndIndex(documentId, bytes, "Assets/screenshot.png");

        verify(documentService).updatePlainText(documentId, "text found in image");
        verify(pdfTextExtractionService, never()).extractText(any());
    }

    @Test
    void unsupportedExtensionsAreSkippedEntirely() {
        service.extractAndIndex(documentId, bytes, "Assets/archive.zip");

        verify(pdfTextExtractionService, never()).extractText(any());
        verify(ocrService, never()).extractText(any(), any());
        verify(documentService, never()).updatePlainText(any(), any());
    }

    @Test
    void blankExtractionResultDoesNotOverwriteTheIndexWithNothing() {
        when(pdfTextExtractionService.extractText(bytes)).thenReturn("   ");

        service.extractAndIndex(documentId, bytes, "Reports/blank.pdf");

        verify(documentService, never()).updatePlainText(any(), any());
    }
}
