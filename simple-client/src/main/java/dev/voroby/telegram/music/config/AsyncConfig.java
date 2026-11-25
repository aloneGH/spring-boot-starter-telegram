package dev.voroby.telegram.music.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 1GB 内存环境专用隔离线程池：
 * - ingestExecutor：单线程控制 TDLib 拉取频率，防 429
 * - transcodeExecutor：严格单线程，保证同一时刻最多 1 个 ffmpeg 进程
 * - lyricExecutor：单线程低速拉取歌词
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "ingestExecutor")
    public Executor ingestExecutor() {
        return singleThreadExecutor("music-ingest-", 32);
    }

    @Bean(name = "transcodeExecutor")
    public Executor transcodeExecutor() {
        return singleThreadExecutor("music-transcode-", 16);
    }

    @Bean(name = "lyricExecutor")
    public Executor lyricExecutor() {
        return singleThreadExecutor("music-lyric-", 32);
    }

    private ThreadPoolTaskExecutor singleThreadExecutor(String prefix, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
