/*
 * Copyright 2013-2023 the HotswapAgent authors.
 *
 * This file is part of HotswapAgent.
 *
 * HotswapAgent is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 2 of the License, or (at your
 * option) any later version.
 *
 * HotswapAgent is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with HotswapAgent. If not, see http://www.gnu.org/licenses/.
 */
package org.hotswap.agent.plugin.spring.scanner;

import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.spring.ResetBeanFactoryCaches;
import org.hotswap.agent.plugin.spring.ResetBeanFactoryPostProcessorCaches;
import org.hotswap.agent.plugin.spring.ResetBeanPostProcessorCaches;
import org.hotswap.agent.plugin.spring.ResetSpringStaticCaches;
import org.hotswap.agent.plugin.spring.SpringPlugin;
import org.hotswap.agent.plugin.spring.core.BeanFactoryProcessor;
import org.hotswap.agent.plugin.spring.getbean.ProxyReplacer;
import org.hotswap.agent.plugin.spring.util.ResourceUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

import static org.hotswap.agent.plugin.spring.util.ResourceUtils.convertToClasspathURL;

/**
 * IMPORTANT: DON'T REFER TO THIS CLASS IN OTHER CLASS!!
 */
public class XmlBeanDefinitionScannerAgent {
    private static final AgentLogger LOGGER = AgentLogger.getLogger(XmlBeanDefinitionScannerAgent.class);

    public static final String PROPERTY_PLACEHOLDER_CONFIGURER = "org.springframework.beans.factory.config.PropertyPlaceholderConfigurer";
    public static final String PROPERTY_SOURCES_PLACEHOLDER_CONFIGURER = "org.springframework.context.support.PropertySourcesPlaceholderConfigurer";
    private static Map<String, XmlBeanDefinitionScannerAgent> instances = new HashMap<>();
    private static boolean basePackageInited = false;

    // xmlReader for corresponding url
    private BeanDefinitionReader reader;

    // XML's URL the current XmlBeanDefinitionScannerAgent is responsible for
    private URL url;

    // Beans defined in the XML file (beanName -> beanClassName)
    private Map<String, String> beansRegistered = new HashMap<>();

    // PropertyResourceConfigurer's locations defined in the XML file
    private Set<String> propertyLocations = new HashSet<>();

    /**
     * Flag to check reload status.
     * In unit test we need to wait for reload finish before the test can continue. Set flag to true
     * in the test class and wait until the flag is false again.
     */
    public static boolean reloadFlag = false;

    public static void registerBean(String beanName, BeanDefinition beanDefinition) {
        XmlBeanDefinitionScannerAgent agent = findAgent(beanDefinition);
        if (agent == null) {
            LOGGER.trace("cannot find registered XmlBeanDefinitionScannerAgent for bean {}", beanName);
            return;
        }

        registerBeanName(agent, beanName, beanDefinition.getBeanClassName());
        registerPropertyLocations(agent, beanDefinition);
    }

    /**
     * need to ensure that when method is invoked first time , this class is not loaded,
     * so this class is will be loaded by appClassLoader
     */
    public static void registerXmlBeanDefinitionScannerAgent(XmlBeanDefinitionReader reader, Resource resource) {
        String path = ResourceUtils.getPath(resource);
        if (path == null) {
            return;
        }

        URL resourceUrl = null;
        try {
            resourceUrl = resource.getURL();
        } catch (IOException e) {
            // ignore
        }

        if (!instances.containsKey(path)) {
            instances.put(path, new XmlBeanDefinitionScannerAgent(reader, resourceUrl));
        }
    }

