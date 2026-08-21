package de.tstieh.stonesync.search;

import de.tstieh.stonesync.logging.AppLog;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shells out to the system {@code tesseract} binary (installed via the Dockerfile - {@code
 * tesseract-ocr}/{@code tesseract-ocr-eng}/{@code tesseract-ocr-deu}) rather than a JNA/native
 * binding library: this project's console tooling already shells out to CLI tools (curl, psql,
 * jq - see {@code Dockerfile}), and a plain process call sidesteps the native-library-version
 * matching that JNA-based OCR wrappers are notoriously fragile about in a slim container image.
 *
 * <p>Best-effort by design: any failure (binary missing, corrupt image, timeout) is logged and
 * returns an empty string rather than propagating - a missing/failed OCR result must never break
 * an attachment upload, since the extracted text is only ever used for search.</p>
 */
@Service
public class OcrService {

    private static final long TIMEOUT_SECONDS = 60;

    /** @param fileExtensionHint used only to pick a sensible temp-file suffix for format detection. */
    public String extractText(byte[] imageBytes, String fileExtensionHint) {
        Path input;
        try {
            input = Files.createTempFile("stonesync-ocr-in-", "." + safeExtension(fileExtensionHint));
        } catch (IOException e) {
            AppLog.warn("OCR skipped: could not create temp input file ({})", e.getMessage());
            return "";
        }

        Path outputBase = input.resolveSibling(input.getFileName() + "-out");
        Path outputTxt = Path.of(outputBase + ".txt");
        try {
            Files.write(input, imageBytes);
            Process process = new ProcessBuilder("tesseract", input.toString(), outputBase.toString(), "-l", "eng+deu")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                AppLog.warn("OCR timed out after {}s", TIMEOUT_SECONDS);
                return "";
            }
            if (process.exitValue() != 0 || !Files.exists(outputTxt)) {
                AppLog.warn("OCR failed (tesseract exit code {})", process.exitValue());
                return "";
            }
            return Files.readString(outputTxt);
        } catch (IOException e) {
            // Also the "tesseract binary not installed" case (e.g. running outside the Docker
            // image) - a warning, not an error, since the server keeps working without OCR.
            AppLog.warn("OCR unavailable: {}", e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            deleteQuietly(input);
            deleteQuietly(outputTxt);
        }
    }

    private static String safeExtension(String hint) {
        String cleaned = hint == null ? "" : hint.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return cleaned.isBlank() ? "img" : cleaned;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup of a temp file - leaving one behind is not worth failing over.
        }
    }
}
