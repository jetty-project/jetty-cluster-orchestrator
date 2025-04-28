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

package org.mortbay.jetty.orchestrator;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.configuration.ClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SimpleNodeArrayConfiguration;

public class HealthCheckTest
{
    @Test
    public void testClusterStaysAliveAfterHealthCheckDelay() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .healthCheckDelay(500)
            .healthCheckTimeout(2000)
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").node(new Node("1", "localhost")).node(new Node("2", "localhost")))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            // If this doesn't throw after 5s, the 2s health check timeout check still works.
            cluster.nodeArray("client-array").executeOnAll(tools ->
            {
                for (int i = 0; i < 5; i++)
                {
                    Thread.sleep(1000);
                    System.out.println("hello from " + tools.getGlobalNodeId().getNodeId());
                }
            }).get();
        }
    }

    @Test
    @Disabled("kills the JVM, no way to assert failure")
    public void testFailHealthCheck() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .healthCheckDelay(2000)
            .healthCheckTimeout(1000)
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").node(new Node("1", "localhost")).node(new Node("2", "localhost")))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            cluster.nodeArray("client-array").executeOnAll(tools ->
            {
                for (int i = 0; i < 5; i++)
                {
                    Thread.sleep(1000);
                    System.out.println("hello from " + tools.getGlobalNodeId().getNodeId());
                }
            }).get();
        }
    }
}
