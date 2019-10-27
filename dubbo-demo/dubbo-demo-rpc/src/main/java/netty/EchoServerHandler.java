package netty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Date;
import java.util.Iterator;
import java.util.Set;

public class EchoServerHandler implements Runnable {

    private Selector selector;
    private ServerSocketChannel serverChannel ;

    private boolean stop;

    /**
     * 初始化多路复用器，绑定监听端口
     *
     * @param port
     */
    public EchoServerHandler(int port) {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().bind(new InetSocketAddress(port), 1024);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("服务器监听" + port);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public void stop(){
        this.stop = true;
    }

    @Override
    public void run() {

        try {
            while (!stop){
                selector.select(1000);
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectionKeys.iterator();
                SelectionKey selectionKey = null;
                while (iterator.hasNext()){
                    selectionKey = iterator.next();
                    iterator.remove();
                    try {
                        handleInput(selectionKey);
                    } catch (IOException e) {
                        if (selectionKey != null) {
                            selectionKey.cancel();
                            if (selectionKey.channel() != null) {
                                selectionKey.channel().close();
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (selector != null) {
            try {
                selector.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void handleInput(SelectionKey key) throws IOException {
        if (key.isValid()) {
            if (key.isAcceptable()) {
                ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                SocketChannel sc = ssc.accept();
                sc.configureBlocking(false);
                sc.register(selector, SelectionKey.OP_READ);
            }
            if (key.isReadable()) {
                SocketChannel sc = (SocketChannel) key.channel();
                ByteBuffer readBuff = ByteBuffer.allocate(1024);
                //非阻塞的
                int read = sc.read(readBuff);
                if (read > 0) {
                    readBuff.flip();
                    byte[] bytes = new byte[readBuff.remaining()];
                    readBuff.get(bytes);
                    String body = new String(bytes, "utf-8");
                    System.out.println("服务收到消息：" + body);
                    String currentTime = new Date(System.currentTimeMillis()).toString();
                    doWrite(sc, currentTime);
                } else if (read < 0) {
                    key.cancel();
                    sc.close();
                } else {
                }
            }
        }
    }

    /**
     * 异步发送应答消息
     * @param sc
     * @param content
     * @throws IOException
     */
    private void doWrite(SocketChannel sc, String content) throws IOException {
        if (content != null && content.trim().length() > 0) {
            byte[] bytes = content.getBytes();
            ByteBuffer byteBuffer = ByteBuffer.allocate(bytes.length);
            byteBuffer.put(bytes);
            byteBuffer.flip();
            sc.write(byteBuffer);
        }
    }
}
