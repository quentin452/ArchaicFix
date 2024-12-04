package org.embeddedt.archaicfix.lighting.world.lighting;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.profiler.Profiler;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.embeddedt.archaicfix.lighting.api.IChunkLighting;
import org.embeddedt.archaicfix.lighting.api.ILightingEngine;
import org.embeddedt.archaicfix.lighting.collections.PooledLongQueue;

import java.util.concurrent.atomic.AtomicBoolean;

public class LightingEngine implements ILightingEngine {
    private static final int MAX_SCHEDULED_COUNT = 1 << 22;
    private static final int MAX_LIGHT = 15;
    private final World world;
    private final Profiler profiler;
    private final PooledLongQueue[] queuedLightUpdates = new PooledLongQueue[EnumSkyBlock.values().length];
    private final PooledLongQueue[] queuedDarkenings = new PooledLongQueue[MAX_LIGHT + 1];
    private final PooledLongQueue[] queuedBrightenings = new PooledLongQueue[MAX_LIGHT + 1];
    private final PooledLongQueue initialBrightenings;
    private final PooledLongQueue initialDarkenings;
    private final AtomicBoolean updating = new AtomicBoolean(false);
    private static final int lX = 26, lY = 8, lZ = 26, lL = 4;
    private static final int sZ = 0, sX = sZ + lZ, sY = sX + lX, sL = sY + lY;
    private static final long mX = (1L << lX) - 1, mY = (1L << lY) - 1, mZ = (1L << lZ) - 1, mL = (1L << lL) - 1, mPos = (mY << sY) | (mX << sX) | (mZ << sZ);
    private static final long yCheck = 1L << (sY + lY);
    private static final long[] neighborShifts = new long[6];
    static {
        EnumFacing[] values = new EnumFacing[] { EnumFacing.DOWN, EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST };
        for (int i = 0; i < 6; ++i) {
            neighborShifts[i] = ((long) values[i].getFrontOffsetY() << sY) | ((long) values[i].getFrontOffsetX() << sX) | ((long) values[i].getFrontOffsetZ() << sZ);
        }
    }
    private static final long mChunk = ((mX >> 4) << (4 + sX)) | ((mZ >> 4) << (4 + sZ));
    private final BlockPos.MutableBlockPos curPos = new BlockPos.MutableBlockPos();
    private Chunk curChunk;
    private long curChunkIdentifier;
    private long curData;
    static boolean isDynamicLightsLoaded;
    private boolean isNeighborDataValid = false;
    private final NeighborInfo[] neighborInfos = new NeighborInfo[6];
    private PooledLongQueue.LongQueueIterator queueIt;

    public LightingEngine(final World world) {
        this.world = world;
        this.profiler = world.theProfiler;
        isDynamicLightsLoaded = Loader.isModLoaded("DynamicLights");
        PooledLongQueue.Pool pool = new PooledLongQueue.Pool();
        this.initialBrightenings = new PooledLongQueue(pool);
        this.initialDarkenings = new PooledLongQueue(pool);
        for (int i = 0; i < EnumSkyBlock.values().length; ++i) {
            this.queuedLightUpdates[i] = new PooledLongQueue(pool);
        }
        for (int i = 0; i < this.queuedDarkenings.length; ++i) {
            this.queuedDarkenings[i] = new PooledLongQueue(pool);
        }
        for (int i = 0; i < this.queuedBrightenings.length; ++i) {
            this.queuedBrightenings[i] = new PooledLongQueue(pool);
        }
        for (int i = 0; i < this.neighborInfos.length; ++i) {
            this.neighborInfos[i] = new NeighborInfo();
        }
    }

    @Override
    public void scheduleLightUpdate(final EnumSkyBlock lightType, final int xIn, final int yIn, final int zIn) {
        this.scheduleLightUpdate(lightType, encodeWorldCoord(xIn, yIn, zIn));
    }

    private void scheduleLightUpdate(final EnumSkyBlock lightType, final long pos) {
        final PooledLongQueue queue = this.queuedLightUpdates[lightType.ordinal()];
        queue.add(pos);
        if (queue.size() >= MAX_SCHEDULED_COUNT) {
            this.processLightUpdatesForType(lightType);
        }
    }

    @Override
    public void processLightUpdates() {
        this.processLightUpdatesForType(EnumSkyBlock.Sky);
        this.processLightUpdatesForType(EnumSkyBlock.Block);
    }

