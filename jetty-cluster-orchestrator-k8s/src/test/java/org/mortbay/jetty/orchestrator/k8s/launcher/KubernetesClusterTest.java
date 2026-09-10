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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.model.Bind;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mortbay.jetty.orchestrator.Cluster;
import org.mortbay.jetty.orchestrator.NodeArray;
import org.mortbay.jetty.orchestrator.NodeArrayFuture;
import org.mortbay.jetty.orchestrator.launcher.HostLauncher;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.k8s.configuration.K8sNode;
import org.mortbay.jetty.orchestrator.configuration.SimpleClusterConfiguration;
import org.mortbay.jetty.orchestrator.k8s.configuration.K8sNodeArrayConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end test of {@link KubernetesRemoteHostLauncher}.
 * <p>
 * A throwaway k3s cluster is started in Docker, so a running Docker daemon is all this needs;
 * without one the test is skipped. To use a cluster you already have:
 * <pre>
 *   mvn test -Dtest=KubernetesClusterTest \
 *       -Dkubernetes.config.path=$HOME/.kube/config \
 *       -Dk8s.namespace=default
 * </pre>
 * {@code k8s.image} is the image the node pods run, which needs a JRE and {@code tar}, and
 * {@code k3s.image} the one the throwaway cluster runs.
 * <p>
 * On a build machine that would rather not pull from Docker Hub every time, point
 * {@code jco.k3s.registry.mirror} at a proxy and the cluster pulls through it; add
 * {@code -Djco.k3s.registry.insecure=true} when the proxy serves a certificate that does not
 * match the name you reach it by.
 */
public class KubernetesClusterTest
{
    private static final String KUBECONFIG_PROPERTY = "kubernetes.config.path";
    private static final String K8S_IMAGE = System.getProperty("k8s.image", "eclipse-temurin:21-jre");
    private static final String K8S_NAMESPACE = System.getProperty("k8s.namespace", "default");
    private static final String K3S_IMAGE = System.getProperty("k3s.image", "rancher/k3s:v1.36.4-k3s1"); // v1.31.2-k3s1
    private static final String REGISTRY_MIRROR = System.getProperty("jco.k3s.registry.mirror");
    private static final boolean REGISTRY_INSECURE = Boolean.getBoolean("jco.k3s.registry.insecure");

    private static final Logger log = LoggerFactory.getLogger(KubernetesClusterTest.class);

    private static K3sContainer k3s;
    private static Path kubeConfig;

    @BeforeAll
    public static void startup() throws Exception
    {
        String configured = System.getProperty(KUBECONFIG_PROPERTY);
        if (configured != null && !configured.isEmpty() && Files.exists(Paths.get(configured)))
        {
            kubeConfig = Paths.get(configured);
            log.info("using the Kubernetes cluster of {}", kubeConfig);
            return;
        }

        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
            "no -D" + KUBECONFIG_PROPERTY + " given and Docker is not available to start a k3s cluster");

