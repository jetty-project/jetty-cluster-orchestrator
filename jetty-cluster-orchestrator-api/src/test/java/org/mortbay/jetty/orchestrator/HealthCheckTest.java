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

import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.configuration.ClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.localhost.configuration.LocalNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.util.JvmUtil;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class HealthCheckTest
{
    @Test
    public void testClusterStaysAliveAfterHealthCheckDelay() throws Exception
    {
        // Checks go out every 500ms and a node tolerates 10s without one. That is generous on
        // purpose: a busy CI agent can easily stall for a couple of seconds, and this test is
        // about the health checker leaving a working cluster alone, not about exact timings.
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(JvmUtil.currentJvm())
            .healthCheckDelay(500)
            .healthCheckTimeout(10000)
            .nodeArray(new LocalNodeArrayConfiguration("client-array")
                    .node("1")
                    .node("2"))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            // The nodes keep working for 5s, so the cluster must still be up at the end.
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
    public void testFailHealthCheck()
    {
        // Checks only go out every 2s but a node gives up after 1s without one, so the nodes are
        // bound to die. What matters is that this JVM survives them: the node on localhost runs in
        // it, and it used to take the whole test JVM down with System.exit.
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(JvmUtil.currentJvm())
            .healthCheckDelay(2000)
            .healthCheckTimeout(1000)
            .nodeArray(new LocalNodeArrayConfiguration("client-array")
                    .node("1")
                    .node("2"))
            ;

        assertThrows(Exception.class, () ->
        {
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
        });
    }
}
