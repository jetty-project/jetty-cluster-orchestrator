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

package org.mortbay.jetty.orchestrator.k8s.configuration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mortbay.jetty.orchestrator.configuration.AbstractNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.configuration.Node;
import org.mortbay.jetty.orchestrator.localhost.launcher.LocalHostLauncher;

/**
 * Node array running as Kubernetes pods.
 *
 * <p>Node selectors set here apply to every node of the array; a node setting the same key
 * overrides it.</p>
 */
public class K8sNodeArrayConfiguration extends AbstractNodeArrayConfiguration
{
    private final Map<String, String> nodeSelectors = new LinkedHashMap<>();

    public K8sNodeArrayConfiguration(String id)
    {
        super(id);
    }

    public K8sNodeArrayConfiguration node(K8sNode node)
    {
        if (LocalHostLauncher.HOSTNAME.equals(node.getHostname()))
            throw new IllegalArgumentException("'" + LocalHostLauncher.HOSTNAME + "' is reserved for LocalNodeArrayConfiguration, use a pod hostname instead");
        addNode(node);
        return this;
    }

    /**
     * Adds a node selector applying to every node of this array.
     */
    public K8sNodeArrayConfiguration nodeSelector(String key, String value)
    {
        nodeSelectors.put(key, value);
        return this;
    }

    public K8sNodeArrayConfiguration nodeSelectors(Map<String, String> nodeSelectors)
    {
        this.nodeSelectors.putAll(nodeSelectors);
        return this;
    }

    @Override
    public Collection<? extends Node> nodes()
    {
        Collection<? extends Node> nodes = super.nodes();
        if (nodeSelectors.isEmpty())
            return nodes;

        List<K8sNode> merged = new ArrayList<>(nodes.size());
        for (Node node : nodes)
        {
            K8sNode k8sNode = (K8sNode)node;
            Map<String, String> selectors = new LinkedHashMap<>(nodeSelectors);
            selectors.putAll(k8sNode.getNodeSelectors());
            merged.add(k8sNode.withNodeSelectors(selectors));
        }
        return Collections.unmodifiableList(merged);
    }

    @Override
    public K8sNodeArrayConfiguration jvm(Jvm jvm)
    {
        super.jvm(jvm);
        return this;
    }
}
