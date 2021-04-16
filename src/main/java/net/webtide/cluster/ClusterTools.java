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

package net.webtide.cluster;

import net.webtide.cluster.tools.AtomicCounter;
import net.webtide.cluster.tools.Barrier;
import org.apache.curator.framework.CuratorFramework;

public class ClusterTools
{
    private final CuratorFramework curator;
    private final String nodeId;

    public ClusterTools(CuratorFramework curator, String nodeId)
    {
        this.curator = curator;
        this.nodeId = nodeId;
    }

    public Barrier barrier(String name, int count)
    {
        return new Barrier(curator, nodeId, name, count);
    }

    public AtomicCounter atomicCounter(String name)
    {
        return new AtomicCounter(curator, nodeId, name);
    }
}
