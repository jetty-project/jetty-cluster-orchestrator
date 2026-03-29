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

package org.mortbay.jetty.orchestrator.k8s.launcher;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.Cluster;
import org.mortbay.jetty.orchestrator.NodeArray;
import org.mortbay.jetty.orchestrator.NodeArrayFuture;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.SimpleNodeArrayConfiguration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for {@link KubernetesRemoteHostLauncher}.
 *
 * <p>ZooKeeper is deployed automatically inside the Kubernetes cluster as a pod + service.
 * The controller connects via a {@code LocalPortForward}; pods use cluster-internal DNS.
 * No external network access to the controller machine is required.</p>
 *
 * <p>This test is skipped unless a Kubernetes cluster is available and
 * the following system properties are provided:</p>
 * <ul>
 *   <li>{@code k8s.image} (required) - container image with a JRE and {@code tar} (e.g. {@code eclipse-temurin:21-jre})</li>
 *   <li>{@code k8s.namespace} (optional, default: {@code default}) - Kubernetes namespace to use</li>
 * </ul>
 *
 * <p>Run with:</p>
 * <pre>
 *   mvn test -Dtest=KubernetesClusterTest \
 *       -Dk8s.image=eclipse-temurin:21-jre \
 *       -Dk8s.namespace=default
 * </pre>
 *
 * <p>{@code nodeArray.rootPathOf(id)} is backed by {@code KubernetesNodeFileSystem} and
 * is exercised in {@link #testNodeFileSystemAccess()}.</p>
 */
public class KubernetesClusterTest
{
    private static final String K8S_IMAGE = System.getProperty("k8s.image");
    private static final String K8S_NAMESPACE = System.getProperty("k8s.namespace", "default");

    private static void assumeKubernetesAvailable()
    {

        boolean hasKubeconfig = StringUtils.isNotEmpty(System.getProperty("kubernetes.config.path"))
            && Files.exists(Paths.get(System.getProperty("kubernetes.config.path")));
        Assumptions.assumeTrue(hasKubeconfig, "Kubernetes not configured (no KUBECONFIG env var or -Dkubernetes.config.path=");
        Assumptions.assumeTrue(StringUtils.isNotEmpty(K8S_IMAGE), "k8s.image system property not set");
    }

    @Test
    void testBasicNodeExecution() throws Exception
    {
        assumeKubernetesAvailable();

        KubernetesRemoteHostLauncher launcher = new KubernetesRemoteHostLauncher.Builder().namespace(K8S_NAMESPACE)
                .image(K8S_IMAGE)
                .kubernetesConfig(Paths.get(System.getProperty("kubernetes.config.path")))
                .build();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(new SimpleNodeArrayConfiguration("worker-array")
                .node(new Node.Builder().withId("1").withHostname("k8s-node-1").build()))
            .hostLauncher(launcher);

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray workerArray = cluster.nodeArray("worker-array");

            final int participantCount = 2; // 1 node + 1 test thread
            NodeArrayFuture future = workerArray.executeOnAll(tools ->
            {
                long counter = tools.atomicCounter("counter", 0L).incrementAndGet();
                tools.barrier("barrier", participantCount).await();
                System.out.println("k8s node executed, counter=" + counter);
            });

            long testCounter = cluster.tools().atomicCounter("counter", 0L).incrementAndGet();
            cluster.tools().barrier("barrier", participantCount).await(2, TimeUnit.MINUTES);
            future.get(2, TimeUnit.MINUTES);

            long finalCount = cluster.tools().atomicCounter("counter", 0L).get();
            assertThat(finalCount, equalTo(2L)); // 1 from test thread + 1 from node
        }
    }

    /**
     * Verifies that {@code nodeArray.rootPathOf(id)} returns a valid NIO {@link Path} backed
     * by {@code KubernetesNodeFileSystem}, and that the filesystem can list directories and
     * read files inside the running pod.
     *
     * <p>Before the {@code KubernetesNodeFileSystem} fix, {@code rootPathOf} threw
     * {@code FileSystemNotFoundException} for Kubernetes nodes because no NIO filesystem
     * was ever registered for {@code jco:} URIs in K8s mode.</p>
     *
     * <p>The test verifies three things:</p>
     * <ol>
     *   <li>{@code rootPathOf(id)} does not throw — the filesystem is registered at pod launch.</li>
     *   <li>Directory listing works: the {@code .jco} directory (created by the launcher) is visible
     *       from the path returned by {@code rootPathOf}.</li>
     *   <li>File reading works: a file written by the node lambda is readable via the filesystem.</li>
     * </ol>
     */
    @Test
    void testNodeFileSystemAccess() throws Exception
    {
        assumeKubernetesAvailable();

        KubernetesRemoteHostLauncher launcher = new KubernetesRemoteHostLauncher.Builder()
            .namespace(K8S_NAMESPACE)
            .image(K8S_IMAGE)
            .kubernetesConfig(Paths.get(System.getProperty("kubernetes.config.path")))
            .build();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(new SimpleNodeArrayConfiguration("worker-array")
                .node(new Node.Builder().withId("1").withHostname("k8s-fs-node-1").build()))
            .hostLauncher(launcher);

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray workerArray = cluster.nodeArray("worker-array");
            final int participantCount = 2; // 1 node + 1 test thread

            NodeArrayFuture future = workerArray.executeOnAll(tools ->
            {
                // Write a marker file to the pod's working directory ($HOME/.jco/<nodeId>/).
                // The test will read it back via the KubernetesNodeFileSystem to verify the
                // filesystem's newInputStream / file-read path works end-to-end.
                try (FileOutputStream fos = new FileOutputStream("jco-fs-test.txt"))
                {
                    fos.write("k8s-filesystem-works".getBytes(StandardCharsets.UTF_8));
                }
                tools.barrier("fs-barrier", participantCount).await();
            });

            cluster.tools().barrier("fs-barrier", participantCount).await(2, TimeUnit.MINUTES);
            future.get(2, TimeUnit.MINUTES);

            for (String id : workerArray.ids())
            {
                // 1. rootPathOf must not throw FileSystemNotFoundException (the bug that was fixed).
                Path rootPath = workerArray.rootPathOf(id);
                assertThat("rootPathOf must return a non-null path", rootPath, notNullValue());

                // 2. Directory listing: the .jco parent directory was created by the launcher
                //    (via 'mkdir -p <podHome>/.jco/<hostId>/.classpath').
                //    rootPath = <podHome>/.jco/<nodeId>/  →  rootPath/.. = <podHome>/.jco/
                Path jcoDir = rootPath.resolve("..");
                assertTrue(Files.isDirectory(jcoDir),
                    "Expected .jco directory to exist and be a directory: " + jcoDir);

                // 3. File reading: use the KubernetesNodeFileSystem to read the marker file
                //    written by the node lambda above. The file was written to the working directory,
                //    which is $HOME/.jco/<nodeId>/ for node processes, so we can read it via rootPath.
                Path markerPath = rootPath.resolve("jco-fs-test.txt");
                byte[] content = Files.readAllBytes(markerPath);
                assertThat("Marker file content must match what the node wrote",
                    new String(content, StandardCharsets.UTF_8),
                    equalTo("k8s-filesystem-works"));
            }
        }
    }

    @Test
    void testMultipleNodesExecution() throws Exception
    {
        assumeKubernetesAvailable();

        KubernetesRemoteHostLauncher launcher = new KubernetesRemoteHostLauncher.Builder()
                .namespace(K8S_NAMESPACE)
                .image(K8S_IMAGE)
                .kubernetesConfig(Paths.get(System.getProperty("kubernetes.config.path")))
                .build();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(new SimpleNodeArrayConfiguration("server-array")
                .node(new Node.Builder().withId("1").withHostname("k8s-server-1").withServicePort(8080).build()))
            .nodeArray(new SimpleNodeArrayConfiguration("client-array")
                .node(new Node.Builder().withId("2").withHostname("k8s-client-1").build()))
            .hostLauncher(launcher);

        try (Cluster cluster = new Cluster(cfg))
        {
            NodeArray serverArray = cluster.nodeArray("server-array");
            NodeArray clientArray = cluster.nodeArray("client-array");

            final int participantCount = 3; // 2 nodes + 1 test thread
            NodeArrayFuture serverFuture = serverArray.executeOnAll(tools ->
                tools.barrier("barrier", participantCount).await());

            NodeArrayFuture clientFuture = clientArray.executeOnAll(tools ->
                tools.barrier("barrier", participantCount).await());

            cluster.tools().barrier("barrier", participantCount).await(2, TimeUnit.MINUTES);
            serverFuture.get(2, TimeUnit.MINUTES);
            clientFuture.get(2, TimeUnit.MINUTES);
        }
    }
}
