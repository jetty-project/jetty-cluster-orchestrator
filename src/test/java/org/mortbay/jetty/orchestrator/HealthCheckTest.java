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
    @Disabled("too slow")
    public void testClusterStaysAliveAfterHealthCheckDelay() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").node(new Node("1", "localhost")).node(new Node("2", "localhost")))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            // If this doesn't throw after 20s, the 15s healthcheck still works.
            cluster.nodeArray("client-array").executeOnAll(tools ->
            {
                for (int i = 0; i < 20; i++)
                {
                    Thread.sleep(1000);
                    System.out.println("hello from " + tools.getGlobalNodeId().getNodeId());
                }
            }).get();
        }
    }

}
