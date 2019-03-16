package com.yhh.pipeline;

public class TestHandler2 implements Handler {


    @Override
    public void channelRead(HandlerContext ctx, Object msg) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        String result = (String) msg + "-handler2";
        System.out.println(result);
        ctx.write(result);
    }
}
