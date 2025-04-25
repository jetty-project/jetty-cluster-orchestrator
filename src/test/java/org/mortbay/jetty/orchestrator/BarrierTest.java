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

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.configuration.ClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SimpleNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.tools.Barrier;

public class BarrierTest
{
    @Test
    public void testNoTimeout() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").node(new Node("1", "localhost")))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");

            int count = 100;
            NodeArrayFuture future = nodeArray.executeOnAll(tools ->
            {
                for (int i = 0; i < count; i++)
                {
                    tools.barrier("the-barrier", 2).await();
                }
            });

            for (int i = 0; i < count; i++)
            {
                cluster.tools().barrier("the-barrier", 2).await();
            }
            future.get();
        }
    }

    @Test
    public void testTimeout() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").node(new Node("1", "localhost")))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");
            NodeArrayFuture future = nodeArray.executeOnAll(tools ->
            {
                Assertions.assertThrows(TimeoutException.class, () -> tools.barrier("the-barrier", 3).await(1, TimeUnit.SECONDS));
            });

            Assertions.assertThrows(TimeoutException.class, () -> cluster.tools().barrier("the-barrier", 3).await(1, TimeUnit.SECONDS));
            future.get();
        }
    }

    @Test
    public void testNotCyclic() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration();

        try (Cluster cluster = new Cluster(cfg))
        {
            Barrier barrier = cluster.tools().barrier("the-barrier", 1);
            barrier.await(1, TimeUnit.SECONDS);

            Assertions.assertThrows(BrokenBarrierException.class, () -> barrier.await());
            Assertions.assertThrows(BrokenBarrierException.class, () -> barrier.await(1, TimeUnit.SECONDS));
        }
    }
}
