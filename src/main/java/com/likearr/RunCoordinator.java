package com.likearr;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Prevents web, scheduled and CLI runs from modifying the same state at once. */
final class RunCoordinator {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final Path LOCK = Path.of("/state/likearr-run.lock");
    private RunCoordinator() {}

    static boolean running() { return RUNNING.get(); }

    static boolean start(String label) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        Thread worker = new Thread(() -> {
            try { cycle(label); }
            catch (Exception ex) {
                System.err.println(label + " failed: " + ex.getMessage());
                Dashboard.record("error", label, "Failed; check container logs");
            } finally { RUNNING.set(false); }
        }, "likearr-run");
        worker.start();
        return true;
    }

    static int once(boolean apply) throws Exception {
        if (!apply) return new Likearr().run(false);
        Files.createDirectories(LOCK.getParent());
        try (FileChannel channel = FileChannel.open(LOCK, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException ex) { lock = null; }
            if (lock == null) { System.err.println("A Likearr run is already in progress."); return 1; }
            try (FileLock acquired = lock) { return new Likearr().run(true); }
        }
    }

    private static void cycle(String label) throws Exception {
        Files.createDirectories(LOCK.getParent());
        try (FileChannel channel = FileChannel.open(LOCK, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException ex) { lock = null; }
            if (lock == null) { Dashboard.record("error", label, "Another run is already in progress"); return; }
            try (FileLock acquired = lock) {
                Dashboard.record("run", label, "Started");
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(45);
                do {
                    Likearr app = new Likearr();
                    int result = app.run(true);
                    if (result != 0 || app.pending.isEmpty()) {
                        Dashboard.record(result == 0 ? "complete" : "error", label, result == 0 ? "Completed" : "Completed with errors");
                        return;
                    }
                    if (System.nanoTime() >= deadline) break;
                    Thread.sleep(60_000);
                } while (System.nanoTime() < deadline);
                Dashboard.record("pending", label, "Still waiting for Jellyfin; will retry at the next run");
            }
        }
    }
}
