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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds what every node array has in common: an id, a JVM and a set of uniquely
 * identified nodes. Launcher-specific subclasses add their own settings and expose
 * a {@code node(...)} method taking whichever {@link Node} type they support.
 *
 * <p>Nodes keep their declaration order, which is what makes cluster startup
 * reproducible from one run to the next.</p>
 */
public abstract class AbstractNodeArrayConfiguration implements NodeArrayConfiguration, JvmDependent
{
    private final String id;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private Jvm jvm;

    protected AbstractNodeArrayConfiguration(String id)
    {
        this.id = Objects.requireNonNull(id, "Node array id cannot be null");
    }

    @Override
    public String id()
    {
        return id;
    }

    @Override
    public Collection<? extends Node> nodes()
    {
        return Collections.unmodifiableCollection(nodes.values());
    }

    protected void addNode(Node node)
    {
        Objects.requireNonNull(node, "Node cannot be null");
        if (nodes.putIfAbsent(node.getId(), node) != null)
            throw new IllegalArgumentException("Duplicate node ID in node array '" + id + "': " + node.getId());
    }

    @Override
    public Jvm jvm()
    {
        return jvm;
    }

    @Override
    public AbstractNodeArrayConfiguration jvm(Jvm jvm)
    {
        this.jvm = jvm;
        return this;
    }
}
