package org.apache.dubbo.demo.consumer;

import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.rpc.service.GenericService;

import java.util.HashMap;
import java.util.Map;

/**
 * @Author: mianba
 * @Date: 2019/10/14 11:59
 * @Description:
 */
public class TestGeneric {

    public static void main(String[] args) {
        // 引⽤远程服务, 该实例⾥⾯封装了所有与注册中⼼及服务提供⽅连接，请缓存
        ReferenceConfig<GenericService> reference = new ReferenceConfig<GenericService>();
        // 弱类型接⼝名
        reference.setInterface("com.alibaba.dubbo.demo.DemoService");
        reference.setVersion("1.0.0");
        // 声明为泛化接⼝
        reference.setGeneric(true);
        // ⽤com.alibaba.dubbo.rpc.service.GenericService可以替代所有接口引用⽤
        GenericService genericService = reference.get();
        // 基本类型以及Date,List,Map等不需要转换，直接调⽤
        Object result = genericService.$invoke("sayYes", new String[] {"java.lang.String"}, new Object[] {"afei"});
        System.out.println("result --> "+result);

        // ⽤Map表示POJO参数，如果返回值为POJO也将自动转成Map
        Map<String, Object> teacher = new HashMap<String, Object>();
        teacher.put("id", "1");
        teacher.put("name", "admin");
        teacher.put("age", "18");
        teacher.put("level", "3");
        teacher.put("remark", "测试");
        // 如果返回POJO将自动转成Map
        result = genericService.$invoke("justTest", new String[]
                {"com.alibaba.dubbo.demo.bean.HighTeacher"}, new Object[]{teacher});
        System.out.println("result --> "+result);
    }
}
