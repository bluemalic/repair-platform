package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.MetricsRecorder;
import com.bluemalic.repair.config.AiProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiChatModelProviderTest {

    @Test
    void rejectsConcurrentCallAndReleasesPermitAfterCompletion() throws Exception {
        AiProperties properties = new AiProperties("key", "http://localhost", "test-model",
                "ro", "ro-password", 3000, 1000, 10, true, 1);
        AiChatModelProvider provider = new AiChatModelProvider(
                properties, new MetricsRecorder(new SimpleMeterRegistry()), 1);
        CountDownLatch callStarted = new CountDownLatch(1);
        CountDownLatch releaseCall = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<String> firstCall = executor.submit(() -> provider.call("test", () -> {
                callStarted.countDown();
                try {
                    if (!releaseCall.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release the model call");
                    }
                    return "first";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));

            assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> provider.call("test", () -> "second"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

            releaseCall.countDown();
            assertThat(firstCall.get(5, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(provider.call("test", () -> "after release")).isEqualTo("after release");
        } finally {
            releaseCall.countDown();
            executor.shutdownNow();
        }
    }
}
