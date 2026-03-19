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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.mortbay.jetty.orchestrator.configuration.ClusterConfiguration;
import org.mortbay.jetty.orchestrator.configuration.HostLauncher;
import org.mortbay.jetty.orchestrator.configuration.LocalHostLauncher;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.configuration.NodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.rpc.NodeProcess;
import org.mortbay.jetty.orchestrator.rpc.RpcClient;
import org.mortbay.jetty.orchestrator.rpc.command.CheckNodeCommand;
import org.mortbay.jetty.orchestrator.rpc.command.KillNodeCommand;
import org.mortbay.jetty.orchestrator.rpc.command.SpawnNodeCommand;
import org.mortbay.jetty.orchestrator.util.IOUtil;
import org.mortbay.jetty.orchestrator.util.ZooKeeperClient;
import org.mortbay.jetty.orchestrator.util.ZooKeeperServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Cluster implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(Cluster.class);

    private final String id;
    private final ClusterConfiguration configuration;
    private final LocalHostLauncher localHostLauncher = new LocalHostLauncher();
    private final HostLauncher hostLauncher;
    private final Map<String, NodeArray> nodeArrays = new HashMap<>(); // keyed by NodeArrayId
    private final Map<GlobalNodeId, Host> hosts = new HashMap<>(); // keyed by HostId
    private final Timer hostsCheckerTimer = new Timer();
    private ZooKeeperServer zkServer;
    private ZooKeeperClient zkClient;
    private ClusterTools clusterTools;

    public Cluster(ClusterConfiguration configuration) throws Exception
    {
        this(generateId(), configuration);
    }

    private static String generateId()
    {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String className = stackTrace[3].getClassName();
        String simpleClassName = className.substring(className.lastIndexOf('.') + 1);
        return simpleClassName + "_" + stackTrace[3].getMethodName();
    }

    public Cluster(String id, ClusterConfiguration configuration) throws Exception
    {
        this.id = id;
        this.configuration = configuration;
        this.hostLauncher = configuration.hostLauncher();
        try
        {
            init();
        }
        catch (Exception e)
        {
            close();
            throw e;
        }
    }

    private void init() throws Exception
    {
        zkServer = new ZooKeeperServer();
        String connectString = zkServer.getConnectString();
        zkClient = new ZooKeeperClient(connectString);
        clusterTools = new ClusterTools(zkClient, new GlobalNodeId(id, LocalHostLauncher.HOSTNAME));

        // start all host nodes
        List<Node> nodes = new ArrayList<>(
            configuration.nodeArrays().stream()
                .flatMap(cfg -> {
                    // node selectors at array level
                    Map<String, String> nodeArraySelectors = cfg.nodeSelectors();
                    // but each node will be to override it
                    return cfg.nodes().stream().map(node -> {
                        Map<String, String> nodeSelectors = new HashMap<>(nodeArraySelectors);
                        nodeSelectors.putAll(node.getNodeSelectors());
                        return new Node(node.getId(), node.getHostname(), nodeSelectors);
                    });
                })
                .collect(Collectors.toMap(
                    Node::getHostname,
                    n -> n,
                    (a, b) -> a,          // keep first occurrence for duplicate hostnames
                    LinkedHashMap::new))  // preserve insertion order
                .values());
        List<Future<Map.Entry<GlobalNodeId, String>>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try
        {
            for (Node node : nodes)
            {
                GlobalNodeId globalNodeId = new GlobalNodeId(id, node.getHostname());
                HostLauncher launcher = node.getHostname().equals(LocalHostLauncher.HOSTNAME) ? localHostLauncher : hostLauncher;
                if (launcher == null)
                    throw new IllegalStateException("No configured host launcher to start node on " + node.getHostname());
                futures.add(executor.submit(() ->
                {
                    long healthCheckTimeout = configuration.healthCheckTimeout();
                    String remoteConnectString = launcher.launch(globalNodeId, node, connectString, Long.toString(healthCheckTimeout));
                    return new AbstractMap.SimpleImmutableEntry<>(globalNodeId, remoteConnectString);
                }));
            }
        }
        finally
        {
            executor.shutdown();
        }
        for (Future<Map.Entry<GlobalNodeId, String>> future : futures)
        {
            Map.Entry<GlobalNodeId, String> entry = future.get();
            GlobalNodeId globalNodeId = entry.getKey();
            String remoteConnectString = entry.getValue();
            hosts.put(globalNodeId, new Host(globalNodeId, new RpcClient(zkClient, globalNodeId), remoteConnectString));
        }

        // start heath check timer
        long healthCheckDelay = configuration.healthCheckDelay();
        hostsCheckerTimer.schedule(new TimerTask() {
            @Override
            public void run()
            {
                for (Host host : hosts.values())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Checking health of host {}", host);
                    host.check();
                }
            }
        }, healthCheckDelay, healthCheckDelay);

        // start all worker nodes
        for (NodeArrayConfiguration nodeArrayConfig : configuration.nodeArrays())
        {
            Map<String, NodeArray.Node> nodeArrayNodes = new HashMap<>();
            for (Node nodeConfig : nodeArrayConfig.nodes())
            {
                GlobalNodeId globalNodeId = new GlobalNodeId(id, nodeArrayConfig, nodeConfig);
                Host host = hosts.get(globalNodeId.getHostGlobalId());
                try
                {
                    NodeProcess remoteProcess = (NodeProcess)host.rpcClient.call(new SpawnNodeCommand(nodeArrayConfig.jvm(), globalNodeId.getHostname(), globalNodeId.getHostId(), globalNodeId.getNodeId(), host.remoteConnectString, Long.toString(configuration.healthCheckTimeout())), 10, TimeUnit.SECONDS);
                    NodeArray.Node node = new NodeArray.Node(globalNodeId, remoteProcess, new RpcClient(zkClient, globalNodeId));
                    host.nodes.add(node);
                    nodeArrayNodes.put(nodeConfig.getId(), node);
                }
                catch (Exception e)
                {
                    throw new Exception("Error spawning node '" + globalNodeId.getHostId() + "'", e);
                }
            }
            nodeArrays.put(nodeArrayConfig.id(), new NodeArray(nodeArrayNodes));
        }
    }

    public ClusterTools tools()
    {
        return clusterTools;
    }

    @Override
    public void close()
    {
        hostsCheckerTimer.cancel();
        hosts.values().forEach(IOUtil::close);
        hosts.clear();
        nodeArrays.clear();
        IOUtil.close(hostLauncher);
        IOUtil.close(localHostLauncher);
        IOUtil.close(zkClient);
        IOUtil.close(zkServer);
    }

    public NodeArray nodeArray(String id)
    {
        return nodeArrays.get(id);
    }

    private static class Host implements AutoCloseable
    {
        private final GlobalNodeId globalNodeId;
        private final RpcClient rpcClient;
        private final String remoteConnectString;
        private final List<NodeArray.Node> nodes = new CopyOnWriteArrayList<>();

        private Host(GlobalNodeId globalNodeId, RpcClient rpcClient, String remoteConnectString)
        {
            this.globalNodeId = globalNodeId;
            this.rpcClient = rpcClient;
            this.remoteConnectString = remoteConnectString;
        }

        private void check()
        {
            List<String> unsaneHostIds = new ArrayList<>();
            Exception failure = null;
            for (NodeArray.Node node : nodes)
            {
                NodeProcess nodeProcess = node.getNodeProcess();
                try
                {
                    if (LOG.isDebugEnabled())
                       LOG.debug("client checking node {}", node);
                    // Ask the host node to check the spawned node.
                    rpcClient.call(new CheckNodeCommand(nodeProcess), 10, TimeUnit.SECONDS);
                    // Ask the spawned node to check itself. Must happen to create
                    // a heartbeat for the health checks.
                    node.selfCheck();
                }
                catch (Exception e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Host {} failed check of {}", globalNodeId.getHostId(), nodeProcess, e);
                    unsaneHostIds.add(String.format(" Host %s failed check of %s", globalNodeId.getHostId(), nodeProcess));
                    if (failure == null)
                        failure = e;
                    else
                        failure.addSuppressed(e);
                }
            }
            if (!unsaneHostIds.isEmpty())
            {
                LOG.error("Forcibly closing the cluster as {} host(s) failed its/their health check:\n{}", unsaneHostIds.size(), String.join("\n", unsaneHostIds), failure);
                close();
            }
        }

        @Override
        public void close()
        {
            for (NodeArray.Node node : nodes)
            {
                NodeProcess nodeProcess = node.getNodeProcess();
                try
                {
                    rpcClient.call(new KillNodeCommand(nodeProcess), 10, TimeUnit.SECONDS);
                }
                catch (Exception e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Error closing {}", nodeProcess, e);
                }
            }
            IOUtil.close(rpcClient);
            nodes.forEach(IOUtil::close);
            nodes.clear();
        }

        @Override
        public String toString()
        {
            return "Host{" +
                "globalNodeId='" + globalNodeId + '\'' +
                "remoteConnectString='" + remoteConnectString + '\'' +
                ", nodes=" + nodes +
                '}';
        }
    }
}
