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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.utils.AtomicPositiveInteger;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ResponseFuture;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.RpcResult;
import org.apache.dubbo.rpc.SimpleAsyncRpcResult;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DubboInvoker
 */
public class DubboInvoker<T> extends AbstractInvoker<T> {

    private final ExchangeClient[] clients;

    private final AtomicPositiveInteger index = new AtomicPositiveInteger();

    private final String version;

    private final ReentrantLock destroyLock = new ReentrantLock();

    private final Set<Invoker<?>> invokers;

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients) {
        this(serviceType, url, clients, null);
    }

    public DubboInvoker(Class<T> serviceType, URL url, ExchangeClient[] clients, Set<Invoker<?>> invokers) {
        super(serviceType, url, new String[]{Constants.INTERFACE_KEY, Constants.GROUP_KEY, Constants.TOKEN_KEY, Constants.TIMEOUT_KEY});
        this.clients = clients;
        // get version.
        this.version = url.getParameter(Constants.VERSION_KEY, "0.0.0");
        this.invokers = invokers;
    }

    /**
     * 执行调用
     * @param invocation
     * @return
     * @throws Throwable
     */
    @Override
    protected Result doInvoke(final Invocation invocation) throws Throwable {
        RpcInvocation inv = (RpcInvocation) invocation;
        // 获取方法名称
        final String methodName = RpcUtils.getMethodName(invocation);
        // 设置 path 和 version 到 attachment中
        inv.setAttachment(Constants.PATH_KEY, getUrl().getPath());
        inv.setAttachment(Constants.VERSION_KEY, version);

        ExchangeClient currentClient;
        // 如果只有一个Client，直接选择；如果多个Client，轮询
        if (clients.length == 1) {
            // 从client 数组中获取 ExchangeClient
            currentClient = clients[0];
        } else {
            currentClient = clients[index.getAndIncrement() % clients.length];
        }
        // 远程调用
        try {
            // 获取异步的配置，默认为false
            boolean isAsync = RpcUtils.isAsync(getUrl(), invocation);
            boolean isAsyncFuture = RpcUtils.isReturnTypeFuture(inv);
            // isOneway 为 true ，表示单向通信
            boolean isOneway = RpcUtils.isOneway(getUrl(), invocation);
            // 获取consumer的 timeout,默认1s
            int timeout = getUrl().getMethodParameter(methodName, Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
            // 单向调用
            if (isOneway) {
                boolean isSent = getUrl().getMethodParameter(methodName, Constants.SENT_KEY, false);
                // 发送请求
                currentClient.send(inv, isSent);
                // 设置上下文中的 future字段为 null
                RpcContext.getContext().setFuture(null);
                // 返回空的 RpcResult
                return new RpcResult();
                // 异步又返回值
            } else if (isAsync) {
                // 发送请求，并得到一个 ResponseFuture
                ResponseFuture future = currentClient.request(inv, timeout);
                // For compatibility
                //将future封装
                FutureAdapter<Object> futureAdapter = new FutureAdapter<>(future);
                // 设置 future 到上下文中
                RpcContext.getContext().setFuture(futureAdapter);

                Result result;
                if (isAsyncFuture) {
                    // register resultCallback, sometimes we need the async result being processed by the filter chain.
                    // 注册 返回结果进行回调 futureFilter
                    result = new AsyncRpcResult(futureAdapter, futureAdapter.getResultFuture(), false);
                } else {
                    result = new SimpleAsyncRpcResult(futureAdapter, futureAdapter.getResultFuture(), false);
                }
                return result;
                // 同步调用
            } else {
                RpcContext.getContext().setFuture(null);
                // 同步调用，等待结果 ResponseFuture 具体的结果在DefaultFuture
                return (Result) currentClient.request(inv, timeout).get();
            }
        } catch (TimeoutException e) {
            throw new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        } catch (RemotingException e) {
            throw new RpcException(RpcException.NETWORK_EXCEPTION, "Failed to invoke remote method: " + invocation.getMethodName() + ", provider: " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        if (!super.isAvailable()) {
            return false;
        }
        for (ExchangeClient client : clients) {
            if (client.isConnected() && !client.hasAttribute(Constants.CHANNEL_ATTRIBUTE_READONLY_KEY)) {
                //cannot write == not Available ?
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        // in order to avoid closing a client multiple times, a counter is used in case of connection per jvm, every
        // time when client.close() is called, counter counts down once, and when counter reaches zero, client will be
        // closed.
        // 忽略，若已经销毁
        if (super.isDestroyed()) {
            return;
        } else {
            // double check to avoid dup close
            // 双重锁校验，避免已经关闭
            destroyLock.lock();
            try {
                if (super.isDestroyed()) {
                    return;
                }
                // 标记关闭
                super.destroy();
                // 移除出 `invokers`
                if (invokers != null) {
                    invokers.remove(this);
                }
                // 关闭 ExchangeClient 们
                for (ExchangeClient client : clients) {
                    try {
                        client.close(ConfigurationUtils.getServerShutdownTimeout());
                    } catch (Throwable t) {
                        logger.warn(t.getMessage(), t);
                    }
                }

            } finally {
                destroyLock.unlock();
            }
        }
    }
}
