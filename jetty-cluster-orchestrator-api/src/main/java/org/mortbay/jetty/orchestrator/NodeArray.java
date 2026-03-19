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

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.mortbay.jetty.orchestrator.configuration.LocalHostLauncher;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;
import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.rpc.NodeProcess;
import org.mortbay.jetty.orchestrator.rpc.RpcClient;
import org.mortbay.jetty.orchestrator.rpc.command.CheckNodeCommand;
import org.mortbay.jetty.orchestrator.rpc.command.ExecuteNodeJobCommand;
import org.mortbay.jetty.orchestrator.util.IOUtil;

public class NodeArray
{
    private final Map<String, Node> nodes;

    NodeArray(Map<String, Node> nodes)
    {
        this.nodes = nodes;
    }

    public String hostnameOf(String id)
    {
        Node node = nodes.get(id);
        if (node == null)
            throw new IllegalArgumentException("No such node with ID " + id);
        return node.globalNodeId.getHostname();
    }

    public Path rootPathOf(String id)
    {
        Node node = nodes.get(id);
        if (node == null)
            throw new IllegalArgumentException("No such node with ID " + id);
        if (node.globalNodeId.isLocal())
        {
            return LocalHostLauncher.rootPathOf(node.globalNodeId.getNodeId()).toPath();
        }
        else
        {
            URI uri = URI.create(NodeFileSystemProvider.PREFIX + ":" + node.globalNodeId.getHostId() + "!/." + NodeFileSystemProvider.PREFIX + "/" + node.globalNodeId.getNodeId());
            return Paths.get(uri);
        }
    }

    public Set<String> ids()
    {
        return nodes.keySet();
    }

    public NodeArrayFuture executeOn(String id, NodeJob nodeJob)
    {
        Node node = nodes.get(id);
        if (node == null)
            throw new IllegalArgumentException("No such node with ID " + id);

        Map<String, CompletableFuture<Object>> futures = new HashMap<>();
        try
        {
            CompletableFuture<Object> future = node.rpcClient.callAsync(new ExecuteNodeJobCommand(nodeJob));
            futures.put(id, future);
        }
        catch (Exception e)
        {
            CompletableFuture<Object> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            futures.put(id, future);
        }
        return new NodeArrayFuture(futures);
    }

    public NodeArrayFuture executeOn(Set<String> ids, NodeJob nodeJob)
    {
        Set<String> missingIds = new HashSet<>(nodes.keySet());
        ids.forEach(missingIds::remove);
        if (!missingIds.isEmpty())
            throw new IllegalArgumentException("No such node with ID " + missingIds);

        Map<String, CompletableFuture<Object>> futures = new HashMap<>();
        for (String id : ids)
        {
            Node node = nodes.get(id);
            try
            {
                CompletableFuture<Object> future = node.rpcClient.callAsync(new ExecuteNodeJobCommand(nodeJob));
                futures.put(id, future);
            }
            catch (Exception e)
            {
                CompletableFuture<Object> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                futures.put(id, future);
            }
        }
        return new NodeArrayFuture(futures);
    }

    public NodeArrayFuture executeOnAll(NodeJob nodeJob)
    {
        Map<String, CompletableFuture<Object>> futures = new HashMap<>();
        for (Map.Entry<String, Node> entry : nodes.entrySet())
        {
            String nodeId = entry.getKey();
            Node node = entry.getValue();
            try
            {
                CompletableFuture<Object> future = node.rpcClient.callAsync(new ExecuteNodeJobCommand(nodeJob));
                futures.put(nodeId, future);
            }
            catch (Exception e)
            {
                CompletableFuture<Object> future = new CompletableFuture<>();
                future.completeExceptionally(e);
                futures.put(nodeId, future);
            }
        }
        return new NodeArrayFuture(futures);
    }

    static class Node implements AutoCloseable
    {
        private final GlobalNodeId globalNodeId;
        private final NodeProcess nodeProcess;
        private final RpcClient rpcClient;

        Node(GlobalNodeId globalNodeId, NodeProcess nodeProcess, RpcClient rpcClient)
        {
            this.globalNodeId = globalNodeId;
            this.nodeProcess = nodeProcess;
            this.rpcClient = rpcClient;
        }

        public NodeProcess getNodeProcess()
        {
            return nodeProcess;
        }

        void selfCheck() throws Exception
        {
            rpcClient.callAsync(new CheckNodeCommand(nodeProcess)).get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close()
        {
            IOUtil.close(rpcClient);
        }

        @Override
        public String toString()
        {
            return globalNodeId.getNodeId();
        }
    }
}
