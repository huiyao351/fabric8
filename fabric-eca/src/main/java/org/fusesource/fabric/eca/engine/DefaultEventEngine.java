/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2011 FuseSource Corporation, a Progress Software company. All rights reserved.
 *
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License("CDDL") (the "License").
 * You may not use this file except in compliance with the License. You can obtain
 * a copy of the License at http://www.opensource.org/licenses/CDDL-1.0.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at resources/META-INF/LICENSE.txt.
 *
 */
package org.fusesource.fabric.eca.engine;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.ServiceSupport;
import org.fusesource.fabric.eca.eventcache.EventCache;
import org.fusesource.fabric.eca.eventcache.EventCacheManager;
import org.fusesource.fabric.eca.expression.Expression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultEventEngine extends ServiceSupport implements org.fusesource.fabric.eca.engine.EventEngine {
    private static final transient Logger LOG = LoggerFactory.getLogger(DefaultEventEngine.class);
    private EventCacheManager eventCacheManager;
    private Map<String, List<ExpressionHolder>> fromToExpressionMap = new ConcurrentHashMap<String, List<ExpressionHolder>>();
    private Map<Expression, List<String>> expressionToFromMap = new ConcurrentHashMap<Expression, List<String>>();

    /**
     * Initialize the engine - which will add itself to the context
     */
    public void intialize(CamelContext context, String cacheImplementation) throws Exception {
        eventCacheManager = EventHelper.getEventCacheManager(context, cacheImplementation);
    }

    /**
     * Add a route Id
     */
    public EventCache<Exchange> addRoute(String fromId, String window) {
        EventCache<Exchange> result = eventCacheManager.getCache(Exchange.class, fromId, window);
        return result;
    }

    /**
     * remove a route
     */
    public void removeRoute(String routeId) {
        eventCacheManager.removeCache(routeId);
    }

    /**
     * Process an Exchange
     */
    public void process(Exchange exchange) {
        if (exchange != null) {
            String fromId = exchange.getFromRouteId();
            if (fromId == null) {
                fromId = exchange.getFromEndpoint().getEndpointUri();
            }
            if (fromId != null) {
                EventCache<Exchange> eventCache = eventCacheManager.lookupCache(Exchange.class, fromId);
                if (eventCache == null) {
                    fromId = exchange.getFromEndpoint().getEndpointKey();
                    eventCache = eventCacheManager.lookupCache(Exchange.class, fromId);
                }
                if (eventCache != null) {
                    if (eventCache.add(exchange)) {
                        //Get the matching expressions
                        List<ExpressionHolder> expressionHolders = fromToExpressionMap.get(fromId);
                        for (ExpressionHolder expressionHolder : expressionHolders) {
                            if (expressionHolder.expression.isMatch()) {
                                if (expressionHolder.listener != null) {
                                    expressionHolder.listener.expressionFired(expressionHolder.expression, exchange);
                                }
                            }
                        }
                    } else {
                        //ignore - already fired the rule for this exchange
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Ignoring - already fired for exchange: " + exchange);
                        }
                    }

                } else {
                    LOG.warn("Can't find cache for a route or endpoint named: " + fromId + " for exchange: " + exchange);
                }
            } else {
                LOG.warn("can't process an exchange with no route or endpoint information: ", exchange);
            }
        } else {
            LOG.warn("process() passed null exchange!");
        }
    }


    /**
     * Add an expression - equivalent of a rule
     */
    public void addExpression(Expression expression, ExpressionListener listener) {
        ExpressionHolder expressionHolder = new ExpressionHolder();
        expressionHolder.expression = expression;
        expressionHolder.listener = listener;
        //get the route Ids
        String[] fromIds = expression.getFromIds().split(",");
        List<String> fromList = expressionToFromMap.get(expression);
        if (fromList == null) {
            fromList = new CopyOnWriteArrayList<String>();
            expressionToFromMap.put(expression, fromList);
        }

        for (String fromId : fromIds) {
            fromId = fromId.trim();
            fromList.add(fromId);

            List<ExpressionHolder> expressionHolderList = fromToExpressionMap.get(fromId);
            if (expressionHolderList == null) {
                expressionHolderList = new CopyOnWriteArrayList<ExpressionHolder>();
                fromToExpressionMap.put(fromId, expressionHolderList);
            }
            expressionHolderList.add(expressionHolder);
        }
    }

    /**
     * remove an expression
     */
    public void removeExpression(Expression expression) {
        List<String> routeList = expressionToFromMap.remove(expression);
        if (routeList != null) {
            for (String routeId : routeList) {
                List<ExpressionHolder> expressionHolderList = fromToExpressionMap.get(routeId);
                if (expressionHolderList != null) {
                    for (ExpressionHolder expressionHolder : expressionHolderList) {
                        if (expressionHolder.expression == expression) {
                            expressionHolderList.remove(expressionHolder);
                            if (expressionHolderList.isEmpty()) {
                                fromToExpressionMap.remove(routeId);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }


    @Override
    protected void doStart() throws Exception {
        eventCacheManager.start();
        for (Expression expression : expressionToFromMap.keySet()) {
            expression.start();
        }
    }

    @Override
    protected void doStop() throws Exception {
        eventCacheManager.stop();
        for (Expression expression : expressionToFromMap.keySet()) {
            expression.stop();
        }
    }

    private static class ExpressionHolder {
        Expression expression;
        ExpressionListener listener;
    }
}
