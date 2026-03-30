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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.mortbay.jetty.orchestrator.rpc.command.Command;
import org.mortbay.jetty.orchestrator.tools.DistributedQueue;
import org.mortbay.jetty.orchestrator.util.ZooKeeperClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RpcClient implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(RpcClient.class);

    private final DistributedQueue commandQueue;
    private final DistributedQueue responseQueue;
    private final ExecutorService executorService;
    private final AtomicInteger threadIdGenerator = new AtomicInteger();
    private final ConcurrentMap<Long, CompletableFuture<Object>> calls = new ConcurrentHashMap<>();
    private final AtomicLong requestIdGenerator = new AtomicLong();
    private final GlobalNodeId globalNodeId;

    public RpcClient(ZooKeeperClient zkClient, GlobalNodeId globalNodeId)
    {
        this.globalNodeId = globalNodeId;
        commandQueue = zkClient.createDistributedQueue("/clients/" + globalNodeId.getNodeId() + "/commandQ");
        responseQueue = zkClient.createDistributedQueue("/clients/" + globalNodeId.getNodeId() + "/responseQ");
        executorService = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r);
            t.setName("jco-" + threadIdGenerator.getAndIncrement());
            return t;
        });
        executorService.submit(() ->
        {
            while (true)
            {
                Response resp = (Response)responseQueue.take();
                if (LOG.isDebugEnabled())
                    LOG.debug("{} got response {}", globalNodeId.getNodeId(), resp);
                CompletableFuture<Object> future = calls.remove(resp.getId());
                if (resp.getThrowable() != null)
                    future.completeExceptionally(new ExecutionException(resp.getThrowable()));
                else
                    future.complete(resp.getResult());
            }
        });
    }

    public CompletableFuture<Object> callAsync(Command command) throws Exception
    {
        if (isClosed())
            throw new IllegalStateException("RPC client is closed");
        long requestId = requestIdGenerator.getAndIncrement();
        CompletableFuture<Object> completableFuture = new CompletableFuture<>();
        calls.put(requestId, completableFuture);
        Request request = new Request(requestId, command);
        if (LOG.isDebugEnabled())
            LOG.debug("{} sending request {}", globalNodeId.getNodeId(), request);
        commandQueue.offer(request);
        return completableFuture;
    }

    public Object call(Command command) throws Exception
    {
        CompletableFuture<Object> future = callAsync(command);
        return future.get();
    }

    private boolean isClosed()
    {
        return executorService.isShutdown();
    }

    @Override
    public void close()
    {
        executorService.shutdownNow();
        calls.values().forEach(f -> f.completeExceptionally(new IllegalStateException("Pending call terminated on close (remote process died?) for node " + globalNodeId.getNodeId())));
        calls.clear();
    }
}
