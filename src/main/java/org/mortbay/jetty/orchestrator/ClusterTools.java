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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;
import org.mortbay.jetty.orchestrator.tools.AtomicCounter;
import org.mortbay.jetty.orchestrator.tools.Barrier;
import org.mortbay.jetty.orchestrator.tools.DistributedQueue;
import org.mortbay.jetty.orchestrator.util.ZooKeeperClient;

public class ClusterTools
{
    private final ZooKeeperClient zkClient;
    private final GlobalNodeId globalNodeId;
    private final ConcurrentMap<String, Object> nodeEnvironment = new ConcurrentHashMap<>();

    public ClusterTools(ZooKeeperClient zkClient, GlobalNodeId globalNodeId)
    {
        this.zkClient = zkClient;
        this.globalNodeId = globalNodeId;
    }

    public GlobalNodeId getGlobalNodeId()
    {
        return globalNodeId;
    }

    /**
     * @deprecated use {@link #getGlobalNodeId()} instead
     */
    @Deprecated
    public String getNodeId()
    {
        return globalNodeId.getNodeId();
    }

    public Barrier barrier(String name, int count)
    {
        return zkClient.createBarrier(globalNodeId, name, count);
    }

    public AtomicCounter atomicCounter(String name, long initialValue)
    {
        return zkClient.createAtomicCounter(globalNodeId, name, initialValue);
    }

    public DistributedQueue distributedQueue(String name)
    {
        return zkClient.createDistributedQueue(name);
    }

    public ConcurrentMap<String, Object> nodeEnvironment()
    {
        return nodeEnvironment;
    }
}
