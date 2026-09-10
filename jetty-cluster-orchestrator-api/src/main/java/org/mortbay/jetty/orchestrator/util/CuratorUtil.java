//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.mortbay.jetty.orchestrator.util;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

/**
 * Utility class for creating Curator framework instances with configurable
 * retry policies and timeouts via system properties.
 */
public class CuratorUtil
{
    // System property keys
    public static final String RETRY_BASE_SLEEP_TIME_MS_PROPERTY = "jco.curator.retry.baseSleepTimeMs";
    public static final String RETRY_MAX_RETRIES_PROPERTY = "jco.curator.retry.maxRetries";
    public static final String SESSION_TIMEOUT_MS_PROPERTY = "jco.curator.sessionTimeoutMs";
    public static final String CONNECTION_TIMEOUT_MS_PROPERTY = "jco.curator.connectionTimeoutMs";

    // Default values
    private static final int DEFAULT_BASE_SLEEP_TIME_MS = 1000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int DEFAULT_SESSION_TIMEOUT_MS = 30000;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 15000;

    /**
     * Creates a new CuratorFramework instance with configurable retry policy and timeouts.
     * 
     * Configuration is controlled via system properties:
     * <ul>
     * <li>{@code jco.curator.retry.baseSleepTimeMs} - Base delay between retries in milliseconds (default: 1000)</li>
     * <li>{@code jco.curator.retry.maxRetries} - Maximum number of retry attempts (default: 3)</li>
     * <li>{@code jco.curator.sessionTimeoutMs} - ZooKeeper session timeout in milliseconds (default: 30000)</li>
     * <li>{@code jco.curator.connectionTimeoutMs} - Initial connection timeout in milliseconds (default: 15000)</li>
     * </ul>
     * 
     * @param connectString the ZooKeeper connection string
     * @return a configured CuratorFramework instance (not started)
     */
    public static CuratorFramework newClient(String connectString)
    {
        int baseSleepTimeMs = Integer.getInteger(RETRY_BASE_SLEEP_TIME_MS_PROPERTY, DEFAULT_BASE_SLEEP_TIME_MS);
        int maxRetries = Integer.getInteger(RETRY_MAX_RETRIES_PROPERTY, DEFAULT_MAX_RETRIES);
        int sessionTimeoutMs = Integer.getInteger(SESSION_TIMEOUT_MS_PROPERTY, DEFAULT_SESSION_TIMEOUT_MS);
        int connectionTimeoutMs = Integer.getInteger(CONNECTION_TIMEOUT_MS_PROPERTY, DEFAULT_CONNECTION_TIMEOUT_MS);

        RetryPolicy retryPolicy = new ExponentialBackoffRetry(baseSleepTimeMs, maxRetries);

        return CuratorFrameworkFactory.builder()
            .connectString(connectString)
            .retryPolicy(retryPolicy)
            .sessionTimeoutMs(sessionTimeoutMs)
            .connectionTimeoutMs(connectionTimeoutMs)
            .build();
    }

    /**
     * Creates a new RetryPolicy with configurable parameters.
     * 
     * @return a configured ExponentialBackoffRetry policy
     */
    public static RetryPolicy newRetryPolicy()
    {
        int baseSleepTimeMs = Integer.getInteger(RETRY_BASE_SLEEP_TIME_MS_PROPERTY, DEFAULT_BASE_SLEEP_TIME_MS);
        int maxRetries = Integer.getInteger(RETRY_MAX_RETRIES_PROPERTY, DEFAULT_MAX_RETRIES);
        return new ExponentialBackoffRetry(baseSleepTimeMs, maxRetries);
    }
}