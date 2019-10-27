package nio;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.InetSocketAddress;

public class NettyClient {

    private static int port= 8080;

    private static String local_ip ="127.0.0.1";



    public static void main(String[] args) throws Exception {
        new NettyClient().start();
    }

    public void start() throws Exception{
        EventLoopGroup group = new NioEventLoopGroup();

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group).channel(NioSocketChannel.class).remoteAddress(new InetSocketAddress(local_ip,port))
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline().addLast(new NettyClientHandler());
                        }
                    });

            ChannelFuture sync = bootstrap.connect().sync();
            sync.channel().closeFuture().sync();
        } finally {
            group.shutdownGracefully().sync();
        }
    }
}
