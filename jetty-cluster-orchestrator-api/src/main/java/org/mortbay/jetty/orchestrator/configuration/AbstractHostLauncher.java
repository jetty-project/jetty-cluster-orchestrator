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

package org.mortbay.jetty.orchestrator.configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;

/**
 * Base class doing what every {@link HostLauncher} has to do, so that launchers only
 * have to describe how one host is started.
 *
 * <p>It maps nodes onto hosts (several nodes, possibly from different node arrays,
 * share a host when they share a hostname), starts the hosts of a node array in
 * parallel, and remembers what it started so a host is never launched twice.</p>
 */
public abstract class AbstractHostLauncher implements HostLauncher
{
    private final Map<String, HostLaunch> launchedHosts = new ConcurrentHashMap<>();
    private final ExecutorService launchPool = Executors.newCachedThreadPool(new LauncherThreadFactory());

    /**
     * The node array configuration this launcher understands. Anything else is rejected
     * up front, so subclasses can safely narrow the {@link Node}s they are handed.
     */
    protected abstract Class<? extends NodeArrayConfiguration> configurationType();

    /**
     * Starts one host JVM and returns the connect string JVMs on that host must use to
     * reach ZooKeeper.
     */
    protected abstract String launchHost(GlobalNodeId hostId, Node node, String connectString, String... extraArgs) throws Exception;

    /**
     * Releases whatever {@link #launchHost} created. Called by {@link #close()}.
     */
    protected abstract void closeHosts();

    /**
     * Checks that two nodes sharing a hostname can live on the same host JVM. The default
     * accepts any pair; launchers carrying per-node host settings override this to reject
     * conflicting ones rather than silently keeping the first.
     */
    protected void checkSharedHost(Node first, Node second)
    {
    }

    @Override
    public final Map<String, String> launch(String clusterId, NodeArrayConfiguration nodeArray, String connectString, String... extraArgs) throws Exception
    {
        Class<? extends NodeArrayConfiguration> expected = configurationType();
        if (!expected.isInstance(nodeArray))
            throw new IllegalArgumentException("Node array '" + nodeArray.id() + "' is a " + nodeArray.getClass().getName() +
                " but " + getClass().getSimpleName() + " needs a " + expected.getName());

        // Several nodes of the array may name the same host: they all run on one host JVM.
        Map<String, Node> hostNodes = new LinkedHashMap<>();
        for (Node node : nodeArray.nodes())
        {
            Node alreadyOnThatHost = hostNodes.putIfAbsent(node.getHostname(), node);
            if (alreadyOnThatHost != null)
                checkSharedHost(alreadyOnThatHost, node);
        }

        Map<String, CompletableFuture<String>> pending = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : hostNodes.entrySet())
        {
            String hostname = entry.getKey();
            Node node = entry.getValue();
            HostLaunch ours = new HostLaunch(node);
            HostLaunch previous = launchedHosts.putIfAbsent(hostname, ours);
            if (previous != null)
            {
                // An earlier node array already launched this host, or is launching it right now.
                checkSharedHost(previous.node, node);
                pending.put(hostname, previous.connectString);
                continue;
            }
            GlobalNodeId hostId = new GlobalNodeId(clusterId, hostname);
            launchPool.submit(() ->
            {
                try
                {
                    ours.connectString.complete(launchHost(hostId, node, connectString, extraArgs));
                }
                catch (Throwable t)
                {
                    // Forget the failed host so a later attempt is not stuck waiting on it.
                    launchedHosts.remove(hostname, ours);
                    ours.connectString.completeExceptionally(t);
                }
            });
            pending.put(hostname, ours.connectString);
        }

        Map<String, String> remoteConnectStrings = new LinkedHashMap<>();
        Exception failure = null;
        for (Map.Entry<String, CompletableFuture<String>> entry : pending.entrySet())
        {
            try
            {
                remoteConnectStrings.put(entry.getKey(), entry.getValue().get());
            }
            catch (Exception e)
            {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                Exception error = new Exception("Error launching host '" + entry.getKey() + "' of node array '" + nodeArray.id() + "'", cause);
                if (failure == null)
                    failure = error;
                else
                    failure.addSuppressed(error);
            }
        }
        if (failure != null)
            throw failure;
        return remoteConnectStrings;
    }

    @Override
    public final void close()
    {
        launchPool.shutdownNow();
        try
        {
            closeHosts();
        }
        finally
        {
            launchedHosts.clear();
        }
    }

    private static final class HostLaunch
    {
        private final Node node;
        private final CompletableFuture<String> connectString = new CompletableFuture<>();

        private HostLaunch(Node node)
        {
            this.node = node;
        }
    }

    private static final class LauncherThreadFactory implements java.util.concurrent.ThreadFactory
    {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r)
        {
            Thread thread = new Thread(r, "jco-launcher-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
