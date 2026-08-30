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

import java.util.Map;

/**
 * Creates the host JVMs a cluster runs on.
 *
 * <p>A launcher owns a whole node array, not a single node, so it can read whatever
 * launcher-specific settings its own {@link NodeArrayConfiguration} carries. See
 * {@link AbstractHostLauncher}, which takes care of the parts every launcher needs.</p>
 */
public interface HostLauncher extends AutoCloseable
{
    /**
     * Returns a ZooKeeper connect string for the cluster controller to use.
     */
    String initialize() throws Exception;

    /**
     * Launches the host JVMs this node array needs. Nodes sharing a hostname share a host
     * JVM, and a host already launched for an earlier node array is reused.
     *
     * @return for every distinct hostname of the array, the connect string that JVMs
     *         running on that host must use to reach ZooKeeper.
     */
    Map<String, String> launch(String clusterId, NodeArrayConfiguration nodeArray, String connectString, String... extraArgs) throws Exception;

    @Override
    void close();
}
