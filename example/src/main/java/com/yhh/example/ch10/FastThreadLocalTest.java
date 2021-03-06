package com.yhh.example.ch10;

import io.netty.util.concurrent.FastThreadLocal;

public class FastThreadLocalTest {

    private static FastThreadLocal<Object> threadLocal0 = new FastThreadLocal<Object>() {
        @Override
        protected Object initialValue() {
            Object obj = new Object();
            System.out.println(Thread.currentThread().getName() + " initialValue = " + obj);
            return obj;
        }

        @Override
        protected void onRemoval(Object value) throws Exception {
            System.out.println(Thread.currentThread().getName() + " onRemoval value= " + value);
            System.out.println("onRemoval");
        }
    };

    private static FastThreadLocal<Object> threadLocal1 = new FastThreadLocal<Object>() {
        @Override
        protected Object initialValue() {
            Object obj = new Object();
            System.out.println(Thread.currentThread().getName() + " threadLocal1 initialValue = " + obj);
            return obj;
        }
    };


    public static void main(String[] args) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Object object = threadLocal0.get();
                // .... do with object
                System.out.println(Thread.currentThread().getName() + object);
                threadLocal0.set(new Object());

//            while (true) {
//                threadLocal0.set(new Object());
//                try {
//                    Thread.sleep(1);
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
//            }
            }
        }, "test-thread-0").start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                Object object = threadLocal0.get();
                // ... do with object
                System.out.println(Thread.currentThread().getName() + object);

                while (true) {
                    System.out.println(threadLocal0.get() == object);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, "test-thread-1").start();
    }



}
