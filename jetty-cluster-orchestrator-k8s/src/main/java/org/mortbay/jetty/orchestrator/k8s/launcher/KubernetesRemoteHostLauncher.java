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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.mortbay.jetty.orchestrator.configuration.HostLauncher;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.configuration.JvmDependent;
import org.mortbay.jetty.orchestrator.localhost.launcher.LocalHostLauncher;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.rpc.NodeProcess;
import org.mortbay.jetty.orchestrator.util.IOUtil;
import org.mortbay.jetty.orchestrator.util.StreamCopier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible to start Kubernetes nodes.
 * It's not thread-safe, but it is expected that the cluster controller will call it in a single-threaded manner during cluster startup and shutdown.
 * It maintains a map of launched pods so that it can clean them up on close(), and to prevent multiple pods from being launched for the same host.
 * It manages a ZooKeeper pod and ClusterIP service for cluster coordination, with the controller connecting via LocalPortForward and worker pods using cluster-internal DNS.
 * The launcher creates a headless service to give the pods stable DNS names, and registers NIO filesystems for each pod so that ReportUtil.download() can access pod files via jco: URIs.
 * The launcher copies the local classpath to each pod under $HOME/.jco/classpath, and constructs a command line that runs the NodeProcess main class with that classpath and the appropriate JVM options.
 * The launcher uses the fabric8 Kubernetes client library to interact with the cluster.
 */
public class KubernetesRemoteHostLauncher implements HostLauncher, JvmDependent
{
    private static final Logger LOG = LoggerFactory.getLogger(KubernetesRemoteHostLauncher.class);
    private static final String ZK_IMAGE = System.getProperty("zookeeper.image.name", "zookeeper:3.9");

    private final Map<String, PodHolder> pods = new ConcurrentHashMap<>();
    private final String launcherId = UUID.randomUUID().toString().substring(0, 8);
    private String namespace = "default";
    private String image;
    private Jvm jvm;
    private KubernetesClient client;
    private LocalPortForward zkPortForward;
    private String zkPodName;
    private String zkServiceName;
    private final String headlessServiceName = "jco-nodes-" + launcherId;
    private boolean headlessServiceCreated = false;

    KubernetesRemoteHostLauncher(String namespace, String image, Path kubernetesConfig, Map<String, String> namespaceLabels) throws IOException {
        this.namespace = namespace;
        this.image = image;
        try(InputStream inputStream = Files.newInputStream(kubernetesConfig)) {
            this.client = new KubernetesClientBuilder().withConfig(inputStream).build();
        }
        Namespace ns = this.client.namespaces().withName(namespace).get(); // validate namespace exists
        if(ns == null) {
            LOG.debug("specified namespace '{}' does not exist; creating it", namespace);
            ns = new NamespaceBuilder()
                    .withNewMetadata()
                    .withName(this.namespace)
                    .withLabels(namespaceLabels)
                    .endMetadata()
                    .build();

            // Create it in the cluster
            client.namespaces().resource(ns).create();
        }
    }

    public static class Builder {

        private String namespace;
        private String image;
        private Path kubernetesConfig;
        private final Map<String, String> namespaceLabels = new HashMap<>();

        public Builder namespace(String namespace)
        {
            this.namespace = namespace;
            return this;
        }

        public Builder image(String image)
        {
            this.image = image;
            return this;
        }
        
        public Builder withNamespaceLabel(String key, String value)
        {
            this.namespaceLabels.put(key, value);
            return this;
        }
        
        public Builder withNamespaceLabels(Map<String, String> labels)
        {
            this.namespaceLabels.putAll(labels);
            return this;
        }

        public Builder kubernetesConfig(Path kubernetesConfig)
        {
            this.kubernetesConfig = kubernetesConfig;
            return this;
        }

        public KubernetesRemoteHostLauncher build() throws IOException {

            return new KubernetesRemoteHostLauncher(Objects.requireNonNull(namespace, "Namespace cannot be null"),
                    Objects.requireNonNull(image, "Image cannot be null"),
                    Objects.requireNonNull(kubernetesConfig, "Kubernetes config path cannot be null"),
                    new HashMap<>(namespaceLabels));
        }
    }


    @Override
    public Jvm jvm()
    {
        return jvm;
    }

    @Override
    public KubernetesRemoteHostLauncher jvm(Jvm jvm)
    {
        this.jvm = jvm;
        return this;
    }

