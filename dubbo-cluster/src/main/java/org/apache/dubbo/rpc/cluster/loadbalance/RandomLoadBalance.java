/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * random load balance. 随机算法（默认的）
 *
 * 调用链
 * AbstractClusterInvoker.invoke(Invocation)
 * --> AbstractClusterInvoker.doInvoke(invocation, invokers, loadbalance)
 * --> FailoverClusterInvoker.doInvoke(Invocation, final List<Invoker<T>>, LoadBalance) （说明，dubbo默认是failover集群实现，且默认最多重试2次，即默认最多for循环调用3次）
 * --> AbstractClusterInvoker.select()
 * --> AbstractClusterInvoker.doSelect()
 * --> AbstractLoadBalance.select()
 * --> RoundRobinLoadBalance.doSelect()
 */
public class RandomLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "random";


    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers 获得总数
        int length = invokers.size();
        // Every invoker has the same weight?
        // 各个Invoke权重是否都一样
        boolean sameWeight = true;
        // the weight of every invokers
        // 获得每个invoker的权重
        int[] weights = new int[length];
        // the first invoker's weight
        // 获取第一个invoker的权重
        int firstWeight = getWeight(invokers.get(0), invocation);
        weights[0] = firstWeight;
        // The sum of weights
        // 然后将第一个invoker的权重赋值给 总的权重
        int totalWeight = firstWeight;
        // 下面这个循环有两个作用，第一是计算总权重 totalWeight，
        // 第二是检测每个服务提供者的权重是否相同
        for (int i = 1; i < length; i++) {
            int weight = getWeight(invokers.get(i), invocation);
            // save for later use 保存每个权重
            weights[i] = weight;
            // Sum 累加权重
            totalWeight += weight;
            // 不相同的话，置为 sameWeight = false
            if (sameWeight && weight != firstWeight) {
                sameWeight = false;
            }
        }
        // 获取随机数，并计算落在那个区间
        if (totalWeight > 0 && !sameWeight) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            // 获取区间内的一个随机数 [0,totalWeight] 区间内的数字
            int offset = ThreadLocalRandom.current().nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 循环让 offset 数减去服务提供者权重值，当 offset 小于0时，返回相应的 Invoker。
            // 举例说明一下，我们有 servers = [A, B, C]，weights = [5, 3, 2]，offset = 7。
            // 第一次循环，offset - 5 = 2 > 0，即 offset > 5，
            // 表明其不会落在服务器 A 对应的区间上。
            // 第二次循环，offset - 3 = -1 < 0，即 5 < offset < 8，
            // 表明其会落在服务器 B 对应的区间上
            for (int i = 0; i < length; i++) {
                offset -= weights[i];
                if (offset < 0) {
                    return invokers.get(i);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果所有服务提供de权重值一致，则随机返回一个
        return invokers.get(ThreadLocalRandom.current().nextInt(length));
    }
    /**
     * 选择示例：
     * 假定有3台dubbo provider:
     * 10.0.0.1:20884, weight=2
     * 10.0.0.1:20886, weight=3
     * 10.0.0.1:20888, weight=4
     * 随机算法的实现：
     * totalWeight=9;
     * 假设offset=1（即random.nextInt(9)=1）
     * 1-2=-1<0？是，所以选中 10.0.0.1:20884, weight=2
     * 假设offset=4（即random.nextInt(9)=4）
     * 4-2=2<0？否，这时候offset=2， 2-3<0？是，所以选中 10.0.0.1:20886, weight=3
     * 假设offset=7（即random.nextInt(9)=7）
     * 7-2=5<0？否，这时候offset=5， 5-3=2<0？否，这时候offset=2， 2-4<0？是，所以选中 10.0.0.1:20888, weight=4
     */

}
