package nio;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;

/**
 * netty服务端
 */
public class NettyServer {

    private static int port= 8080;

    public static void main(String[] args) throws Exception {
        new NettyServer().start();
    }

    public void start() throws Exception{
        final NettyServerHandler serverHandler = new NettyServerHandler();
        EventLoopGroup boss = new NioEventLoopGroup();
        EventLoopGroup worker = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(boss,worker).channel(NioServerSocketChannel.class).localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline().addLast(serverHandler);
                        }
                    });

            ChannelFuture future = bootstrap.bind().sync();
            future.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully().sync();
            worker.shutdownGracefully().sync();
        }
    }
}
