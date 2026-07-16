package com.example.oraclebench.web;

import com.example.oraclebench.service.SetupService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streams the one-click course setup to the browser terminal via Server-Sent
 * Events. Each SSE message is one terminal line (with ANSI colours); a final
 * {@code done} event tells the client to stop listening (so EventSource does not
 * auto-reconnect and re-run the setup).
 */
@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setup;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "setup-runner");
        t.setDaemon(true);
        return t;
    });

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    /** Reports whether Docker and the lab container are available (checked before setup). */
    @GetMapping("/status")
    public SetupService.SetupStatus status() {
        return setup.status();
    }

    @GetMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run() {
        SseEmitter emitter = new SseEmitter(20 * 60 * 1000L); // 20 minutes

        if (!setup.tryLock()) {
            executor.submit(() -> {
                sendQuietly(emitter, "message", "[33mUn setup è già in corso. Riprova tra poco.[0m");
                sendQuietly(emitter, "done", "busy");
                emitter.complete();
            });
            return emitter;
        }

        executor.submit(() -> {
            try {
                String outcome = setup.run(line -> sendOrThrow(emitter, line));
                sendQuietly(emitter, "done", outcome);
            } catch (Exception e) {
                sendQuietly(emitter, "message", "[31mERRORE: " + e.getMessage() + "[0m");
                sendQuietly(emitter, "done", "error");
            } finally {
                setup.unlock();
                emitter.complete();
            }
        });
        return emitter;
    }

    private void sendOrThrow(SseEmitter emitter, String data) {
        try {
            emitter.send(SseEmitter.event().data(data, MediaType.TEXT_PLAIN));
        } catch (IOException e) {
            // client disconnected — abort the stream
            throw new RuntimeException("client disconnected", e);
        }
    }

    private void sendQuietly(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.TEXT_PLAIN));
        } catch (IOException ignored) {
            // nothing we can do if the client is gone
        }
    }
}
