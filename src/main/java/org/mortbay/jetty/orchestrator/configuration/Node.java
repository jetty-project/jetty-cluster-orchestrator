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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class Node
{
    private final String id;
    private final String hostname;
    private final Map<String, String> nodeSelectors;

    public Node(String hostname)
    {
        this(hostname, hostname);
    }

    public Node(String id, String hostname)
    {
        this(id, hostname, Collections.emptyMap());
    }

    public Node(String id, String hostname, Map<String, String> nodeSelectors)
    {
        this.id = id;
        this.hostname = hostname;
        this.nodeSelectors = Collections.unmodifiableMap(new HashMap<>(nodeSelectors));
    }

    public String getId()
    {
        return id;
    }

    public String getHostname()
    {
        return hostname;
    }

    public Map<String, String> getNodeSelectors()
    {
        return nodeSelectors;
    }

    public Node nodeSelector(String key, String value)
    {
        Map<String, String> merged = new HashMap<>(this.nodeSelectors);
        merged.put(key, value);
        return new Node(id, hostname, merged);
    }

    @Override
    public String toString() {
        return "Node{" +
                "id='" + id + '\'' +
                ", hostname='" + hostname + '\'' +
                ", nodeSelectors=" + nodeSelectors +
                '}';
    }
}