    private void ensureHeadlessService()
    {
        if (headlessServiceCreated)
            return;
        Service svc = new ServiceBuilder()
            .withNewMetadata()
                .withName(headlessServiceName)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withClusterIP("None")
            .endSpec()
            .build();
        try {
            client.services().inNamespace(namespace).resource(svc).create();
        } catch (KubernetesClientException e)
        {
            if(e.getCode() == 429)
            {
                LOG.debug("Headless service {} already exists, continuing", headlessServiceName);
            }
        }
        headlessServiceCreated = true;
    }

    private String podHostnameFor(GlobalNodeId nodeId)
    {
        String hostname = nodeId.getHostname();
        // If hostname is a full DNS name, use only the first label — this matches what podDnsNameFor() computes.
        int dotIdx = hostname.indexOf('.');
        String label = (dotIdx >= 0) ? hostname.substring(0, dotIdx) : sanitizePodName(hostname);
        // Enforce the 63-char Kubernetes DNS label limit
        if (label.length() > 63)
            throw new IllegalArgumentException("Hostname '" + hostname + "' sanitizes to '" + label + 
                "' which exceeds Kubernetes DNS label limit of 63 characters. " +
                "Use shorter hostnames to avoid potential name collisions.");
        return label;
    }

    @Override
    public String initialize() throws Exception
    {
        LOG.debug("launching ZooKeeper pod for cluster coordination");

        zkPodName = "jco-zk-" + launcherId;
        zkServiceName = "jco-zk-" + launcherId;

        // Create ZK pod with label for service selector
        Pod zkPod = new PodBuilder()
            .withNewMetadata()
                .withName(zkPodName)
                .withNamespace(namespace)
                .withLabels(Map.of("app", zkPodName))
            .endMetadata()
            .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                    .withName("zookeeper")
                    .withImage(ZK_IMAGE)
                    .addNewPort().withContainerPort(2181).endPort()
                .endContainer()
            .endSpec()
            .build();
        client.pods().inNamespace(namespace).resource(zkPod).create();

        // Create ClusterIP service
        Service zkService = new ServiceBuilder()
            .withNewMetadata()
                .withName(zkServiceName)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withSelector(Map.of("app", zkPodName))
                .addNewPort()
                    .withPort(2181)
                .endPort()
            .endSpec()
            .build();
        client.services().inNamespace(namespace).resource(zkService).create();

        // Wait for ZK pod to be ready - 2 minutes allows time for image pulling and container startup
        client.pods().inNamespace(namespace).withName(zkPodName).waitUntilReady(2, TimeUnit.MINUTES);

        // Open local port-forward to ZK pod
        this.zkPortForward = client.pods().inNamespace(namespace).withName(zkPodName).portForward(2181);
        return this.zkPortForward.getLocalAddress().getHostAddress() + ":" + this.zkPortForward.getLocalPort();
    }

    @Override
    public void close()
    {
        pods.values().forEach(IOUtil::close);
        pods.clear();
        IOUtil.close(zkPortForward);
        if (zkPodName != null && client != null)
        {
            try
            {
                client.pods().inNamespace(namespace).withName(zkPodName).withGracePeriod(0).delete();
            }
            catch (Exception e)
            {
                LOG.debug("error deleting ZK pod {}", zkPodName, e);
            }
        }
        if (zkServiceName != null && client != null)
        {
            try
            {
                client.services().inNamespace(namespace).withName(zkServiceName).delete();
            }
            catch (Exception e)
            {
                LOG.debug("error deleting ZK service {}", zkServiceName, e);
            }
        }
        if (headlessServiceCreated && client != null)
        {
            try
            {
                client.services().inNamespace(namespace).withName(headlessServiceName).delete();
            }
            catch (Exception e)
            {
                LOG.debug("error deleting headless service {}", headlessServiceName, e);
            }
        }
        IOUtil.close(client);
        client = null;
    }

