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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mortbay.jetty.orchestrator.Cluster;
import org.mortbay.jetty.orchestrator.ClusterTools;
import org.mortbay.jetty.orchestrator.NodeArray;
import org.mortbay.jetty.orchestrator.NodeArrayFuture;
import org.mortbay.jetty.orchestrator.configuration.ClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SimpleNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SshRemoteHostLauncher;
import sshd.AbstractSshTest;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class ClusterTest extends AbstractSshTest
{
    public static Stream<ClusterConfiguration> clusterConfigurations() throws Exception
    {
        ClusterConfiguration cfg1 = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").node(new Node("1", "localhost")).node(new Node("2", "localhost")))
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").node(new Node("1", "localhost")).node(new Node("2", "localhost")))
            ;

        ClusterConfiguration cfg2 = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").node(new Node("1", "localhost")))
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").node(new Node("1", "localhost")))
            ;

        String localHostname = InetAddress.getLocalHost().getHostName();
        ClusterConfiguration cfg3 = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").node(new Node("1", localHostname)))
            .nodeArray(new SimpleNodeArrayConfiguration("client-array").node(new Node("1", localHostname)))
            .hostLauncher(new SshRemoteHostLauncher(sshd.getPort()))
            ;

        return Stream.of(cfg1, cfg2, cfg3);
    }

    @ParameterizedTest
    @MethodSource("clusterConfigurations")
    public void testCluster(ClusterConfiguration cfg) throws Exception
    {
        try (Cluster cluster = new Cluster(cfg))
        {
            final int participantCount = cfg.nodeArrays().stream().mapToInt(cc -> cc.nodes().size()).sum() + 1;
            NodeArray serverArray = cluster.nodeArray("server-array");
            NodeArray clientArray = cluster.nodeArray("client-array");

            NodeArrayFuture sf = serverArray.executeOnAll(tools ->
            {
                long counter = tools.atomicCounter("counter", 0L).incrementAndGet();
                String javaVersion = System.getProperty("java.version");
                int pos = tools.barrier("barrier", participantCount).await();
                System.out.println("servers: hello, world! from java " + javaVersion + " counter = " + counter + " arrival = " + pos);
            });
            NodeArrayFuture cf = clientArray.executeOnAll(tools ->
            {
                long counter = tools.atomicCounter("counter", 0L).incrementAndGet();
                String javaVersion = System.getProperty("java.version");
                int pos = tools.barrier("barrier", participantCount).await();
                System.out.println("clients: hello, world! from java " + javaVersion + " counter = " + counter + " arrival = " + pos);

                File f = new File("data.txt");
                try (FileOutputStream fos = new FileOutputStream(f))
                {
                    fos.write(("client arrived #" + pos + "\n").getBytes(StandardCharsets.UTF_8));
                }
                System.out.println("wrote file " + f.getAbsolutePath());
            });

            ClusterTools tools = cluster.tools();
            long counter = tools.atomicCounter("counter", 0L).incrementAndGet();
            int pos = tools.barrier("barrier", participantCount).await();
            System.out.println("test: hello, world! counter = " + counter + " arrival = " + pos);

            sf.get();
            cf.get();

            for (String id : clientArray.ids())
            {
                Path dataPath = clientArray.rootPathOf(id).resolve("data.txt");
                System.out.println("=== data.txt contents of node " + id + " ===");
                try (InputStream is = Files.newInputStream(dataPath))
                {
                    while (true)
                    {
                        int b = is.read();
                        if (b == -1)
                            break;
                        System.out.print((char) b);
                    }
                }
                System.out.println("=== === === === === === === === ===");
            }
        }
    }

    @Test
    public void testInvalidJvmExecutableInNodeArray() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").node(new Node("1", InetAddress.getLocalHost().getHostName())))
                .jvm(new Jvm(() -> "/does/not/exist")
            );

        assertThrows(Exception.class, () -> new Cluster(cfg));
    }

    @Test
    public void testInvalidJvmExecutableInLauncher() throws Exception
    {
        ClusterConfiguration cfg = new SimpleClusterConfiguration()
            .hostLauncher(new SshRemoteHostLauncher().jvm(new Jvm(() -> "/does/not/exist")))
            .nodeArray(new SimpleNodeArrayConfiguration("server-array").node(new Node("1", InetAddress.getLocalHost().getHostName())))
            ;

        assertThrows(Exception.class, () -> new Cluster(cfg));
    }
}
