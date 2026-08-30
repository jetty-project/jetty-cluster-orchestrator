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

import java.util.Objects;

/**
 * A {@link Node} that is nothing but an id and a hostname, for launchers that need
 * no extra per-node configuration.
 */
public class SimpleNode implements Node
{
    private final String id;
    private final String hostname;

    public SimpleNode(String id, String hostname)
    {
        this.id = Objects.requireNonNull(id, "Node id cannot be null");
        this.hostname = Objects.requireNonNull(hostname, "Node hostname cannot be null");
    }

    @Override
    public String getId()
    {
        return id;
    }

    @Override
    public String getHostname()
    {
        return hostname;
    }

    @Override
    public String toString()
    {
        return "SimpleNode{id='" + id + "', hostname='" + hostname + "'}";
    }
}
