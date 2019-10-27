package netty;

import io.Server;

import java.io.IOException;

public class EchoServer {

    private static int DEFAULT_PORT = 8081;
    private static EchoServerHandler serverHandler;

    public static void start() {
        start(DEFAULT_PORT);
    }

    public static synchronized void start(int port) {

        if (serverHandler != null) {
            serverHandler.stop();
        }

        serverHandler = new EchoServerHandler(port);
        new Thread(serverHandler, "Server").start();
    }

    public static void main(String[] args) throws IOException {
        // 运行服务器
        Server.start();
    }
}
