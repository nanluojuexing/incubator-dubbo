package org.apache.dubbo.demo.consumer;


import com.alibaba.dubbo.rpc.service.EchoService;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import org.apache.dubbo.common.bytecode.ClassGenerator;
import org.apache.dubbo.demo.DemoService;
import org.apache.dubbo.demo.ParamCallback;

public class proxy0 implements ClassGenerator.DC, EchoService, DemoService {
    public static Method[] methods;
    private InvocationHandler handler;

    @Override
    public String sayHello(String string) throws Throwable {
        Object[] arrobject = new Object[]{string};
        Object object = this.handler.invoke(this, methods[0], arrobject);
        return (String)object;
    }

    @Override
    public Object $echo(Object object) throws Throwable {
        Object[] arrobject = new Object[]{object};
        Object object2 = this.handler.invoke(this, methods[1], arrobject);
        return object2;
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

    public proxy0() {
    }

    public proxy0(InvocationHandler invocationHandler) {
        this.handler = invocationHandler;
    }
}