    @Override
    public synchronized void processLightUpdatesForType(final EnumSkyBlock lightType) {
        if (!this.world.isRemote || this.isCallingFromMainThread()) {
            final PooledLongQueue queue = this.queuedLightUpdates[lightType.ordinal()];
            if (queue.size() == 0) {
                return;
            }
            this.processLightUpdatesForTypeInner(lightType, queue);
        }
    }

    @SideOnly(Side.CLIENT)
    private boolean isCallingFromMainThread() {
        return Minecraft.getMinecraft().func_152345_ab();
    }

    private void processLightUpdatesForTypeInner(final EnumSkyBlock lightType, final PooledLongQueue queue) {
        if (this.updating.compareAndSet(false, true)) {
            try {
                this.curChunkIdentifier = -1;
                processQueuedUpdates(lightType, queue);
                processInitialBrightenings(lightType);
                processInitialDarkenings(lightType);
                iterateThroughQueuedUpdates(lightType);
            } finally {
                this.updating.set(false);
            }
        }
    }

    private void processQueuedUpdates(final EnumSkyBlock lightType, final PooledLongQueue queue) {
        this.profiler.startSection("lighting");
        this.profiler.startSection("checking");
        this.queueIt = queue.iterator();
        while (this.nextItem()) {
            if (this.curChunk == null) {
                continue;
            }
            processQueuedUpdate(lightType);
        }
    }

    private void processQueuedUpdate(final EnumSkyBlock lightType) {
        if (this.curChunk == null) {
            return;
        }
        final int oldLight = this.getCursorCachedLight(lightType);
        final int newLight = this.calculateNewLightFromCursor(lightType);
        if (oldLight < newLight) {
            this.initialBrightenings.add(((long) newLight << sL) | this.curData);
        } else if (oldLight > newLight) {
            this.initialDarkenings.add(this.curData);
        }
    }

    private void processInitialBrightenings(final EnumSkyBlock lightType) {
        this.queueIt = this.initialBrightenings.iterator();

        while (this.nextItem()) {
            processInitialBrightening(lightType);
        }
    }

    private void processInitialBrightening(final EnumSkyBlock lightType) {
        if (this.curChunk == null) {
            return;
        }
        final int newLight = (int) (this.curData >> sL & mL);
        final int cachedLight = this.getCursorCachedLight(lightType);
        if (newLight > cachedLight) {
            this.enqueueBrightening(this.curPos, this.curData & mPos, newLight, this.curChunk, lightType);
        }
    }


    private void processInitialDarkenings(final EnumSkyBlock lightType) {
        this.queueIt = this.initialDarkenings.iterator();
        while (this.nextItem()) {
            processInitialDarkening(lightType);
        }
    }

    private void processInitialDarkening(final EnumSkyBlock lightType) {
        if (this.curChunk == null) {
            return;
        }
        final int oldLight = this.getCursorCachedLight(lightType);
        if (oldLight != 0) {
            this.enqueueDarkening(this.curPos, this.curData, oldLight, this.curChunk, lightType);
        }
    }

    private void iterateThroughQueuedUpdates(final EnumSkyBlock lightType) {
        for (int curLight = MAX_LIGHT; curLight >= 0; --curLight) {
            this.profiler.startSection("darkening");
            this.queueIt = this.queuedDarkenings[curLight].iterator();
            while (this.nextItem()) {
                processQueuedDarkening(lightType, curLight);
            }
            this.profiler.endStartSection("brightening");
            this.queueIt = this.queuedBrightenings[curLight].iterator();
            while (this.nextItem()) {
                processQueuedBrightening(lightType, curLight);
            }
            this.profiler.endSection();
        }
    }

