/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.dubbo.demo.consumer.comp;

import org.apache.dubbo.config.annotation.Reference;
import org.apache.dubbo.demo.DemoService;

import org.apache.dubbo.demo.ParamCallback;
import org.springframework.stereotype.Component;

@Component("demoServiceComponent")
public class DemoServiceComponent implements DemoService {
    @Reference
    private DemoService demoService;

    @Override
    public String sayHello(String name) throws Throwable {
        return demoService.sayHello(name);
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
