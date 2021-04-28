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

package sample;

import java.net.InetAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.Cluster;
import org.mortbay.jetty.orchestrator.NodeArray;
import org.mortbay.jetty.orchestrator.NodeArrayFuture;
import org.mortbay.jetty.orchestrator.configuration.ClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.NodeArrayTopology;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SimpleNodeArrayConfiguration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NodeArrayFutureTest
{
    @Test
    public void testDetectProcessDeath() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").topology(new NodeArrayTopology(new Node("1", InetAddress.getLocalHost().getHostName()))))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");
            NodeArrayFuture future = nodeArray.executeOnAll(tools ->
            {
                System.exit(1); // terminate the remote JVM
            });
            assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testDetectTimeout() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").topology(new NodeArrayTopology(new Node("1", InetAddress.getLocalHost().getHostName()), new Node("2", InetAddress.getLocalHost().getHostName()))))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");
            NodeArrayFuture future = nodeArray.executeOnAll(tools ->
            {
                int id = tools.barrier("barrier", 2).await();
                if (id == 0)
                    Thread.sleep(200);
                else
                    throw new ArithmeticException("something went wrong");
            });
            assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void testZeroTimeoutThenDetectDeath() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").topology(new NodeArrayTopology(new Node("1", InetAddress.getLocalHost().getHostName()))))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");
            NodeArrayFuture future1 = nodeArray.executeOnAll(tools ->
            {
                Thread.sleep(1000);
                System.exit(1); // terminate the remote JVM
            });
            assertThrows(TimeoutException.class, () -> future1.get(0, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, future1::get);

            NodeArrayFuture future2 = nodeArray.executeOnAll(tools ->
            {
                tools.atomicCounter("counter", 1); // this should not execute as the process should have died
            });
            assertThrows(ExecutionException.class, future2::get);
            assertThat(cluster.tools().atomicCounter("counter", 0).get(), is(0L));
        }
    }

    @Test
    public void testTimeoutIsSpread() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").topology(new NodeArrayTopology(new Node("1", InetAddress.getLocalHost().getHostName()), new Node("2", InetAddress.getLocalHost().getHostName()))))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");
            NodeArrayFuture future = nodeArray.executeOnAll(tools ->
            {
                Thread.sleep(600);
            });
            assertThrows(TimeoutException.class, () -> future.get(1, TimeUnit.SECONDS));
        }
    }
}
