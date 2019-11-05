package org.apache.dubbo.generic.demo;

/**
 * @Author: mianba
 * @Date: 2019/10/30 11:41
 * @Description:
 */
import org.apache.dubbo.rpc.service.GenericService;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Consumer {
    /**
     * In order to make sure multicast registry works, need to specify '-Djava.net.preferIPv4Stack=true' before
     * launch the application
     */
    public static void main(String[] args) throws Throwable {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-consumer.xml");
        // 本地调用
//        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/dubbo-injvm.xml");
        context.start();
        GenericService genericService = (GenericService) context.getBean("demoService");
        while(true){
            try {
                Thread.sleep(5000);
                Object hello = genericService.$invoke("sayHello",new String[]{"java.lang.String"},new Object[]{"123"});
                System.err.println("result: " + hello);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
