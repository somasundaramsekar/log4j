/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */
package org.apache.logging.log4j.core.appender.routing;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AppenderBase;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttr;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.filter.CompositeFilter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This Appender "routes" between various Appenders, some of which can be references to
 * Appenders defined earlier in the configuration while others can be dynamically created
 * within this Appender as required. Routing is achieved by specifying a pattern on
 * the Routing appender declaration. The pattern should contain one or more substitution patterns of
 * the form "$${[key:]token}". The pattern will be resolved each time the Appender is called using
 * the built in StrSubstitutor and the StrLookup plugin that matches the specified key.
 */
@Plugin(name="Routing",type="Core",elementType="appender",printObject=true)
public class RoutingAppender extends AppenderBase {
    private static final String DEFAULT_KEY = "ROUTING_APPENDER_DEFAULT";
    private final Routes routes;
    private final Configuration config;
    private ConcurrentMap<String, AppenderControl> appenders = new ConcurrentHashMap<String, AppenderControl>();

    private RoutingAppender(String name, CompositeFilter filters, boolean handleException, Routes routes,
                            Configuration config) {
        super(name, filters, null, handleException);
        this.routes = routes;
        this.config = config;
    }

    @Override
    public void start() {
        Map<String, Appender> map = config.getAppenders();
        for (Route route : routes.getRoutes()) {
            if (route.getAppenderRef() != null) {
                Appender appender = map.get(route.getAppenderRef());
                if (appender != null) {
                    String key = route.getKey() == null ? DEFAULT_KEY : route.getKey();
                    if (appenders.containsKey(key)) {
                        if (DEFAULT_KEY.equals(key)) {
                            logger.error("Multiple default routes. Only the first will be used");
                        } else {
                            logger.error("Duplicate route " + key + " is ignored");
                        }
                    } else {
                        appenders.put(key, new AppenderControl(appender));
                    }
                } else {
                    logger.error("Appender " + route.getAppenderRef() + " cannot be located. Route ignored");
                }
            }
        }
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        for (AppenderControl control : appenders.values()) {
            if (control instanceof AppenderWrapper) {
                control.getAppender().stop();
            }
        }
    }

    public void append(LogEvent event) {
        String key = config.getSubst().replace(event, routes.getPattern());
        AppenderControl control = getControl(key, event);
        if (control != null) {
            control.callAppender(event);
        }
    }

    private synchronized AppenderControl getControl(String key, LogEvent event) {
        AppenderControl control = appenders.get(key);
        boolean defaultRoute = false;
        if (control != null) {
            return control;
        }
        Route route = null;
        for (Route r : routes.getRoutes()) {
            if (r.getAppenderRef() == null && key.equals(r.getKey())) {
                route = r;
                break;
            }
        }
        if (route == null) {
            control = appenders.get(DEFAULT_KEY);
            if (control != null) {
                return control;
            }
            for (Route r : routes.getRoutes()) {
                if (r.getAppenderRef() == null && r.getKey() == null) {
                    route = r;
                    defaultRoute = true;
                    break;
                }
            }
        }
        if (route != null) {
            Appender app = createAppender(route, event);
            if (app == null) {
                return null;
            }
            control = new AppenderWrapper(app);
            appenders.put(key, control);
            if (defaultRoute) {
                appenders.put(DEFAULT_KEY, control);
            }
        }

        return control;
    }

    private Appender createAppender(Route route, LogEvent event) {
        Node routeNode = route.getNode();
        for (Node node : routeNode.getChildren()) {
            if (node.getType().getElementName().equals("appender")) {
                config.createConfiguration(node, event);
                if (node.getObject() instanceof Appender) {
                    Appender app = (Appender) node.getObject();
                    app.start();
                    return (Appender) node.getObject();
                }
                logger.error("Unable to create Appender of type " + node.getName());
                return null;
            }
        }
        logger.error("No Appender was configured for route " + route.getKey());
        return null;
    }

    @PluginFactory
    public static RoutingAppender createAppender(@PluginAttr("name") String name,
                                          @PluginAttr("suppressExceptions") String suppress,
                                          @PluginElement("routes") Routes routes,
                                          @PluginConfiguration Configuration config,
                                          @PluginElement("filters") CompositeFilter filters) {

        boolean handleExceptions = suppress == null ? true : Boolean.valueOf(suppress);

        if (name == null) {
            logger.error("No name provided for RoutingAppender");
            return null;
        }
        if (routes == null) {
            logger.error("No routes defined for RoutingAppender");
            return null;
        }
        return new RoutingAppender(name, filters, handleExceptions, routes, config);
    }

    private static class AppenderWrapper extends AppenderControl {
        public AppenderWrapper(Appender appender) {
            super(appender);
        }
    }
}