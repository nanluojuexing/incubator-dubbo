package io;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * io 服务端
 */
public class Server {

    private static ServerSocket serverSocket;

    private static int DEFAULT_PORT = 12345;

    private static ExecutorService executorService = Executors.newFixedThreadPool(60);

    public static void main(String[] args) {

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Server.start();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public static void start() throws IOException{
        start(DEFAULT_PORT);
    }

    public synchronized static void start(int port) throws IOException{
        if(serverSocket != null){
            return;
        }

        try (ServerSocket serverSocket = new ServerSocket(port)) {

            System.out.println("服务已启动，端口为：" + port);
            while (true) {
                Socket socket = serverSocket.accept();
                executorService.submit(new ServerHandler(socket));
            }
        }
    }
}