    private void processQueuedDarkening(final EnumSkyBlock lightType, final int curLight) {
        if (this.getCursorCachedLight(lightType) >= curLight) {
            return;
        }
        final Block state = LightingEngineHelpers.posToState(this.curPos, this.curChunk);
        final int luminosity = this.getCursorLuminosity(state, lightType);
        final int opacity;
        if (luminosity >= MAX_LIGHT - 1) {
            opacity = 1;
        } else {
            opacity = this.getPosOpacity(this.curPos, state);
        }
        if (this.calculateNewLightFromCursor(luminosity, opacity, lightType) < curLight) {
            int newLight = luminosity;
            this.fetchNeighborDataFromCursor(lightType);
            for (NeighborInfo info : this.neighborInfos) {
                final Chunk nChunk = info.chunk;

                if (nChunk == null) {
                    continue;
                }
                final int nLight = info.light;

                if (nLight == 0) {
                    continue;
                }
                final BlockPos.MutableBlockPos nPos = info.pos;

                if (curLight - this.getPosOpacity(nPos, LightingEngineHelpers.posToState(nPos, info.section)) >= nLight) {
                    this.enqueueDarkening(nPos, info.key, nLight, nChunk, lightType);
                } else {
                    newLight = Math.max(newLight, nLight - opacity);
                }
            }
            this.enqueueBrighteningFromCursor(newLight, lightType);
        } else {
            this.enqueueBrighteningFromCursor(curLight, lightType);
        }
    }

    private void processQueuedBrightening(final EnumSkyBlock lightType, final int curLight) {
        final int oldLight = this.getCursorCachedLight(lightType);
        if (oldLight == curLight) {
            this.world.func_147479_m(this.curPos.getX(), this.curPos.getY(), this.curPos.getZ());
            if (curLight > 1) {
                this.spreadLightFromCursor(curLight, lightType);
            }
        }
    }

    private void fetchNeighborDataFromCursor(final EnumSkyBlock lightType) {
        if (this.isNeighborDataValid) {
            return;
        }
        this.isNeighborDataValid = true;
        for (int i = 0; i < neighborInfos.length; i++) {
            NeighborInfo info = neighborInfos[i];
            updateNeighborInfo(info, i, lightType);
        }
    }

    private void updateNeighborInfo(NeighborInfo info, int index, EnumSkyBlock lightType) {
        final long newLongPos = info.key = this.curData + neighborShifts[index];
        if ((newLongPos & yCheck) != 0) {
            info.chunk = null;
            info.section = null;
            return;
        }
        final BlockPos.MutableBlockPos newPos = decodeWorldCoord(info.pos, newLongPos);
        final Chunk newChunk;
        if ((newLongPos & mChunk) == this.curChunkIdentifier) {
            newChunk = info.chunk = this.curChunk;
        } else {
            newChunk = info.chunk = this.getChunk(newPos);
        }
        if (newChunk != null) {
            ExtendedBlockStorage newSection = newChunk.getBlockStorageArray()[newPos.getY() >> 4];
            info.light = getCachedLightFor(newChunk, newSection, newPos, lightType);
            info.section = newSection;
        }
    }

    private static int getCachedLightFor(Chunk chunk, ExtendedBlockStorage storage, BlockPos pos, EnumSkyBlock type) {
        int i = pos.getX() & 15; // Local X within the chunk section
        int j = pos.getY(); // Global Y coordinate
        int k = pos.getZ() & 15; // Local Z within the chunk section

        // If the storage is null, handle it based on the light type
        if (storage == null) {
            if (type == EnumSkyBlock.Sky && chunk.canBlockSeeTheSky(i, j, k)) {
                return type.defaultLightValue;
            } else {
                return 0;
            }
        }

        // Ensure Y is within the valid range for the storage
        int localY = j & 15;
        if (localY < 0 || localY >= 16) {
            return 0; // Out-of-bounds access
        }

        // Handle sky light
        if (type == EnumSkyBlock.Sky) {
            if (chunk.worldObj.provider.hasNoSky) {
                return 0;
            } else {
                return storage.getExtSkylightValue(i, localY, k);
            }
        }

        // Handle block light
        if (type == EnumSkyBlock.Block) {
            return storage.getExtBlocklightValue(i, localY, k);
        }

        // Default case
        return type.defaultLightValue;
    }


    private int calculateNewLightFromCursor(final EnumSkyBlock lightType) {
        final Block state = LightingEngineHelpers.posToState(this.curPos, this.curChunk);
        final int luminosity = this.getCursorLuminosity(state, lightType);
        final int opacity = (luminosity >= MAX_LIGHT - 1) ? 1 : this.getPosOpacity(this.curPos, state);
        return this.calculateNewLightFromCursor(luminosity, opacity, lightType);
    }

    private int calculateNewLightFromCursor(final int luminosity, final int opacity, final EnumSkyBlock lightType) {
        if (luminosity >= MAX_LIGHT - opacity) {
            return luminosity;
        }
        int newLight = luminosity;
        this.fetchNeighborDataFromCursor(lightType);
        for (NeighborInfo info : this.neighborInfos) {
            if (info.chunk != null) {
                newLight = Math.max(info.light - opacity, newLight);
            }
        }
        return newLight;
    }

