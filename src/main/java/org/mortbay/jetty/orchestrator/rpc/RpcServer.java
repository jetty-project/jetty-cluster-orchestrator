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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.SimpleDistributedQueue;
import org.mortbay.jetty.orchestrator.ClusterTools;
import org.mortbay.jetty.orchestrator.rpc.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RpcServer implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(RpcServer.class);

    private final GlobalNodeId globalNodeId;
    private final SimpleDistributedQueue commandQueue;
    private final SimpleDistributedQueue responseQueue;
    private final ExecutorService executorService;
    private final AtomicInteger threadIdGenerator = new AtomicInteger();
    private volatile boolean active;
    private final ClusterTools clusterTools;
    private volatile long lastCommandTimestamp;

    public RpcServer(CuratorFramework curator, GlobalNodeId globalNodeId)
    {
        this.globalNodeId = globalNodeId;
        commandQueue = new SimpleDistributedQueue(curator, "/clients/" + globalNodeId.getNodeId() + "/commandQ");
        responseQueue = new SimpleDistributedQueue(curator, "/clients/" + globalNodeId.getNodeId() + "/responseQ");
        executorService = Executors.newCachedThreadPool(r ->
        {
            Thread thread = new Thread(r);
            String nodeId = globalNodeId.getNodeId();
            String shortId = nodeId.substring(nodeId.indexOf('/') + 1);
            thread.setName(threadIdGenerator.getAndIncrement() + "|" + shortId);
            return thread;
        });
        clusterTools = new ClusterTools(curator, globalNodeId);
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
            commandQueue.offer(serialize(new Request(0, new AbortCommand())));
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
            byte[] cmdBytes;
            try
            {
                cmdBytes = commandQueue.take();
                Object obj = deserialize(cmdBytes);
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

                    byte[] resBytes;
                    try
                    {
                        Response response = new Response(requestId, result, throwable);
                        resBytes = serialize(response);
                    }
                    catch (IOException e)
                    {
                        Response response = new Response(requestId, null, e);
                        try
                        {
                            resBytes = serialize(response);
                        }
                        catch (IOException nested)
                        {
                            // can't happen
                            resBytes = null;
                        }
                    }
                    try
                    {
                        responseQueue.offer(resBytes);
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

    private static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException
    {
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
        return ois.readObject();
    }

    private static byte[] serialize(Object obj) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        return baos.toByteArray();
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
