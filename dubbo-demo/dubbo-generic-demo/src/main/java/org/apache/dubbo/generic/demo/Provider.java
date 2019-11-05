package org.apache.dubbo.generic.demo;

import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * @Author: mianba
 * @Date: 2019/10/30 11:41
 * @Description:
 */
public class Provider {

    /**
     * In order to make sure multicast registry works, need to specify '-Djava.net.preferIPv4Stack=true' before
     * launch the application
     */
    public static void main(String[] args) throws Exception {
        ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("spring/provider.xml");
        context.start();
        System.in.read();
    }
}