    private void spreadLightFromCursor(final int curLight, final EnumSkyBlock lightType) {
        this.fetchNeighborDataFromCursor(lightType);
        for (NeighborInfo info : this.neighborInfos) {
            final Chunk nChunk = info.chunk;
            if (nChunk != null) {
                int newLight = curLight - this.getPosOpacity(info.pos, LightingEngineHelpers.posToState(info.pos, info.section));
                if (newLight > info.light) {
                    this.enqueueBrightening(info.pos, info.key, newLight, nChunk, lightType);
                }
            }
        }
    }

    private void enqueueBrighteningFromCursor(final int newLight, final EnumSkyBlock lightType) {
        this.enqueueBrightening(this.curPos, this.curData, newLight, this.curChunk, lightType);
    }

    private void enqueueBrightening(final BlockPos pos, final long longPos, final int newLight, final Chunk chunk, final EnumSkyBlock lightType) {
        this.queuedBrightenings[newLight].add(longPos);

        chunk.setLightValue(lightType, pos.getX() & 15, pos.getY(), pos.getZ() &15, newLight);
    }

    private void enqueueDarkening(final BlockPos pos, final long longPos, final int oldLight, final Chunk chunk, final EnumSkyBlock lightType) {
        this.queuedDarkenings[oldLight].add(longPos);

        chunk.setLightValue(lightType, pos.getX() & 15, pos.getY(), pos.getZ() &15, 0);
    }

    private static BlockPos.MutableBlockPos decodeWorldCoord(final BlockPos.MutableBlockPos pos, final long longPos) {
        final int posX = (int) ((longPos >> sX) & mX) - (1 << (lX - 1));
        final int posY = (int) ((longPos >> sY) & mY);
        final int posZ = (int) ((longPos >> sZ) & mZ) - (1 << (lZ -1));
        return pos.setPos(posX, posY, posZ);
    }

    private static long encodeWorldCoord(final long x, final long y, final long z) {
        return (y << sY) | (x + (1 << lX - 1) << sX) | (z + (1 << lZ - 1) << sZ);
    }

    private boolean nextItem() {
        if (!this.queueIt.hasNext()) {
            this.queueIt.finish();
            this.queueIt = null;

            return false;
        }
        this.curData = this.queueIt.next();
        this.isNeighborDataValid = false;
        decodeWorldCoord(this.curPos, this.curData);
        final long chunkIdentifier = this.curData & mChunk;
        if (this.curChunkIdentifier != chunkIdentifier) {
            this.curChunk = this.getChunk(this.curPos);
            this.curChunkIdentifier = chunkIdentifier;
        }
        return true;
    }

    private int getCursorCachedLight(final EnumSkyBlock lightType) {
        if (this.curChunk == null) {
            return 0;
        }
        return ((IChunkLighting) this.curChunk).getCachedLightFor(lightType, this.curPos.getX(), this.curPos.getY(), this.curPos.getZ());
    }

    private int getCursorLuminosity(final Block state, final EnumSkyBlock lightType) {
        if (lightType == EnumSkyBlock.Sky) {
            if (this.curChunk.canBlockSeeTheSky(this.curPos.getX() & 15, this.curPos.getY(), this.curPos.getZ() &15)) {
                return EnumSkyBlock.Sky.defaultLightValue;
            } else {
                return 0;
            }
        }
        return MathHelper.clamp_int(LightingEngineHelpers.getLightValueForState(state, this.world, this.curPos.getX(), this.curPos.getY(), this.curPos.getZ()), 0, MAX_LIGHT);
    }

    private int getPosOpacity(final BlockPos pos, final Block state) {
        return MathHelper.clamp_int(state.getLightOpacity(world, pos.getX(), pos.getY(), pos.getZ()), 1, MAX_LIGHT);
    }

    private Chunk getChunk(final BlockPos pos) {
        IChunkProvider prov = this.world.getChunkProvider();
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return LightingEngineHelpers.getLoadedChunk(prov, chunkX, chunkZ);
    }

    private static class NeighborInfo {
        Chunk chunk;
        ExtendedBlockStorage section;
        int light;
        long key;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    }
}