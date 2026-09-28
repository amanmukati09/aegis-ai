package ai.aegis.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executors;

/**
 * Runs @Async jobs on JDK 21 virtual threads — cheap to block on, so long-running
 * jobs (bulk log analysis, multi-step diagnosis) don't tie up platform threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("jobExecutor")
    public AsyncTaskExecutor jobExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
