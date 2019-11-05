package org.apache.dubbo.generic.demo;

import org.apache.dubbo.demo.DemoService;
import org.apache.dubbo.demo.ParamCallback;
import org.apache.dubbo.rpc.RpcContext;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DemoServiceImpl implements DemoService {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(DemoServiceImpl.class);

    @Override
    public String sayHello(String name) {
        logger.info("Hello " + name + ", request from consumer: " + RpcContext.getContext().getRemoteAddress());
        return "Hello " + name + ", response from provider: " + RpcContext.getContext().getLocalAddress();
    }

    @Override
    public void bye(Object o) {
    }

    @Override
    public void callbackParam(String msg, ParamCallback callback) {

    }


    @Override
    public String say01(String msg) {
        return null;
    }

    @Override
    public String[] say02() {
        return new String[0];
    }

    @Override
    public void say03() {

    }

    @Override
    public Void say04() {
        return null;
    }

}