    public static void reloadClass(String className) {
        for (XmlBeanDefinitionScannerAgent agent : instances.values()) {
            if (!agent.beansRegistered.containsValue(className)) {
                continue;
            }

            try {
                LOGGER.debug("Reloading XML {} since class {} changed", agent.url, className);
                agent.reloadBeanFromXml();
            } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
                LOGGER.error("Reloading XML failed: {}", e.getMessage());
            }
        }
    }

    public static void reloadXml(URL url) {
        XmlBeanDefinitionScannerAgent xmlBeanDefinitionScannerAgent = instances.get(convertToClasspathURL(url.getPath()));
        if (xmlBeanDefinitionScannerAgent == null) {
            LOGGER.warning("url " + url + " is not associated with any XmlBeanDefinitionScannerAgent, not reloading");
            return;
        }
        try {
            xmlBeanDefinitionScannerAgent.reloadBeanFromXml();
        } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
            LOGGER.error("Reloading XML failed: {}", e.getMessage());
        }
    }

    public static List<String> reloadXmls(List<URL> urls) {
        List<XmlBeanDefinitionScannerAgent> agents = new ArrayList<>(urls.size());
        for (URL url : urls) {
            XmlBeanDefinitionScannerAgent xmlBeanDefinitionScannerAgent = instances.get(url.getPath());
            if (xmlBeanDefinitionScannerAgent == null) {
                LOGGER.warning("url " + url + " is not associated with any XmlBeanDefinitionScannerAgent, not reloading");
                continue;
            }
            try {
                agents.add(xmlBeanDefinitionScannerAgent);
                xmlBeanDefinitionScannerAgent.clearCache();
            } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
                LOGGER.error("Reloading XML failed: {}", e.getMessage());
            }
        }
        List<String> beanNames = new ArrayList<>();
        for (XmlBeanDefinitionScannerAgent agent : agents) {
            try {
                agent.reloadDefinition();
                beanNames.addAll(agent.beansRegistered.keySet());
            } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
                LOGGER.error("Reloading XML failed: {}", e.getMessage());
            }
        }
        return beanNames;
    }

    public static Set<String> reloadXmls(Set<URL> urls, Set<String> resourcePaths) {
        Set<XmlBeanDefinitionScannerAgent> agents = new HashSet<>(resourcePaths.size() + urls.size());
        for (String resourcePath : resourcePaths) {
            XmlBeanDefinitionScannerAgent xmlBeanDefinitionScannerAgent = instances.get(resourcePath);
            if (xmlBeanDefinitionScannerAgent == null) {
                LOGGER.warning("url " + resourcePath + " is not associated with any XmlBeanDefinitionScannerAgent, not reloading");
                continue;
            }
            try {
                if (agents.add(xmlBeanDefinitionScannerAgent)){
                    xmlBeanDefinitionScannerAgent.clearCache();
                }
            } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
                LOGGER.error("Reloading XML failed: {}", e.getMessage());
            }
        }
        for (URL url : urls) {
            XmlBeanDefinitionScannerAgent xmlBeanDefinitionScannerAgent = instances.get(url.getPath());
            if (xmlBeanDefinitionScannerAgent == null) {
                LOGGER.warning("url " + url + " is not associated with any XmlBeanDefinitionScannerAgent, not reloading");
                continue;
            }
            try {
                if (agents.add(xmlBeanDefinitionScannerAgent)) {
                    xmlBeanDefinitionScannerAgent.clearCache();
                }
            } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
                LOGGER.error("Reloading XML failed: {}", e.getMessage());
            }
        }
        Set<String> beanNames = new HashSet<>();
        for (XmlBeanDefinitionScannerAgent agent : agents) {
            try {
                agent.reloadDefinition();
                beanNames.addAll(agent.beansRegistered.keySet());
            } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
                LOGGER.error("Reloading XML failed: {}", e.getMessage());
            }
        }
        return beanNames;
    }

    public static void reloadProperty(URL url) {
        String path = convertToClasspathURL(url.getPath());
        if (path == null) {
            return;
        }
        for (XmlBeanDefinitionScannerAgent agent : instances.values()) {
            if (!agent.propertyLocations.contains(path)) {
                continue;
            }

            try {
                LOGGER.debug("Reloading XML {} since property file {} changed", agent.url, url);
                agent.reloadBeanFromXml();
            } catch (org.springframework.beans.factory.parsing.BeanDefinitionParsingException e) {
                LOGGER.error("Reloading XML failed: {}", e.getMessage());
            }
        }
    }


    private static XmlBeanDefinitionScannerAgent findAgent(BeanDefinition beanDefinition) {
        if (!(beanDefinition instanceof AbstractBeanDefinition)) {
            LOGGER.debug("BeanDefinition [{}] is not an instance of AbstractBeanDefinition, ignore", beanDefinition);
            return null;
        }

        if (beanDefinition instanceof AnnotatedBeanDefinition) {
            LOGGER.debug("BeanDefinition [{}] is an instance of AnnotatedBeanDefinition, ignore", beanDefinition);
            return null;
        }

        Resource resource = ((AbstractBeanDefinition) beanDefinition).getResource();
        if (resource == null) {
            LOGGER.debug("BeanDefinition [{}] has no resource, ignore", beanDefinition);
            return null;
        }

        try {
            String path = convertToClasspathURL(resource.getURL().getPath());
            return instances.get(path);
        } catch (IOException e) {
            LOGGER.warning("Fail to fetch url from resource: {}", resource);
            return null;
        }
    }

    private static void registerBeanName(XmlBeanDefinitionScannerAgent agent, String beanName, String beanClassName) {
        agent.beansRegistered.put(beanName, beanClassName == null ? "" : beanClassName);
    }

    private static void registerPropertyLocations(XmlBeanDefinitionScannerAgent agent, BeanDefinition beanDefinition) {
        String clazz = beanDefinition.getBeanClassName();
        if (!PROPERTY_PLACEHOLDER_CONFIGURER.equals(clazz) && !PROPERTY_SOURCES_PLACEHOLDER_CONFIGURER.equals(clazz)) {
            return;
        }

        PropertyValue pv = beanDefinition.getPropertyValues().getPropertyValue("location");
        if (pv != null && pv.getValue() instanceof TypedStringValue) {
            String location = ((TypedStringValue) pv.getValue()).getValue();
            if (location != null) {
                agent.propertyLocations.add(convertPropertyLocation(location));
            }
        }

        pv = beanDefinition.getPropertyValues().getPropertyValue("locations");
        if (pv != null && pv.getValue() instanceof ManagedList) {
            for (Object o : (ManagedList<?>) pv.getValue()) {
                TypedStringValue value = (TypedStringValue) o;
                String location = value.getValue();
                if (location == null) {
                    continue;
                }

                agent.propertyLocations.add(convertPropertyLocation(location));
            }
        }
    }

    private static String convertPropertyLocation(String location) {
        if (location.startsWith("classpath:")) {
            location = location.substring("classpath:".length());
        } else {
            location = convertToClasspathURL(location);
        }
        return location;
    }

    private XmlBeanDefinitionScannerAgent(BeanDefinitionReader reader, URL url) {
        this.reader = reader;
        this.url = url;

        if (SpringPlugin.basePackagePrefixes != null && !basePackageInited) {
            ClassPathBeanDefinitionScannerAgent xmlBeanDefinitionScannerAgent = ClassPathBeanDefinitionScannerAgent.getInstance(new ClassPathBeanDefinitionScanner(reader.getRegistry()));
            for (String basePackage : SpringPlugin.basePackagePrefixes) {
                xmlBeanDefinitionScannerAgent.registerBasePackage(basePackage);
            }
            basePackageInited = true;
        }
    }

    /**
     * reload bean from xml definition
     */
    public void reloadBeanFromXml() {
        clearCache();
        reloadDefinition();
    }

    void clearCache() {

        DefaultListableBeanFactory factory = maybeRegistryToBeanFactory();
        if (factory == null) {
            LOGGER.warning("Fail to find bean factory for url {}, cannot reload", this.url);
            return;
        }

        removeRegisteredBeanDefinitions(factory);
    }

    void reloadDefinition() {

        LOGGER.info("Reloading XML file: " + url);
        // this will call registerBeanDefinition which in turn call resetBeanDefinition to destroy singleton
        // maybe should use watchResourceClassLoader.getResource?
        this.reader.loadBeanDefinitions(new FileSystemResource(url.getPath()));

        reloadFlag = false;
    }

    private void removeRegisteredBeanDefinitions(DefaultListableBeanFactory factory) {
        LOGGER.debug("Remove all beans defined in the XML file {} before reloading it", url.getPath());
        for (String beanName : beansRegistered.keySet()) {
            try {
                BeanFactoryProcessor.removeBeanDefinition(factory, beanName);
            } catch (NoSuchBeanDefinitionException e) {
                LOGGER.debug("Bean {} not found in factory, ignore", beanName);
            }
        }

        beansRegistered.clear();
    }

    private DefaultListableBeanFactory maybeRegistryToBeanFactory() {
        BeanDefinitionRegistry registry = this.reader.getRegistry();
        if (registry instanceof DefaultListableBeanFactory) {
            return (DefaultListableBeanFactory) registry;
        } else if (registry instanceof GenericApplicationContext) {
            return ((GenericApplicationContext) registry).getDefaultListableBeanFactory();
        }
        return null;
    }
}
