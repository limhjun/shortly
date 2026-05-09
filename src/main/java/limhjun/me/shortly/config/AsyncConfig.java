package limhjun.me.shortly.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.*;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "clickRecorderExecutor")
    public ExecutorService clickRecorderExecutor() {
        // Virtual-thread per task, but with a bounded backlog queue.
        // Spring's @Async resolves the executor by bean name.
        return new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10_000),
                Thread.ofVirtual().name("click-recorder-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy() // RejectedExecutionException on overflow
        );
    }
}
