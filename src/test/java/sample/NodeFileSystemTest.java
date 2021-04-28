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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.Cluster;
import org.mortbay.jetty.orchestrator.NodeArray;
import org.mortbay.jetty.orchestrator.configuration.ClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.NodeArrayTopology;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SimpleNodeArrayConfiguration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class NodeFileSystemTest
{
    @Test
    public void testSmallFile() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").topology(new NodeArrayTopology(new Node("1", InetAddress.getLocalHost().getHostName()))))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");

            nodeArray.executeOnAll(tools ->
            {
                byte[] buffer = "Big File!\n".getBytes(StandardCharsets.UTF_8);
                long totalCount = 0L;
                try (OutputStream bigOs = new BufferedOutputStream(new FileOutputStream("big.txt"), 1024 * 1024))
                {
                    while (totalCount < 16 * 1024)
                    {
                        bigOs.write(buffer);
                        totalCount += buffer.length;
                    }
                }
                tools.atomicCounter("fileSize", totalCount);
            }).get();

            File targetFile = new File("target/big.txt");
            try (OutputStream os = new FileOutputStream(targetFile))
            {
                Files.copy(nodeArray.rootPathOf("1").resolve("big.txt"), os);
            }
            assertThat(cluster.tools().atomicCounter("fileSize", 0L).get(), is(targetFile.length()));
        }
    }

    @Test
    public void testLargeFile() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("my-array").topology(new NodeArrayTopology(new Node("1", InetAddress.getLocalHost().getHostName()))))
            ;

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray nodeArray = cluster.nodeArray("my-array");

            nodeArray.executeOnAll(tools ->
            {
                byte[] buffer = "Big File!\n".getBytes(StandardCharsets.UTF_8);
                long totalCount = 0L;
                try (OutputStream bigOs = new BufferedOutputStream(new FileOutputStream("big.txt"), 1024 * 1024))
                {
                    while (totalCount < 16 * 1024 * 1024)
                    {
                        bigOs.write(buffer);
                        totalCount += buffer.length;
                    }
                }
                tools.atomicCounter("fileSize", totalCount);
            }).get();

            File targetFile = new File("target/big.txt");
            try (OutputStream os = new FileOutputStream(targetFile))
            {
                Files.copy(nodeArray.rootPathOf("1").resolve("big.txt"), os);
            }
            assertThat(cluster.tools().atomicCounter("fileSize", 0L).get(), is(targetFile.length()));
        }
    }
}
