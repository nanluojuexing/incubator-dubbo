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
package org.apache.dubbo.registry.integration;

import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.configcenter.DynamicConfiguration;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Cluster;
import org.apache.dubbo.rpc.cluster.Configurator;
import org.apache.dubbo.rpc.cluster.Router;
import org.apache.dubbo.rpc.cluster.RouterChain;
import org.apache.dubbo.rpc.cluster.RouterFactory;
import org.apache.dubbo.rpc.cluster.directory.AbstractDirectory;
import org.apache.dubbo.rpc.cluster.directory.StaticDirectory;
import org.apache.dubbo.rpc.cluster.support.ClusterUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.protocol.InvokerWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.Constants.APP_DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.CATEGORY_KEY;
import static org.apache.dubbo.common.Constants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.Constants.DYNAMIC_CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.Constants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.Constants.ROUTERS_CATEGORY;
import static org.apache.dubbo.common.Constants.ROUTE_PROTOCOL;
import static org.apache.dubbo.common.utils.UrlUtils.classifyUrls;


/**
 * RegistryDirectory 动态服务目录，注册中心的服务配置发生变化后，可收到服务的相关变化
 *  1.invoker的列举
 *  2. 接受服务配置变更
 *  3. invoker列表的刷新
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class)
            .getAdaptiveExtension();

    private final String serviceKey; // Initialization at construction time, assertion not null
    private final Class<T> serviceType; // Initialization at construction time, assertion not null
    private final Map<String, String> queryMap; // Initialization at construction time, assertion not null
    private final URL directoryUrl; // Initialization at construction time, assertion not null, and always assign non null value
    private final boolean multiGroup;
    private Protocol protocol; // Initialization at the time of injection, the assertion is not null
    private Registry registry; // Initialization at the time of injection, the assertion is not null
    private volatile boolean forbidden = false;

    private volatile URL overrideDirectoryUrl; // Initialization at construction time, assertion not null, and always assign non null value

    /**
     * override rules
     * Priority: override>-D>consumer>provider
     * Rule one: for a certain provider <ip:port,timeout=100>
     * Rule two: for all providers <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    // Map<url, Invoker> cache service url to invoker mapping.
    private volatile Map<String, Invoker<T>> urlInvokerMap; // The initial value is null and the midway may be assigned to null, please use the local variable reference
    private volatile List<Invoker<T>> invokers;

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // The initial value is null and the midway may be assigned to null, please use the local variable reference

    private static final ConsumerConfigurationListener consumerConfigurationListener = new ConsumerConfigurationListener();
    private ReferenceConfigurationListener serviceConfigurationListener;


    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null) {
            throw new IllegalArgumentException("service type is null.");
        }
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0) {
            throw new IllegalArgumentException("registry serviceKey is null.");
        }
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        this.overrideDirectoryUrl = this.directoryUrl = turnRegistryUrlToConsumerUrl(url);
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        this.multiGroup = group != null && ("*".equals(group) || group.contains(","));
    }

    private URL turnRegistryUrlToConsumerUrl(URL url) {
        // save any parameter in registry that will be useful to the new url.
        String isDefault = url.getParameter(Constants.DEFAULT_KEY);
        if (StringUtils.isNotEmpty(isDefault)) {
            queryMap.put(Constants.REGISTRY_KEY + "." + Constants.DEFAULT_KEY, isDefault);
        }
        return url.setPath(url.getServiceInterface())
                .clearParameters()
                .addParameters(queryMap)
                .removeParameter(Constants.MONITOR_KEY);
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        // 设置消费订阅的url
        setConsumerUrl(url);
        consumerConfigurationListener.addNotifyListener(this);
        serviceConfigurationListener = new ReferenceConfigurationListener(this, url);
        // 向注册中心，发起订阅
        registry.subscribe(url, this);
    }


    @Override
    public void destroy() {
        if (isDestroyed()) {
            return;
        }
        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
            DynamicConfiguration.getDynamicConfiguration()
                    .removeListener(ApplicationModel.getApplication(), consumerConfigurationListener);
        } catch (Throwable t) {
            logger.warn("unexpected error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // must be executed after unsubscribing
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     * 通知监听器，在注册中心发生变化的时候，会通知
     * 根据url的category参数对url进行分别处理,toConfigurators,toRouters 将url转为 configurators 和 routers 列表
     * @param urls The list of registered information , is always not empty. The meaning is the same as the return value of {@link org.apache.dubbo.registry.RegistryService#lookup(URL)}.
     */
    @Override
    public synchronized void notify(List<URL> urls) {
        List<URL> categoryUrls = urls.stream()
                .filter(this::isValidCategory)
                .filter(this::isNotCompatibleFor26x)
                .collect(Collectors.toList());

        /**
         * TODO Try to refactor the processing of these three type of urls using Collectors.groupBy()?
         */
        this.configurators = Configurator.toConfigurators(classifyUrls(categoryUrls, UrlUtils::isConfigurator))
                .orElse(configurators);

        toRouters(classifyUrls(categoryUrls, UrlUtils::isRoute)).ifPresent(this::addRouters);

        // providers
        refreshOverrideAndInvoker(classifyUrls(categoryUrls, UrlUtils::isProvider));
    }

    private void refreshOverrideAndInvoker(List<URL> urls) {
        // mock zookeeper://xxx?mock=return null
        overrideDirectoryUrl();
        refreshInvoker(urls);
    }

    /**
     * Convert the invokerURL list to the Invoker Map. The rules of the conversion are as follows:
     * <ol>
     * <li> If URL has been converted to invoker, it is no longer re-referenced and obtained directly from the cache,
     * and notice that any parameter changes in the URL will be re-referenced.</li>
     * <li>If the incoming invoker list is not empty, it means that it is the latest invoker list.</li>
     * <li>If the list of incoming invokerUrl is empty, It means that the rule is only a override rule or a route
     * rule, which needs to be re-contrasted to decide whether to re-reference.</li>
     * </ol>
     *
     * refreshInvoker 方法首先会根据入参 invokerUrls 的数量和协议头判断是否禁用所有的服务，如果禁用，则将 forbidden 设为 true，并销毁所有的 Invoker。
     * 若不禁用，则将 url 转成 Invoker，得到 <url, Invoker> 的映射关系。然后进一步进行转换，得到 <methodName, Invoker 列表> 映射关系。之后进行多组 Invoker 合并操作，并将合并结果赋值给 methodInvokerMap。
     * methodInvokerMap 变量在 doList 方法中会被用到，doList 会对该变量进行读操作，在这里是写操作。当新的 Invoker 列表生成后，还要一个重要的工作要做，就是销毁无用的 Invoker，避免服务消费者调用已下线的服务的服务
     *
     * @param invokerUrls this parameter can't be null
     */
    // TODO: 2017/8/31 FIXME The thread pool should be used to refresh the address, otherwise the task may be accumulated.
    private void refreshInvoker(List<URL> invokerUrls) {
        Assert.notNull(invokerUrls, "invokerUrls should not be null");
        // invokerUrls 仅有一个元素，且 url 协议头为 empty，此时表示禁用所有服务
        if (invokerUrls.size() == 1 && invokerUrls.get(0) != null && Constants.EMPTY_PROTOCOL.equals(invokerUrls
                .get(0)
                .getProtocol())) {
            // 设置 forbidden 为 true
            this.forbidden = true; // Forbid to access
            this.invokers = Collections.emptyList();
            routerChain.setInvokers(this.invokers);
            // 销毁所有的invoker
            destroyAllInvokers(); // Close all invokers
        } else {
            this.forbidden = false; // Allow to access
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            if (invokerUrls == Collections.<URL>emptyList()) {
                invokerUrls = new ArrayList<>();
            }
            if (invokerUrls.isEmpty() && this.cachedInvokerUrls != null) {
                // 添加缓存 url 到 invokerUrls 中
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                this.cachedInvokerUrls = new HashSet<>();
                this.cachedInvokerUrls.addAll(invokerUrls);//Cached invoker urls, convenient for comparison
            }
            if (invokerUrls.isEmpty()) {
                return;
            }
            // 将 url 转成 Invoker
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// Translate url list to Invoker map
            // state change
            // If the calculation is wrong, it is not processed.
            if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls
                        .toString()));
                return;
            }
            List<Invoker<T>> newInvokers = Collections.unmodifiableList(new ArrayList<>(newUrlInvokerMap.values()));
            // pre-route and build cache, notice that route cache should build on original Invoker list.
            // toMergeMethodInvokerMap() will wrap some invokers having different groups, those wrapped invokers not should be routed.
            routerChain.setInvokers(newInvokers);
            this.invokers = multiGroup ? toMergeInvokerList(newInvokers) : newInvokers;
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                // 销毁无用doinvokers
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // Close the unused Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    private List<Invoker<T>> toMergeInvokerList(List<Invoker<T>> invokers) {
        List<Invoker<T>> mergedInvokers = new ArrayList<>();
        // group -> Invoker 列表
        Map<String, List<Invoker<T>>> groupMap = new HashMap<String, List<Invoker<T>>>();
        for (Invoker<T> invoker : invokers) {
            String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");
            groupMap.computeIfAbsent(group, k -> new ArrayList<>());
            // 缓存 <group, List<Invoker>> 到 groupMap 中
            groupMap.get(group).add(invoker);
        }
        // 如果 groupMap 中仅包含一组键值对，此时直接取出该键值对的值即可
        if (groupMap.size() == 1) {
            mergedInvokers.addAll(groupMap.values().iterator().next());
            // groupMap.size() > 1 成立，表示 groupMap 中包含多组键值对，比如：
            // {
            //     "dubbo": [invoker1, invoker2, invoker3, ...],
            //     "hello": [invoker4, invoker5, invoker6, ...]
            // }
        } else if (groupMap.size() > 1) {
            for (List<Invoker<T>> groupList : groupMap.values()) {
                StaticDirectory<T> staticDirectory = new StaticDirectory<>(groupList);
                staticDirectory.buildRouterChain();
                mergedInvokers.add(cluster.join(staticDirectory));
            }
        } else {
            mergedInvokers = invokers;
        }
        return mergedInvokers;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private Optional<List<Router>> toRouters(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }

        List<Router> routers = new ArrayList<>();
        for (URL url : urls) {
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                continue;
            }
            String routerType = url.getParameter(Constants.ROUTER_KEY);
            if (routerType != null && routerType.length() > 0) {
                url = url.setProtocol(routerType);
            }
            try {
                Router router = routerFactory.getRouter(url);
                if (!routers.contains(router)) {
                    routers.add(router);
                }
            } catch (Throwable t) {
                logger.error("convert router url to router error, url: " + url, t);
            }
        }

        return Optional.of(routers);
    }

    /**
     * Turn urls into invokers, and if url has been refer, will not re-reference.
     * toInvokers 方法返回的是 <url, Invoker> 映射关系表
     * @param urls
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<String, Invoker<T>>();
        if (urls == null || urls.isEmpty()) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<String>();
        // 获取服务消费端配置的协议
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);
        // 循环服务提供者 URL 集合，转成 Invoker 集合
        for (URL providerUrl : urls) {
            // If protocol is configured at the reference side, only the matching protocol is selected
            // 如果 reference 端配置了 protocol ，则只选择匹配的 protocol
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;
                String[] acceptProtocols = queryProtocols.split(",");
                // 检测服务提供者协议是否被服务消费者所支持
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    // 若服务消费者协议头不被消费者所支持，则忽略当前 providerUrl
                    continue;
                }
            }
            // 忽略empty协议
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }
            //  // 通过 SPI 检测服务端协议是否被消费端支持，不支持则抛出异常
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() +
                        " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() +
                        " to consumer " + NetUtils.getLocalHost() + ", supported protocol: " +
                        ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }
            // 合并url
            URL url = mergeUrl(providerUrl);
            //忽略已经初始化的
            String key = url.toFullString(); // The parameter urls are sorted
            if (keys.contains(key)) { // Repeated url
                continue;
            }
            keys.add(key);
            // Cache key is url that does not merge with consumer side parameters, regardless of how the consumer combines parameters, if the server url changes, then refer again
            // 将本地 Invoker 缓存赋值给 localUrlInvokerMap
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
            // 获取与 url 对应的 Invoker
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);
            // 缓存未命中
            if (invoker == null) { // Not in the cache, refer again
                try {
                    boolean enabled = true;
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        // 获取 disable 配置，取反，然后赋值给 enable 变量
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        // 获取 enable 配置，并赋值给 enable 变量
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }
                    if (enabled) {
                        // 调用 refer 获取 Invoker
                        invoker = new InvokerDelegate<T>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }
                if (invoker != null) { // Put new invoker in cache
                    newUrlInvokerMap.put(key, invoker);
                }
                // 命中缓存
            } else {
                // invoker 存储到 newUrlInvokerMap
                newUrlInvokerMap.put(key, invoker);
            }
        }
        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * Merge url parameters. the order is: override > -D >Consumer > Provider
     *
     * 合并 URL 参数，优先级为配置规则 > 服务消费者配置 > 服务提供者配置
     * @param providerUrl
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        // 合并消费端的参数
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // Merge the consumer side parameters

        providerUrl = overrideWithConfigurator(providerUrl);
        // 不检查是否成功，总是创建 invoker
        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // Do not check whether the connection is successful or not, always create Invoker!

        // The combination of directoryUrl and override is at the end of notify, which can't be handled here
        // 仅合并提供者参数，因为 directoryUrl 与 override 合并是在 notify 的最后，这里不能够处理
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // Merge the provider side parameters

        if ((providerUrl.getPath() == null || providerUrl.getPath()
                .length() == 0) && "dubbo".equals(providerUrl.getProtocol())) { // Compatible version 1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private URL overrideWithConfigurator(URL providerUrl) {
        // override url with configurator from "override://" URL for dubbo 2.6 and before
        providerUrl = overrideWithConfigurators(this.configurators, providerUrl);

        // override url with configurator from configurator from "app-name.configurators"
        providerUrl = overrideWithConfigurators(consumerConfigurationListener.getConfigurators(), providerUrl);

        // override url with configurator from configurators from "service-name.configurators"
        if (serviceConfigurationListener != null) {
            providerUrl = overrideWithConfigurators(serviceConfigurationListener.getConfigurators(), providerUrl);
        }

        return providerUrl;
    }

    private URL overrideWithConfigurators(List<Configurator> configurators, URL url) {
        if (configurators != null && !configurators.isEmpty()) {
            for (Configurator configurator : configurators) {
                url = configurator.configure(url);
            }
        }
        return url;
    }

    /**
     * Close all invokers
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        invokers = null;
    }

    /**
     * Check whether the invoker in the cache needs to be destroyed
     * If set attribute of url: refer.autodestroy=false, the invokers will only increase without decreasing,there may be a refer leak
     *
     * @param oldUrlInvokerMap
     * @param newUrlInvokerMap
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                // 检测 newInvokers 中是否包含老的 Invoker
                if (!newInvokers.contains(entry.getValue())) {
                    if (deleted == null) {
                        deleted = new ArrayList<String>();
                    }
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            for (String url : deleted) {
                if (url != null) {
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destroy invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destroy invoker[" + invoker.getUrl() + "] failed. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public List<Invoker<T>> doList(Invocation invocation) {
        if (forbidden) {
            // 1. No service provider 2. Service providers are disabled
            // 服务提供者关闭或禁用了服务，此时抛出 No provider 异常
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION, "No provider available from registry " +
                    getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +
                    NetUtils.getLocalHost() + " use dubbo version " + Version.getVersion() +
                    ", please check status of providers(disabled, not registered or in blacklist).");
        }

        if (multiGroup) {
            return this.invokers == null ? Collections.emptyList() : this.invokers;
        }

        List<Invoker<T>> invokers = null;
        try {
            // Get invokers from cache, only runtime routers will be executed.
            invokers = routerChain.route(getConsumerUrl(), invocation);
        } catch (Throwable t) {
            logger.error("Failed to execute router: " + getUrl() + ", cause: " + t.getMessage(), t);
        }


        // FIXME Is there any need of failing back to Constants.ANY_VALUE or the first available method invokers when invokers is null?
        /*Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference
        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            String methodName = RpcUtils.getMethodName(invocation);
            invokers = localMethodInvokerMap.get(methodName);
            if (invokers == null) {
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }*/
        // 返回invoker列表
        return invokers == null ? Collections.emptyList() : invokers;
    }

    @Override
    public Class<T> getInterface() {
        return serviceType;
    }

    @Override
    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    @Override
    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void buildRouterChain(URL url) {
        this.setRouterChain(RouterChain.buildChain(url));
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    public List<Invoker<T>> getInvokers() {
        return invokers;
    }

    private boolean isValidCategory(URL url) {
        String category = url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
        if ((ROUTERS_CATEGORY.equals(category) || ROUTE_PROTOCOL.equals(url.getProtocol())) ||
                PROVIDERS_CATEGORY.equals(category) ||
                CONFIGURATORS_CATEGORY.equals(category) || DYNAMIC_CONFIGURATORS_CATEGORY.equals(category) ||
                APP_DYNAMIC_CONFIGURATORS_CATEGORY.equals(category)) {
            return true;
        }
        logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " +
                getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
        return false;
    }

    private boolean isNotCompatibleFor26x(URL url) {
        return StringUtils.isEmpty(url.getParameter(Constants.COMPATIBLE_CONFIG_KEY));
    }

    private void overrideDirectoryUrl() {
        // merge override parameters
        this.overrideDirectoryUrl = directoryUrl;
        List<Configurator> localConfigurators = this.configurators; // local reference
        doOverrideUrl(localConfigurators);
        List<Configurator> localAppDynamicConfigurators = consumerConfigurationListener.getConfigurators(); // local reference
        doOverrideUrl(localAppDynamicConfigurators);
        if (serviceConfigurationListener != null) {
            List<Configurator> localDynamicConfigurators = serviceConfigurationListener.getConfigurators(); // local reference
            doOverrideUrl(localDynamicConfigurators);
        }
    }

    private void doOverrideUrl(List<Configurator> configurators) {
        if (configurators != null && !configurators.isEmpty()) {
            for (Configurator configurator : configurators) {
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }
    }

    /**
     * The delegate class, which is mainly used to store the URL address sent by the registry,and can be reassembled on the basis of providerURL queryMap overrideMap for re-refer.
     *
     * @param <T>
     */
    private static class InvokerDelegate<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegate(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }

    private static class ReferenceConfigurationListener extends AbstractConfiguratorListener {
        private RegistryDirectory directory;
        private URL url;

        ReferenceConfigurationListener(RegistryDirectory directory, URL url) {
            this.directory = directory;
            this.url = url;
            this.initWith(url.getEncodedServiceKey() + Constants.CONFIGURATORS_SUFFIX);
        }

        @Override
        protected void notifyOverrides() {
            // to notify configurator/router changes
            directory.refreshInvoker(Collections.emptyList());
        }
    }

    private static class ConsumerConfigurationListener extends AbstractConfiguratorListener {
        List<RegistryDirectory> listeners = new ArrayList<>();

        ConsumerConfigurationListener() {
            this.initWith(ApplicationModel.getApplication() + Constants.CONFIGURATORS_SUFFIX);
        }

        void addNotifyListener(RegistryDirectory listener) {
            this.listeners.add(listener);
        }

        @Override
        protected void notifyOverrides() {
            listeners.forEach(listener -> listener.refreshInvoker(Collections.emptyList()));
        }
    }

}
