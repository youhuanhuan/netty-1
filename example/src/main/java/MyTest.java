import com.yhh.unsafe.UnSafeUtil;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.nio.Buffer;
import java.nio.ByteBuffer;

public class MyTest {

    public static Unsafe UNSAFE = UnSafeUtil.getUnsafe();
    public static void main(String[] args) throws Exception {

        ByteBuffer direct = ByteBuffer.allocateDirect(1);
        final Constructor<?> constructor = direct.getClass().getDeclaredConstructor(long.class, int.class);
        constructor.setAccessible(true);

        // 一定要用这步
        long address = UNSAFE.allocateMemory(1);
        System.out.println("address = " + address);

        ByteBuffer newInstanceBuffer = (ByteBuffer) constructor.newInstance(address, 10);
        System.out.println("newInstanceBuffer = " + newInstanceBuffer);
        newInstanceBuffer.put((byte) 90);
        System.out.println("newInstanceBuffer = " + newInstanceBuffer);

        newInstanceBuffer.put((byte) 55);
        System.out.println("newInstanceBuffer = " + newInstanceBuffer);

        newInstanceBuffer.put((byte) 66);
        System.out.println("newInstanceBuffer = " + newInstanceBuffer);


        byte aByte = UNSAFE.getByte(address);
        System.out.println("aByte = " + aByte);
        byte aByte2 = UNSAFE.getByte(address + 1);
        System.out.println("aByte2 = " + aByte2);
        byte aByte3 = UNSAFE.getByte(address + 2);
        System.out.println("aByte3 = " + aByte3);


        byte aByte4 = UNSAFE.getByte(address + 3);
        System.out.println("aByte4 = " + aByte4);
    }
}
