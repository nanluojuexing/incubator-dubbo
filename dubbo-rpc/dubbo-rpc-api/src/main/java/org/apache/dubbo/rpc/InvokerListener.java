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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.extension.SPI;

/**
 * InvokerListener. (SPI, Singleton, ThreadSafe)
 *
 * 是 dubbo 通过 Protocol 进行服务引用的时候调用的扩展点，也就是 Protocol 在进行 refer() 方法的时候对服务暴露的一个扩展点。最终是在 ProtocolListenerWrapper#export 方法中通过 dubbo 的 SPI 机制加载进去，然后包装成一个 ListenerInvokerWrapper 对象
 */
@SPI
public interface InvokerListener {

    /**
     * The invoker referred
     *
     * @param invoker
     * @throws RpcException
     * @see org.apache.dubbo.rpc.Protocol#refer(Class, org.apache.dubbo.common.URL)
     */
    void referred(Invoker<?> invoker) throws RpcException;

    /**
     * The invoker destroyed.
     *
     * @param invoker
     * @see org.apache.dubbo.rpc.Invoker#destroy()
     */
    void destroyed(Invoker<?> invoker);

}