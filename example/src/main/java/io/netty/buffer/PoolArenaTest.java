package io.netty.buffer;

import java.io.*;
import java.util.Arrays;

public class PoolArenaTest {


    public static void main(String[] args) throws Exception {
//        ByteBufAllocator alloc = PooledByteBufAllocator.DEFAULT;
//        // PooledUnsafeHeapByteBuf(ridx: 0, widx: 0, cap: 254)
//        ByteBuf byteBuf = alloc.heapBuffer(254);
//        byteBuf.writeInt(126);
//        System.out.println(byteBuf.readInt());

        PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
//        int pageSize = 8192;
//        int maxOrder = 11;
//        int pageShifts = 8192;
//        int chunkSize = 16777216;
//        int directMemoryCacheAlignment = 0;
//        PoolArena.HeapArena heapArena = new PoolArena.HeapArena(allocator, pageSize, maxOrder, pageShifts, chunkSize, directMemoryCacheAlignment);

        PoolArena<byte[]> heapArena1 = allocator.heapArenas[0];
        PoolThreadCache cache = allocator.threadCache.get();

//        PoolArena directArena = null;
//        int tinyCacheSize = 512;
//        int smallCacheSize = 256;
//        int normalCacheSize = 64;
//        int DEFAULT_MAX_CACHED_BUFFER_CAPACITY = 32768;
//        int DEFAULT_CACHE_TRIM_INTERVAL = 8192;

//        PoolThreadCache cache = new PoolThreadCache(heapArena1, directArena,
//                tinyCacheSize, smallCacheSize, normalCacheSize,
//                DEFAULT_MAX_CACHED_BUFFER_CAPACITY, DEFAULT_CACHE_TRIM_INTERVAL);

        // PooledUnsafeHeapByteBuf
        ByteBuf buf = heapArena1.allocate(cache, 8192, Integer.MAX_VALUE);
        buf.writeInt(111);
        int a = buf.readInt();
        System.out.println("a = " + a);
        System.out.println(((PooledByteBuf) buf).handle);
        System.out.println(((PooledByteBuf) buf).offset);
        System.out.println(((PooledByteBuf) buf).length);
        //buf.release();

        ByteBuf buf2 = heapArena1.allocate(cache, 8192+1, Integer.MAX_VALUE);
        buf2.writeInt(222);
        int a2 = buf2.readInt();
        System.out.println("a2 = " + a2);
        System.out.println(((PooledByteBuf) buf2).handle);
        System.out.println(((PooledByteBuf) buf2).offset);
        System.out.println(((PooledByteBuf) buf2).length);

        ByteBuf buf3 = heapArena1.allocate(cache, 8192+2, Integer.MAX_VALUE);
        buf3.writeInt(333);
        int a3 = buf3.readInt();
        System.out.println("a3 = " + a3);
        System.out.println(((PooledByteBuf) buf3).handle);
        System.out.println(((PooledByteBuf) buf3).offset);
        System.out.println(((PooledByteBuf) buf3).length);





//        byte[] memory = (byte[]) ((PooledByteBuf) buf2).memory;
//        OutputStream out = new FileOutputStream("D:\\log/ccc.txt");
//
//        String s = Arrays.toString(memory);
//        InputStream is = new ByteArrayInputStream(s.getBytes());
//        byte[] buff = new byte[1024*10];
//        int len = 0;
//        int index = 1;
//        while((len=is.read(buff))!=-1){
//            System.out.println((index++) + " " + Arrays.toString(buff));
//            out.write(buff, 0, len);
//        }
//        is.close();
//        out.close();

        //cache.allocateTiny(heapArena1, buf, 11, 16);
    }

}
