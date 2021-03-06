/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;


import static io.netty.util.internal.ObjectUtil.checkPositiveOrZero;

import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="http://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);

    // 从分配器中获取到的一块内存区域  调用方：io.netty.buffer.PooledByteBufAllocator.newHeapBuffer
    final PoolArena<byte[]> heapArena;
    // 从分配器中获取到的一块内存区域   调用方：io.netty.buffer.PooledByteBufAllocator.newDirectBuffer
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are tiny, small and normal.
    private final MemoryRegionCache<byte[]>[] tinySubPageHeapCaches;
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;

    /*
     * 直接内存
     * tinySubPageDirectCaches[] 长度32
     * 第一个节点 不存数据
     * 第二个节点 16B   每个节点上又是一个queue
     * 第三个节点 32B   每个节点上又是一个queue
     * 第31个节点 496B  每个节点上又是一个queue
     */
    private final MemoryRegionCache<ByteBuffer>[] tinySubPageDirectCaches;
    // len = 4  [0]=512B的queue [1]=1K的queue [2]=2K的queue [3]=4K的queue
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    // len = 3  [0]=8K的queue [1]=16K的queue [2]=32K的queue
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;


    // 用于计算 normal级别的 normalDirectCaches[] 的索引位置
    // Used for bitshifting when calculate the index of normal caches later
    private final int numShiftsNormalDirect;
    private final int numShiftsNormalHeap;
    private final int freeSweepAllocationThreshold;
    private final AtomicBoolean freed = new AtomicBoolean();

    // todo
    private int allocations;





    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;
    /**
     * 构造器
     * 调用方 io.netty.buffer.PooledByteBufAllocator.PoolThreadLocalCache#initialValue()
     */
    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                    int maxCachedBufferCapacity, int freeSweepAllocationThreshold) {

        //System.out.println(Thread.currentThread().getName() + " PoolThreadCache 构造器执行 " + this);

        checkPositiveOrZero(maxCachedBufferCapacity, "maxCachedBufferCapacity");

        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        this.directArena = directArena;

        if (directArena != null) {
            // len = 32   tinyCacheSize=512     里面的队列长度512
            tinySubPageDirectCaches = createSubPageCaches(tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);

            // len = 4    smallCacheSize=256    里面的队列长度256
            smallSubPageDirectCaches = createSubPageCaches(smallCacheSize, directArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalDirect = log2(directArena.pageSize);

            // len = 3     normalCacheSize=64   里面的队列长度64
            normalDirectCaches = createNormalCaches(normalCacheSize, maxCachedBufferCapacity, directArena);

            // 这块区域被使用了就加1 这样leastUsedArena() 就会选择下一块区域
            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            tinySubPageDirectCaches = null;
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
            numShiftsNormalDirect = -1;
        }

        if (heapArena != null) {
            // Create the caches for the heap allocations    len = 32
            tinySubPageHeapCaches = createSubPageCaches(tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            // len = 4
            smallSubPageHeapCaches = createSubPageCaches(smallCacheSize, heapArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalHeap = log2(heapArena.pageSize);

            // len = 3
            normalHeapCaches = createNormalCaches(normalCacheSize, maxCachedBufferCapacity, heapArena);

            // 这块区域被使用了就加1 这样leastUsedArena() 就会选择下一块区域
            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            tinySubPageHeapCaches = null;
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
            numShiftsNormalHeap = -1;
        }

        // Only check if there are caches in use.
        if ((tinySubPageDirectCaches != null || smallSubPageDirectCaches != null || normalDirectCaches != null
                || tinySubPageHeapCaches != null || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: " + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    // 构造器中调用    初始化subpage数组
    private static <T> MemoryRegionCache<T>[] createSubPageCaches(int cacheSize, int numCaches, SizeClass sizeClass) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] memoryRegionCaches = new MemoryRegionCache[numCaches];
            for (int i = 0; i < memoryRegionCaches.length; i++) {
                // TODO: maybe use cacheSize / cache.length         内部类1
                SubPageMemoryRegionCache<T> memoryRegionCache = new SubPageMemoryRegionCache<T>(cacheSize, sizeClass);
                memoryRegionCaches[i] = memoryRegionCache;
                //System.out.println(Thread.currentThread().getName() + " createSubPageCaches " + sizeClass + i + " = " + memoryRegionCache);
            }
            return memoryRegionCaches;
        } else {
            return null;
        }
    }

    // 构造器中调用    初始化normal数组
    private static <T> MemoryRegionCache<T>[] createNormalCaches(int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {

        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            int arraySize = Math.max(1, log2(max / area.pageSize) + 1);

            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] memoryRegionCaches = new MemoryRegionCache[arraySize];

            for (int i = 0; i < memoryRegionCaches.length; i++) {
                // 内部类2
                NormalMemoryRegionCache<T> memoryRegionCache = new NormalMemoryRegionCache<T>(cacheSize);
                memoryRegionCaches[i] = memoryRegionCache;
                //System.out.println(Thread.currentThread().getName() + " createNormalCaches " + i + " = " + memoryRegionCache);
            }

            return memoryRegionCaches;
        } else {
            return null;
        }
    }

    private static int log2(int val) {
        int res = 0;
        while (val > 1) {
            val >>= 1;
            res++;
        }
        return res;
    }





    /**
     * Try to allocate a tiny buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * 尝试分配 tiny级别的buffer
     */
    boolean allocateTiny(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        // tiny
        MemoryRegionCache<?> memoryRegionCache = cacheForTiny(area, normCapacity);
        return allocate(memoryRegionCache, buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * 尝试分配 small级别的buffer
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        // small
        MemoryRegionCache<?> memoryRegionCache = cacheForSmall(area, normCapacity);
        return allocate(memoryRegionCache, buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * 尝试分配 small级别的buffer
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        //  area用于判断是heap还是direct
        MemoryRegionCache<?> memoryRegionCache = cacheForNormal(area, normCapacity);
        return allocate(memoryRegionCache, buf, reqCapacity);
    }


    /**
     * 上面3个方法调用
     * @param memoryRegionCache
     * @param buf
     * @param reqCapacity
     * @return
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean allocate(MemoryRegionCache<?> memoryRegionCache, PooledByteBuf buf, int reqCapacity) {
        if (memoryRegionCache == null) {
            // no cache found so just return false here
            return false;
        }

        // 转入内部类MemoryRegionCache 方法   1.从memoryRegionCache中的queue弹出一个entry（包含一个PoolChunk）
        boolean allocated = memoryRegionCache.allocate(buf, reqCapacity);
        logger.info("memoryRegionCache = " + memoryRegionCache + "  分配结果: " + allocated);

        //
        if (++ allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            trim();
        }

        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer, long handle, int normCapacity, SizeClass sizeClass) {
        // area用于判断是heap还是direct
        MemoryRegionCache<?> memoryRegionCache = cache(area, normCapacity, sizeClass);
        if (memoryRegionCache == null) {
            return false;
        }

        // 调用内部类
        return memoryRegionCache.add(chunk, nioBuffer, handle);
    }

    // area用于判断是heap还是direct
    private MemoryRegionCache<?> cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, normCapacity);
        case Small:
            return cacheForSmall(area, normCapacity);
        case Tiny:
            return cacheForTiny(area, normCapacity);
        default:
            throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            free();
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free() {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        if (freed.compareAndSet(false, true)) {
            int numFreed = free(tinySubPageDirectCaches) +
                    free(smallSubPageDirectCaches) +
                    free(normalDirectCaches) +
                    free(tinySubPageHeapCaches) +
                    free(smallSubPageHeapCaches) +
                    free(normalHeapCaches);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    private static int free(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return 0;
        }
        return cache.free();
    }

    void trim() {
        trim(tinySubPageDirectCaches);
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(tinySubPageHeapCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    /**
     * todo 找一个合适大小的 tiny级别 MemoryRegionCache
     */
    private MemoryRegionCache<?> cacheForTiny(PoolArena<?> area, int normCapacity) {
        // 除以16
        int idx = PoolArena.tinyIdx(normCapacity);

        if (area.isDirect()) {
            // = tinySubPageDirectCaches[idx]
            return cache(tinySubPageDirectCaches, idx);
        }
        return cache(tinySubPageHeapCaches, idx);
    }

    /**
     * todo 找一个合适大小的 small级别 MemoryRegionCache
     */
    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int normCapacity) {
        //　除以1024
        int idx = PoolArena.smallIdx(normCapacity);

        if (area.isDirect()) {
            // cache[idx];
            return cache(smallSubPageDirectCaches, idx);
        }
        // cache[idx];
        return cache(smallSubPageHeapCaches, idx);
    }

    /**
     * todo 找一个合适大小的 normal级别 MemoryRegionCache
     */
    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int normCapacity) {
        if (area.isDirect()) {
            int idx = log2(normCapacity >> numShiftsNormalDirect);
            // cache[idx];
            return cache(normalDirectCaches, idx);
        }

        //
        int idx = log2(normCapacity >> numShiftsNormalHeap);
        // cache[idx];
        return cache(normalHeapCaches, idx);
    }

    // 重要的是 idx的算法
    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int idx) {
        if (cache == null || idx > cache.length - 1) {
            return null;
        }
        return cache[idx];
    }




    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     *
     * 内部类1
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {

        // 构造器
        SubPageMemoryRegionCache(int size, SizeClass sizeClass) {
            super(size, sizeClass);
        }

        @Override
        protected void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     *
     * 内部类2
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {

        // 构造器
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * 抽象内部类       上面 2 个内部类：
     *                @link {io.netty.buffer.PoolThreadCache.SubPageMemoryRegionCache}
     *                @link {io.netty.buffer.PoolThreadCache.NormalMemoryRegionCache}
     * @param <T>
     */
    private abstract static class MemoryRegionCache<T> {

        private final int size;
        // 下面一点 Entry内部类， 包含 一个Handle  一个PoolChunk
        private final Queue<Entry<T>> queue;

        /**
         * SizeClass  enum : Tiny,      Small,               Normal
         *               0 - 512B    ,    512B - 8K    ,    8K - 16M
         */
        private final SizeClass sizeClass;
        private int allocations;


        // 构造器
        MemoryRegionCache(int size, SizeClass sizeClass) {
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            // 啥队列  MpscArrayQueue
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            this.sizeClass = sizeClass;
        }


        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         *   上面 2 个内部类实现
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity);


        /**
         * Add to cache if not already full.
         * 外部类调用
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle) {
            //
            Entry<T> entry = newEntry(chunk, nioBuffer, handle);
            boolean b = queue.offer(entry);
            if (!b) {
                // If it was not possible to cache the chunk, immediately recycle the entry
                entry.recycle();
            }
            return b;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         *
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity) {
            // 弹出一个Entry
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return false;
            }

            // 抽象方法子类实现
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity);

            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            ++ allocations;
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         */
        public final int free() {
            return free(Integer.MAX_VALUE);
        }

        private int free(int max) {
            int numFreed = 0;
            for (; numFreed < max; numFreed++) {
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    freeEntry(entry);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         */
        public final void trim() {
            int free = size - allocations;
            allocations = 0;

            // We not even allocated all the number that are
            if (free > 0) {
                free(free);
            }
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry) {
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;

            // recycle now so PoolChunk can be GC'ed.
            entry.recycle();

            chunk.arena.freeChunk(chunk, handle, sizeClass, nioBuffer);
        }

        // 获取一个entry
        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle) {
            Entry entry = RECYCLER.get();

            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            return entry;
        }

        // entry对象池
        @SuppressWarnings("rawtypes")
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };

    } // MemoryRegionCache end



    /**
     * 内部类
     */
    static final class Entry<T> {

        // 指向一段唯一的连续内存
        final Handle<Entry<?>> recyclerHandle;
        long handle = -1;
        PoolChunk<T> chunk;
        //
        ByteBuffer nioBuffer;

        // 构造器
        Entry(Handle<Entry<?>> recyclerHandle) {
            this.recyclerHandle = recyclerHandle;
        }

        // 对象池满了,释放chunk的引用,使其chunk被GC
        void recycle() {
            chunk = null;
            nioBuffer = null;
            handle = -1;
            recyclerHandle.recycle(this);
        }

    } // Entry end



}
