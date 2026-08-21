package de.tstieh.stonesync.search;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * A small, dedicated pool for attachment text extraction (PDF parsing, OCR) - deliberately not
 * Spring's default {@code SimpleAsyncTaskExecutor}, which spawns one unbounded thread per task.
 * OCR is CPU-heavy and can take a few seconds per page; a bounded pool keeps a burst of uploads
 * (e.g. an initial vault upload) from starving the rest of the server instead of failing fast.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "textExtractionExecutor")
    public Executor textExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("text-extraction-");
        executor.initialize();
        return executor;
    }
}
