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

import org.mortbay.jetty.orchestrator.rpc.GlobalNodeId;

public interface HostLauncher extends AutoCloseable
{
    /**
     * Returns a ZooKeeper connect string for the cluster controller to use, or {@code null}
     * to let {@link org.mortbay.jetty.orchestrator.Cluster} start its own embedded ZooKeeper.
     *
     * <p>When non-null, {@code Cluster} will skip starting the embedded ZooKeeper and use
     * this connect string instead. The launcher is responsible for keeping the ZooKeeper
     * accessible for the lifetime of the cluster.
     */
    default String getZooKeeperConnectString() throws Exception
    {
        return null;
    }

    /**
     * @return the modified {@code connectString} that nodes running on this host must connect to.
     */
    String launch(GlobalNodeId hostId, Node node, String connectString, String... extraArgs) throws Exception;
}
