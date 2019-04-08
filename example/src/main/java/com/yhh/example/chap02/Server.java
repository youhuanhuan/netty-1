package com.yhh.example.chap02;

import com.yhh.example.chap06.AuthHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AttributeKey;
//import org.junit.Test;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author
 */
public final class Server {

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = null;
        EventLoopGroup workerGroup = null;
        try {
            // 每个NioEventLoopGroup 会包含一个 ThreadPerTaskExecutor
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            ServerHandler serverHandler = new ServerHandler();

            ChannelInitializer<SocketChannel> childHandler = new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) {
                    // 给每个新链接进来的SocketChannel 绑定一个Handler
                    AuthHandler authHandler = new AuthHandler();
                    System.out.println(Thread.currentThread().getName() + " new childHandler AuthHandler: " + authHandler);
                    ch.pipeline().addLast(authHandler);
                    //..
                }
            };

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .attr(AttributeKey.newInstance("myServerAttr"), "myServervalue")
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childAttr(AttributeKey.newInstance("childAttr"), "childAttrValue")
                    .handler(serverHandler) //配置服务端pipeline
                    .childHandler(childHandler);

            ChannelFuture f = b.bind(8888).sync();

            f.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }

       // test();
    }

    private static AtomicBoolean wakenUp = new AtomicBoolean();
    public static void test() throws IOException {

        //getAndSet ：以原子方式设置为给定值，并返回以前的值。
        boolean andSet = wakenUp.getAndSet(true);
        System.out.println(andSet);
        System.out.println(wakenUp);

        //如果当前值 == 预期值，则以原子方式将该值设置为给定的更新值。
        //这里需要注意的是这个方法的返回值实际上是是否成功修改，而与之前的值无关。
        boolean b = wakenUp.compareAndSet(false, false);
        System.out.println(b);
        System.out.println(wakenUp);

        Selector selector = Selector.open();
        Set<SelectionKey> selectionKeys = selector.selectedKeys();

    }

//    @Test
    public void test1() throws IOException {
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        System.out.println(threadGroup);

        SecurityManager securityManager = System.getSecurityManager();
        System.out.println(securityManager);

        ThreadGroup threadGroup1 = System.getSecurityManager().getThreadGroup();
        System.out.println(threadGroup1);

        Selector selector = Selector.open();
    }
}