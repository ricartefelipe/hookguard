package br.com.ricarte.hookguard.delivery;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Test-classpath only. Used by scripts/worker-crash-drill.sh, which starts the application with
 * target/test-classes on the classpath and sets HOOKGUARD_TEST_PAUSE_BEFORE_SEND_DIR. The packaged
 * jar does not contain this class, so nothing here can be switched on in a deployment.
 *
 * After a job has been claimed (the claim transaction is committed) and before the HTTP request is
 * started, the worker writes the file "claimed" into the directory and then blocks for as long as
 * the file "hold" exists. The drill kills the process during that block.
 */
@Component
@ConditionalOnProperty(name = "hookguard.test.pause-before-send-dir")
class CrashDrillHook {

    private final DeliveryWorker worker;
    private final Path dir;

    CrashDrillHook(DeliveryWorker worker, @Value("${hookguard.test.pause-before-send-dir}") String dir) {
        this.worker = worker;
        this.dir = Path.of(dir);
    }

    @PostConstruct
    void install() {
        worker.setBeforeSend(() -> {
            try {
                Files.writeString(dir.resolve("claimed"), "claimed\n");
                while (Files.exists(dir.resolve("hold"))) {
                    Thread.sleep(20);
                }
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
