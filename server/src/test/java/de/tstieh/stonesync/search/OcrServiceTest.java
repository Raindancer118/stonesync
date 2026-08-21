package de.tstieh.stonesync.search;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@code tesseract} binary (not mocked) - this project's dev/CI environment
 * has it installed (also required in the Docker image, see {@code Dockerfile}), so a real OCR
 * round-trip is more useful here than mocking the one thing this class exists to wrap.
 */
class OcrServiceTest {

    private final OcrService service = new OcrService();

    @Test
    void recognizesClearTextInAnImage() throws IOException {
        byte[] image = renderTextImage("STONESYNC TEST");

        String text = service.extractText(image, "png");

        assertThat(text.toUpperCase()).contains("STONESYNC");
    }

    @Test
    void returnsEmptyStringForGarbageBytesInsteadOfThrowing() {
        byte[] notAnImage = {1, 2, 3, 4, 5};

        String text = service.extractText(notAnImage, "png");

        assertThat(text).isEmpty();
    }

    private static byte[] renderTextImage(String text) throws IOException {
        BufferedImage image = new BufferedImage(600, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("SansSerif", Font.BOLD, 48));
        graphics.drawString(text, 20, 75);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
