package org.apache.dubbo.demo;

/**
 * @Author: mianba
 * @Date: 2019/10/29 11:44
 * @Description:
 */
public class Cat {

    /**
     * 名字
     */
    private String name;

    public String getName() {
        return name;
    }

    public Cat setName(String name) {
        this.name = name;
        return this;
    }
}
