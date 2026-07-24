import java.io.File;
import java.io.IOException;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.List;
import java.util.ArrayList;

/**
 * Represents a media library on the filesystem.
 * Recursively scans directories for supported audio/video files.
 * Uses parallel processing for faster scanning on multi-core systems.
 */
public class cdd_sync_media_library implements Comparable<cdd_sync_media_library> {

    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            ".mp3", ".flac", ".wav", ".ogg", ".aif", ".aiff", ".aac", ".alac", ".m4a",
            ".mov", ".mp4", ".avi", ".flv", ".mpg", ".mpeg", ".dv", ".qtz");

    // Thread pool for parallel scanning
    private static final int NUM_THREADS = Math.min(4, Runtime.getRuntime().availableProcessors());
    private static final ForkJoinPool SCAN_POOL = new ForkJoinPool(NUM_THREADS);

    private String directory;
    private SortedSet<String> tracks = new TreeSet<String>();
    private SortedSet<cdd_sync_media_library> children = new TreeSet<cdd_sync_media_library>();

    public cdd_sync_media_library(String directory) {
        this.directory = directory;
    }

    public String getDirectory() {
        return directory;
    }

    public SortedSet<String> getTracks() {
        return tracks;
    }

    public SortedSet<cdd_sync_media_library> getChildren() {
        return children;
    }

    public int getTotalNumberOfTracks() {
        int result = tracks.size();
        for (cdd_sync_media_library childLibrary : children) {
            result += childLibrary.getTotalNumberOfTracks();
        }
        return result;
    }

    public int getTotalNumberOfDirectories() {
        int result = children.size();
        for (cdd_sync_media_library childLibrary : children) {
            result += childLibrary.getTotalNumberOfDirectories();
        }
        return result;
    }

    public void flattenTracks(java.util.List<String> list) {
        list.addAll(tracks);
        for (cdd_sync_media_library child : children) {
            child.flattenTracks(list);
        }
    }

    /**
     * Removes the specified tracks from this library and all children.
     * Used to remove moved duplicate files before building crates.
     * 
     * @param pathsToRemove List of absolute file paths to remove
     * @return Number of tracks removed
     */
    public int removeTracks(java.util.List<String> pathsToRemove) {
        int removed = 0;
        java.util.Set<String> toRemove = new java.util.HashSet<>(pathsToRemove);

        // Remove from this level
        java.util.Iterator<String> it = tracks.iterator();
        while (it.hasNext()) {
            if (toRemove.contains(it.next())) {
                it.remove();
                removed++;
            }
        }

        // Remove from children recursively
        for (cdd_sync_media_library child : children) {
            removed += child.removeTracks(pathsToRemove);
        }

        return removed;
    }

    public static cdd_sync_media_library readFrom(String mediaLibraryPath) {
        cdd_sync_media_library result = new cdd_sync_media_library(".");
        result.collectAll(mediaLibraryPath);
        return result;
    }

    private void collectAll(String path) {
        File[] all = new File(path).listFiles();
        if (all == null) {
            all = new File[] {};
        }

        // Process audio/video files
        for (File file : all) {
            if (file.isFile() && isMedia(file)) {
                try {
                    tracks.add(file.toPath().toRealPath().toString());
                } catch (IOException e) {
                    tracks.add(file.getAbsolutePath());
                }
            }
        }

        // Collect subdirectories for parallel processing
        List<File> subdirs = new ArrayList<>();
        for (File file : all) {
            if (file.isDirectory()) {
                subdirs.add(file);
            }
        }

        // Process subdirectories in parallel if there are multiple
        if (subdirs.size() > 1) {
            List<Future<cdd_sync_media_library>> futures = new ArrayList<>();

            for (File subdir : subdirs) {
                String childDirectory = subdir.getName();
                String childPath = path + "/" + childDirectory;

                futures.add(SCAN_POOL.submit(() -> {
                    cdd_sync_media_library child = new cdd_sync_media_library(childDirectory);
                    child.collectAll(childPath);
                    return child;
                }));
            }

            // Collect results
            for (Future<cdd_sync_media_library> future : futures) {
                try {
                    children.add(future.get());
                } catch (InterruptedException | ExecutionException e) {
                    cdd_sync_log.error("Error scanning directory: " + e.getMessage());
                }
            }
        } else {
            // Single or no subdirectory - process sequentially
            for (File subdir : subdirs) {
                String childDirectory = subdir.getName();
                cdd_sync_media_library child = new cdd_sync_media_library(childDirectory);
                child.collectAll(path + "/" + childDirectory);
                children.add(child);
            }
        }
    }

    private boolean isMedia(File file) {
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && MEDIA_EXTENSIONS.contains(name.substring(dot));
    }

    public int compareTo(cdd_sync_media_library that) {
        return this.directory.compareTo(that.directory);
    }

    public String toString() {
        return toString(0);
    }

    private String toString(int level) {
        StringBuilder result = new StringBuilder();
        result.append(indent(level)).append(directory).append("\n");
        for (String track : tracks) {
            result.append(indent(level + 1)).append(track).append("\n");
        }
        for (cdd_sync_media_library library : children) {
            result.append(library.toString(level + 1));
        }
        return result.toString();
    }

    private String indent(int level) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 2 * level; i++) {
            result.append(' ');
        }
        return result.toString();
    }
}
