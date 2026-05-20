package com.martinfou.trading.monitor;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Collects system-level metrics: CPU, memory, disk, git commit.
 * <p>
 * Designed to be safely callable from the health-check HTTP handler
 * without throwing exceptions (returns gracefully degraded values).
 */
public class SystemMetrics {

    private final OperatingSystemMXBean osBean;
    private final String cachedGitCommit;

    public SystemMetrics() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.cachedGitCommit = loadGitCommit();
    }

    // ---- CPU ---- //

    /**
     * Returns the CPU load as a percentage (0-100).
     * Returns -1 if unavailable (e.g., on some JVM implementations).
     */
    public double getCpuPercent() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                return Math.round(sunOs.getCpuLoad() * 100.0 * 10.0) / 10.0;
            }
            return osBean.getSystemLoadAverage();
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- Memory ---- //

    /**
     * Returns the JVM heap memory usage as a percentage (0-100).
     */
    public double getMemoryPercent() {
        try {
            var rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            long max = rt.maxMemory();
            if (max > 0) {
                return Math.round((double) used / max * 100.0 * 10.0) / 10.0;
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- Disk ---- //

    /**
     * Returns the disk usage of the root partition as a percentage (0-100).
     */
    public double getDiskPercent() {
        try {
            FileStore store = Files.getFileStore(Paths.get("/"));
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total > 0) {
                double used = (double) (total - usable) / total * 100.0;
                return Math.round(used * 10.0) / 10.0;
            }
            return -1;
        } catch (IOException e) {
            return -1;
        }
    }

    // ---- Git Commit ---- //

    /**
     * Returns the short git commit hash from .git/HEAD.
     * Returns "unknown" if not a git repository.
     */
    public String getGitCommit() {
        return cachedGitCommit;
    }

    private String loadGitCommit() {
        try {
            var headFile = new File(".git/HEAD");
            if (!headFile.exists()) {
                headFile = new File("../.git/HEAD");
            }
            if (!headFile.exists()) {
                return "unknown";
            }
            String head = Files.readString(headFile.toPath()).trim();
            if (head.startsWith("ref: ")) {
                String refPath = ".git/" + head.substring(5);
                var refFile = new File(refPath);
                if (!refFile.exists() && headFile.getParentFile() != null) {
                    refFile = new File(headFile.getParentFile(), head.substring(5));
                }
                if (refFile.exists()) {
                    String hash = Files.readString(refFile.toPath()).trim();
                    return hash.length() > 7 ? hash.substring(0, 7) : hash;
                }
                return "unknown";
            }
            return head.length() > 7 ? head.substring(0, 7) : head;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