    @Override
    public String launch(GlobalNodeId globalNodeId, Node node, String connectString, String... extraArgs) throws Exception
    {
        long start = System.nanoTime();
        GlobalNodeId nodeId = globalNodeId.getHostGlobalId();
        LOG.debug("start launch of k8s pod for node: {}", node);
        if (!nodeId.equals(globalNodeId))
            throw new IllegalArgumentException("node id is not the one of a host node");
        if (pods.putIfAbsent(nodeId.getHostname(), PodHolder.NULL) != null)
            throw new IllegalArgumentException("k8s launcher already launched pod for host " + nodeId.getHostname());

        String podName = sanitizePodName(nodeId.getHostId());
        ExecWatch execWatch = null;
        try
        {
            String remoteConnectString = zkServiceName + ":2181";

            ensureHeadlessService();

            String podHostname = podHostnameFor(nodeId);

            Map<String, String> nodeSelectors = node.getNodeSelectors();

            // Merge hostname label with custom node labels
            Map<String, String> podLabels = new HashMap<>();
            podLabels.put("hostname", node.getHostname());
            podLabels.putAll(node.getLabels());

            Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withNamespace(namespace)
                    .withLabels(podLabels)
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .withHostname(podHostname)
                    .withSubdomain(headlessServiceName)
                    .withNodeSelector(nodeSelectors.isEmpty() ? null : nodeSelectors)
                    .addNewContainer()
                        .withName("node")
                        .withImage(image)
                        .withCommand("sleep", "infinity")
                    .endContainer()
                .endSpec()
                .build();
            client.pods().inNamespace(namespace).resource(pod).create();

            // Wait for pod to be ready - 2 minutes allows time for image pulling and container startup
            client.pods().inNamespace(namespace).withName(podName).waitUntilReady(2, TimeUnit.MINUTES);

            Service nodeService = null;

            // we need to create a service mapping port
            if(node.getServicePort()>0) {
                nodeService = new ServiceBuilder()
                        .withNewMetadata()
                        .withName(node.getHostname())
                        .withNamespace(namespace)
                        .endMetadata()
                        .withNewSpec()
                        .withSelector(Map.of("hostname", node.getHostname()))
                        .addNewPort()
                        .withName("service-port" + node.getServicePort())
                        .withPort(node.getServicePort())
                        .withTargetPort(new IntOrString(node.getServicePort()))
                        .endPort()
                        .endSpec()
                        .build();
                client.services().inNamespace(namespace).resource(nodeService).create();

                // Wait for service endpoints to ensure DNS propagation
                // This prevents "Connection refused" errors when pods try to connect
                waitForServiceEndpoints(node.getHostname(), 30);

                LOG.info("Created service {} for node {} on port {}", nodeId.getHostname(), node.getId(), node.getServicePort());
            }


            LOG.info("pod {} is ready, launching node process for host {}", podName, nodeId.getHostname());

            String homeOutput = runAndCollect(client, namespace, podName, "sh", "-c", "echo $HOME").trim();
            String podHome = homeOutput.isEmpty() ? "/root" : homeOutput;

            // Register NIO filesystem so ReportUtil.download() can walk the pod via jco: URIs.
            URI fsUri = URI.create(NodeFileSystemProvider.PREFIX + ":" + nodeId.getHostId());
            Map<String, Object> fsEnv = new HashMap<>();
            fsEnv.put(KubernetesClient.class.getName(), client);
            fsEnv.put(NodeFileSystemProvider.K8S_NAMESPACE_ENV_PROPERTY, namespace);
            fsEnv.put(NodeFileSystemProvider.K8S_POD_NAME_ENV_PROPERTY, podName);
            fsEnv.put(NodeFileSystemProvider.K8S_POD_HOME_ENV_PROPERTY, podHome);
            FileSystems.newFileSystem(fsUri, fsEnv);

            String classpathDir = podHome + "/." + NodeFileSystemProvider.PREFIX + "/" + nodeId.getHostId() + "/" + NodeProcess.CLASSPATH_FOLDER_NAME;
            runAndWait(client, namespace, podName, "mkdir", "-p", classpathDir);

            List<String> remoteClasspathEntries = new ArrayList<>();
            String[] classpathEntries = System.getProperty("java.class.path").split(File.pathSeparator);
            for (String classpathEntry : classpathEntries)
            {
                File cpFile = new File(classpathEntry);
                String cpFileName = cpFile.getName();
                if (!cpFileName.endsWith(".jar") && !cpFileName.endsWith(".JAR"))
                    remoteClasspathEntries.add(classpathDir + "/" + cpFileName);
                if (cpFile.isDirectory())
                    copyDirToPod(client, namespace, podName, classpathDir, cpFile, 1);
                else
                    copyFileToPod(client, namespace, podName, classpathDir, cpFileName, cpFile);
            }
            remoteClasspathEntries.add(classpathDir + "/*");

            Jvm effectiveJvm = (jvm != null) ? jvm : new Jvm((fs, h) -> "java");
            List<String> cmdLine = buildCommandLine(effectiveJvm, remoteClasspathEntries, nodeId.getHostId(), nodeId.getHostname(), remoteConnectString, extraArgs);

            execWatch = client.pods().inNamespace(namespace).withName(podName)
                .redirectingOutput()
                .redirectingError()
                .exec(cmdLine.toArray(new String[0]));

            new StreamCopier(execWatch.getOutput(), System.out, true).spawnDaemon(nodeId.getHostname() + "-stdout");
            new StreamCopier(execWatch.getError(), System.err, true).spawnDaemon(nodeId.getHostname() + "-stderr");

            PodHolder holder = new PodHolder(nodeId, podName, execWatch, podHome, client, namespace, nodeService);
            pods.put(nodeId.getHostname(), holder);
            LOG.debug("start node {} with remoteConnectString {}", node, remoteConnectString);
            return remoteConnectString;
        }
        catch (Exception e)
        {
            pods.remove(nodeId.getHostname());
            IOUtil.close(execWatch);
            try
            {
                client.pods().inNamespace(namespace).withName(podName).delete();
            }
            catch (Exception deleteEx)
            {
                LOG.debug("error deleting pod {} during error cleanup", podName, deleteEx);
            }
            throw new Exception("Error launching pod for host '" + nodeId.getHostname() + "'", e);
        }
        finally
        {
            LOG.debug("time to start pod for host {}: {}ms", nodeId.getHostname(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    /**
     * Waits for a Kubernetes service to have endpoints, indicating that DNS propagation
     * is complete and the service is bound to pods.
     *
     * @param serviceName the name of the service to wait for
     * @param timeoutSeconds maximum time to wait in seconds
     * @throws Exception if the service endpoints don't become ready within the timeout,
     *                   or if there's an error accessing the Kubernetes API
     */
    private void waitForServiceEndpoints(String serviceName, int timeoutSeconds) throws Exception
    {
        long startTime = System.nanoTime();
        long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        int attempt = 0;
        long sleepMillis = 100;

        LOG.debug("Waiting for service '{}' endpoints (timeout: {}s)", serviceName, timeoutSeconds);

        while (true)
        {
            attempt++;
            long elapsed = System.nanoTime() - startTime;

            if (elapsed > timeoutNanos)
            {
                throw new Exception(String.format(
                    "Timeout waiting for service '%s' endpoints after %d seconds. " +
                    "DNS propagation may be delayed or service has no matching pods.",
                    serviceName, timeoutSeconds));
            }

            try
            {
                var endpoints = client.endpoints()
                    .inNamespace(namespace)
                    .withName(serviceName)
                    .get();

                if (endpoints != null &&
                    endpoints.getSubsets() != null &&
                    !endpoints.getSubsets().isEmpty())
                {
                    boolean hasAddresses = endpoints.getSubsets().stream()
                        .anyMatch(subset -> subset.getAddresses() != null &&
                                           !subset.getAddresses().isEmpty());

                    if (hasAddresses)
                    {
                        long totalMillis = TimeUnit.NANOSECONDS.toMillis(elapsed);
                        LOG.info("Service '{}' endpoints ready after {} attempts ({}ms)",
                            serviceName, attempt, totalMillis);
                        return;
                    }
                }

                if (attempt % 10 == 0)
                {
                    LOG.debug("Service '{}' endpoints not ready (attempt {}, elapsed: {}s)",
                        serviceName, attempt, TimeUnit.NANOSECONDS.toSeconds(elapsed));
                }
            }
            catch (KubernetesClientException e)
            {
                if (attempt == 1)
                {
                    LOG.debug("Service '{}' not accessible yet: {}", serviceName, e.getMessage());
                }
            }

            Thread.sleep(sleepMillis);
            sleepMillis = Math.min(sleepMillis * 2, 2000);
        }
    }

    private static List<String> buildCommandLine(Jvm jvm, List<String> classpathEntries, String nodeId, String hostname, String connectString, String... extraArgs)
    {
        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(jvm.executable(null, hostname));
        cmdLine.addAll(filterOutEmptyStrings(jvm.getOpts()));
        cmdLine.add("-classpath");
        cmdLine.add(String.join(":", classpathEntries));
        cmdLine.add(NodeProcess.class.getName());
        cmdLine.add(nodeId);
        cmdLine.add(connectString);
        cmdLine.addAll(List.of(extraArgs));
        return cmdLine;
    }

    private static List<String> filterOutEmptyStrings(List<String> opts)
    {
        return opts.stream().filter(s -> !s.trim().isEmpty()).collect(Collectors.toList());
    }

    private static void copyFileToPod(KubernetesClient client, String namespace, String podName, String destDir, String filename, File localFile) throws Exception
    {
        String destPath = destDir + "/" + filename;
        String parentDir = destPath.substring(0, destPath.lastIndexOf('/'));
        runAndWait(client, namespace, podName, "mkdir", "-p", parentDir);
        try (InputStream is = new FileInputStream(localFile))
        {
            client.pods().inNamespace(namespace).withName(podName).file(destPath).upload(is);
        }
    }

    private static void copyDirToPod(KubernetesClient client, String namespace, String podName, String destDir, File cpFile, int depth) throws Exception
    {
        File[] files = cpFile.listFiles();
        if (files == null)
            return;

        for (File file : files)
        {
            if (file.isDirectory())
            {
                copyDirToPod(client, namespace, podName, destDir, file, depth + 1);
            }
            else
            {
                String filename = file.getName();
                File currentFile = file;
                for (int i = 0; i < depth; i++)
                {
                    currentFile = currentFile.getParentFile();
                    filename = currentFile.getName() + "/" + filename;
                }
                copyFileToPod(client, namespace, podName, destDir, filename, file);
            }
        }
    }

    private static String runAndCollect(KubernetesClient client, String namespace, String podName, String... command) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                .writingOutput(out)
                .exec(command))
        {
            watch.exitCode().get(30, TimeUnit.SECONDS);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void runAndWait(KubernetesClient client, String namespace, String podName, String... command) throws Exception
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                .writingOutput(out)
                .writingError(err)
                .exec(command))
        {
            Integer exitCode = watch.exitCode().get(30, TimeUnit.SECONDS);
            if (exitCode != null && exitCode != 0)
                throw new IOException("Command " + Arrays.toString(command) + " failed with exit code " + exitCode + ": " + err.toString(StandardCharsets.UTF_8));
        }
    }

    public static String sanitizePodName(String hostId)
    {
        return hostId.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }


    private static class PodHolder implements AutoCloseable
    {
        private static final PodHolder NULL = new PodHolder(null, null, null, null, null, null, null);

        private final GlobalNodeId nodeId;
        private final String podName;
        private final ExecWatch execWatch;
        private final String podHome;
        private final KubernetesClient client;
        private final String namespace;
        private final Service nodeService;

        private PodHolder(GlobalNodeId nodeId, String podName, ExecWatch execWatch, String podHome, KubernetesClient client, String namespace, Service nodeService)
        {
            this.nodeId = nodeId;
            this.podName = podName;
            this.execWatch = execWatch;
            this.podHome = podHome;
            this.client = client;
            this.namespace = namespace;
            this.nodeService = nodeService;
        }

        @Override
        public void close()
        {
            if (nodeId != null)
            {
                try
                {
                    URI fsUri = URI.create(NodeFileSystemProvider.PREFIX + ":" + nodeId.getHostId());
                    FileSystems.getFileSystem(fsUri).close();
                }
                catch (Exception e)
                {
                    LOG.debug("error closing filesystem for pod {}", podName, e);
                }
            }
            IOUtil.close(execWatch);
            if (!LocalHostLauncher.skipDiskCleanup() && podName != null && podHome != null)
            {
                try
                {
                    runAndWait(client, namespace, podName, "rm", "-rf",
                        podHome + "/." + NodeFileSystemProvider.PREFIX + "/" + nodeId.getClusterId());
                }
                catch (Exception e)
                {
                    LOG.debug("error deleting temp files in pod {}", podName, e);
                }
            }
            if (podName != null)
            {
                try
                {
                    client.pods().inNamespace(namespace).withName(podName).delete();
                }
                catch (Exception e)
                {
                    LOG.debug("error deleting pod {}", podName, e);
                }
            }
            if(nodeService != null)
            {
                try
                {
                    client.services().inNamespace(namespace).withName(nodeService.getMetadata().getName()).delete();
                } 
                catch (Exception e)
                {
                    LOG.debug("error deleting service {} for node {}", nodeService.getMetadata().getName(), nodeId.getHostname(), e);
                }
            }
        }
    }
}
