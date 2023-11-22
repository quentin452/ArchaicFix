package org.embeddedt.archaicfix.occlusion;

import net.minecraft.world.chunk.Chunk;
import org.embeddedt.archaicfix.occlusion.util.LinkedHashList;
import org.embeddedt.archaicfix.occlusion.util.SynchronizedIdentityLinkedHashList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ChunkThread extends Thread {
    public final List<Chunk> loadedChunks = new ArrayList<>();
    public final List<Chunk> modifiedChunks = new ArrayList<>();

    public ChunkThread() {
        super("Chunk Worker");
    }
    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            processLoadedChunks();
            processModifiedChunks();
            updateOcclusionState();

            try {
                TimeUnit.MILLISECONDS.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processLoadedChunks() {
        for (int i = 0; i < loadedChunks.size(); i++) {
            Chunk chunk = ((ICulledChunk) loadedChunks.remove(0)).buildCulledSides();
            if (chunk != null) {
                modifiedChunks.add(chunk);
            }
            if ((i & 3) == 0) {
                Thread.yield();
            }
        }
    }

    private void processModifiedChunks() {
        for (int i = 0; i < modifiedChunks.size(); i++) {
            Chunk chunk = modifiedChunks.remove(0);
            if (loadedChunks.contains(chunk)) {
                continue;
            }
            processVisGraphs((ICulledChunk) chunk);
            if ((i & 7) == 0) {
                Thread.yield();
            }
        }
    }

    private void processVisGraphs(ICulledChunk chunk) {
        for (VisGraph graph : chunk.getVisibility()) {
            if (graph.isDirty()) {
                long oldVisibility = graph.getVisibility();
                graph.computeVisibility();
                if (oldVisibility != graph.getVisibility()) {
                    OcclusionHelpers.worker.dirty = true;
                }
            }
        }
    }

    private void updateOcclusionState() {
        OcclusionHelpers.worker.dirty = !modifiedChunks.isEmpty();
    }
}