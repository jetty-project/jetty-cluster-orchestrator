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

package org.mortbay.jetty.orchestrator.rpc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.mortbay.jetty.orchestrator.ClusterTools;
import org.mortbay.jetty.orchestrator.rpc.command.Command;
import org.mortbay.jetty.orchestrator.tools.DistributedQueue;
import org.mortbay.jetty.orchestrator.util.ZooKeeperClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RpcServer implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(RpcServer.class);

    private final GlobalNodeId globalNodeId;
    private final DistributedQueue commandQueue;
    private final DistributedQueue responseQueue;
    private final ExecutorService executorService;
    private final AtomicInteger threadIdGenerator = new AtomicInteger();
    private volatile boolean active;
    private final ClusterTools clusterTools;
    private volatile long lastCommandTimestamp;

    public RpcServer(ZooKeeperClient zkClient, GlobalNodeId globalNodeId)
    {
        this.globalNodeId = globalNodeId;
        commandQueue = zkClient.createDistributedQueue("/clients/" + globalNodeId.getNodeId() + "/commandQ");
        responseQueue = zkClient.createDistributedQueue("/clients/" + globalNodeId.getNodeId() + "/responseQ");
        executorService = Executors.newCachedThreadPool(r ->
        {
            Thread thread = new Thread(r);
            String nodeId = globalNodeId.getNodeId();
            String shortId = nodeId.substring(nodeId.indexOf('/') + 1);
            thread.setName(threadIdGenerator.getAndIncrement() + "|" + shortId);
            return thread;
        });
        clusterTools = new ClusterTools(zkClient, globalNodeId);
        lastCommandTimestamp = System.nanoTime();
    }

    public long getLastCommandTimestamp()
    {
        return lastCommandTimestamp;
    }

    @Override
    public void close() throws Exception
    {
        if (active)
            abort();
        for (int i = 0; i < 1000; i++)
        {
            if (active)
                Thread.sleep(5);
            else
                break;
        }
        executorService.shutdownNow();
    }

    private void abort()
    {
        try
        {
            commandQueue.offer(new Request(0, new AbortCommand()));
        }
        catch (Exception e)
        {
            // does not matter, ZK is shutting down if this happens
            if (LOG.isDebugEnabled())
                LOG.debug("", e);
        }
    }

    public void run()
    {
        active = true;
        while (active)
        {
            try
            {
                Object obj = commandQueue.take();
                Request request = (Request)obj;
                lastCommandTimestamp = System.nanoTime();
                if (LOG.isDebugEnabled())
                    LOG.debug("Received request from {} : {}", globalNodeId.getNodeId(), request);
                if (request.getCommand().getClass() == AbortCommand.class)
                {
                    active = false;
                    return;
                }

                executorService.submit(()->
                {
                    Object result = null;
                    Throwable throwable = null;
                    long requestId = -1;
                    try
                    {
                        requestId = request.getId();
                        result = request.getCommand().execute(clusterTools);
                    }
                    catch (Throwable x)
                    {
                        throwable = x;
                    }

                    try
                    {
                        Response response = new Response(requestId, result, throwable);
                        responseQueue.offer(response);
                    }
                    catch (Exception e)
                    {
                        // does not matter, ZK is shutting down if this happens
                        if (LOG.isDebugEnabled())
                            LOG.debug("", e);
                    }
                });
            }
            catch (InterruptedException e)
            {
                active = false;
                return;
            }
            catch (Exception e)
            {
                active = false;
                throw new RuntimeException("Error reading command on node " + globalNodeId.getNodeId(), e);
            }
        }
    }

    private static class AbortCommand implements Command
    {
        @Override
        public Object execute(ClusterTools clusterTools)
        {
            return null;
        }
    }
}