        k3s = new K3sContainer(DockerImageName.parse(K3S_IMAGE))
            // K3sContainer assumes a plain Docker host: it joins the daemon's cgroup namespace
            // and bind mounts /sys/fs/cgroup. Under docker-in-docker that gives k3s the outer
            // machine's cgroup tree, and every pod it starts then loses its own cgroup. A
            // private namespace behaves the same locally and inside a dind build agent.
            .withCreateContainerCmdModifier(cmd ->
            {
                cmd.getHostConfig().withCgroupnsMode("private");
                Bind[] binds = cmd.getHostConfig().getBinds();
                if (binds != null)
                {
                    cmd.getHostConfig().withBinds(Arrays.stream(binds)
                        .filter(bind -> !"/sys/fs/cgroup".equals(bind.getVolume().getPath()))
                        .toArray(Bind[]::new));
                }
            })
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("k3s")));
        if (REGISTRY_MIRROR != null && !REGISTRY_MIRROR.isEmpty())
        {
            log.info("pulling cluster images through {}", REGISTRY_MIRROR);
            k3s.withCopyToContainer(Transferable.of(registriesYaml(REGISTRY_MIRROR)), "/etc/rancher/k3s/registries.yaml");
        }
        k3s.start();
        kubeConfig = Files.createTempFile("jco-kubeconfig", ".yaml");
        Files.writeString(kubeConfig, k3s.getKubeConfigYaml());
        kubeConfig.toFile().deleteOnExit();
    }

    /**
     * A k3s registries.yaml sending Docker Hub pulls to a mirror. The node and ZooKeeper images
     * are pulled by the cluster's own containerd, so a mirror on the Docker daemon outside never
     * sees them, and the image names in the tests stay as they are.
     */
    private static String registriesYaml(String mirror)
    {
        StringBuilder yaml = new StringBuilder()
            .append("mirrors:\n")
            .append("  docker.io:\n")
            .append("    endpoint:\n")
            .append("      - \"").append(mirror).append("\"\n");
        if (REGISTRY_INSECURE)
        {
            yaml.append("configs:\n")
                .append("  \"").append(URI.create(mirror).getAuthority()).append("\":\n")
                .append("    tls:\n")
                .append("      insecure_skip_verify: true\n");
        }
        return yaml.toString();
    }

    @AfterAll
    public static void shutdown()
    {
        if (k3s != null)
            k3s.stop();
    }

    @Test
    void testBasicNodeExecution() throws Exception
    {
        HostLauncher launcher = new KubernetesRemoteHostLauncher.Builder().namespace(K8S_NAMESPACE)
                .image(K8S_IMAGE)
                .kubernetesConfig(kubeConfig)
                .build();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(new K8sNodeArrayConfiguration("worker-array")
                .node(new K8sNode.Builder().withId("1").withHostname("k8s-node-1").build()))
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
        HostLauncher launcher = new KubernetesRemoteHostLauncher.Builder()
            .namespace(K8S_NAMESPACE)
            .image(K8S_IMAGE)
            .kubernetesConfig(kubeConfig)
            .build();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(new K8sNodeArrayConfiguration("worker-array")
                .node(new K8sNode.Builder().withId("1").withHostname("k8s-fs-node-1").build()))
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
        HostLauncher launcher = new KubernetesRemoteHostLauncher.Builder()
                .namespace(K8S_NAMESPACE)
                .image(K8S_IMAGE)
                .kubernetesConfig(kubeConfig)
                .build();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(new K8sNodeArrayConfiguration("server-array")
                .node(new K8sNode.Builder().withId("1").withHostname("k8s-server-1").withServicePort(8080).build()))
            .nodeArray(new K8sNodeArrayConfiguration("client-array")
                .node(new K8sNode.Builder().withId("2").withHostname("k8s-client-1").build()))
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

    /**
     * Node selectors set on the array apply to every node, and a node setting the same key wins.
     * Both nodes are pinned to the cluster's only node, so they must actually get scheduled.
     */
    @Test
    void testNodeSelectors() throws Exception
    {
        HostLauncher launcher = new KubernetesRemoteHostLauncher.Builder()
            .namespace(K8S_NAMESPACE)
            .image(K8S_IMAGE)
            .kubernetesConfig(kubeConfig)
            .build();

        String schedulableNode = schedulableNodeName();

        K8sNodeArrayConfiguration workers = new K8sNodeArrayConfiguration("worker-array")
            .nodeSelector("kubernetes.io/hostname", "does-not-exist")
            .nodeSelector("kubernetes.io/os", "linux")
            // this node overrides the array-level hostname selector with a node that does exist
            .node(new K8sNode.Builder().withId("1").withHostname("k8s-selector-1")
                .withNodeSelector("kubernetes.io/hostname", schedulableNode)
                .build());

        // the merge must not have mutated the declared node
        assertThat(workers.nodes().iterator().next().getHostname(), equalTo("k8s-selector-1"));

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(workers)
            .hostLauncher(launcher);

        try (Cluster cluster = new Cluster(cfg))
        {
            final int participantCount = 2; // 1 node + 1 test thread
            NodeArrayFuture future = cluster.nodeArray("worker-array").executeOnAll(tools ->
                tools.barrier("barrier", participantCount).await());

            cluster.tools().barrier("barrier", participantCount).await(2, TimeUnit.MINUTES);
            future.get(2, TimeUnit.MINUTES);
        }
    }

    /**
     * Two nodes on one host share a pod, so asking for different pods must be rejected.
     */
    @Test
    void testConflictingSharedHostIsRejected() throws Exception
    {
        HostLauncher launcher = new KubernetesRemoteHostLauncher.Builder()
            .namespace(K8S_NAMESPACE)
            .image(K8S_IMAGE)
            .kubernetesConfig(kubeConfig)
            .build();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
            .jvm(new Jvm((fs, h) -> "java"))
            .nodeArray(new K8sNodeArrayConfiguration("worker-array")
                .node(new K8sNode.Builder().withId("1").withHostname("k8s-shared").withServicePort(8080).build())
                .node(new K8sNode.Builder().withId("2").withHostname("k8s-shared").withServicePort(9090).build()))
            .hostLauncher(launcher);

        Exception e = assertThrows(Exception.class, () -> new Cluster(cfg));
        assertThat(rootCauseMessage(e), containsString("different service ports"));
    }

    private static String rootCauseMessage(Throwable t)
    {
        Throwable cause = t;
        while (cause.getCause() != null)
            cause = cause.getCause();
        return String.valueOf(cause.getMessage());
    }

    private static String schedulableNodeName()
    {
        // Config.fromKubeconfig, not withConfig(InputStream): the latter ignores the file and
        // autoconfigures from the environment instead.
        try (KubernetesClient client = new KubernetesClientBuilder()
            .withConfig(Config.fromKubeconfig(Files.readString(kubeConfig))).build())
        {
            return client.nodes().list().getItems().get(0).getMetadata().getName();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot list the nodes of the Kubernetes cluster", e);
        }
    }
}
