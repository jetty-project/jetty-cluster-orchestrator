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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.mortbay.jetty.orchestrator.configuration.Node;

/**
 * A node running in a Kubernetes pod, with the pod settings the Kubernetes launcher needs.
 *
 * <p>Instances are immutable; {@link #withNodeSelectors(Map)} returns a new node.</p>
 */
public class K8sNode implements Node
{
    private final String id;
    private final String hostname;
    private final Map<String, String> nodeSelectors;
    private final Map<String, String> labels;
    private final int servicePort;

    private K8sNode(String id, String hostname, Map<String, String> nodeSelectors, Map<String, String> labels, int servicePort)
    {
        this.id = Objects.requireNonNull(id, "Node id cannot be null");
        this.hostname = Objects.requireNonNull(hostname, "Node hostname cannot be null");
        this.nodeSelectors = Map.copyOf(nodeSelectors);
        this.labels = Map.copyOf(labels);
        this.servicePort = servicePort;
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

    /**
     * Labels the pod's node must carry for the pod to be scheduled on it.
     *
     * @see <a href="https://kubernetes.io/docs/concepts/scheduling-eviction/assign-pod-node/#nodeselector">nodeSelector</a>
     */
    public Map<String, String> getNodeSelectors()
    {
        return nodeSelectors;
    }

    /**
     * Labels put on the pod itself.
     */
    public Map<String, String> getLabels()
    {
        return labels;
    }

    /**
     * Port to expose through a Kubernetes service named after the hostname, or a negative
     * value to create no service.
     */
    public int getServicePort()
    {
        return servicePort;
    }

    /**
     * @return a copy of this node with the given node selectors.
     */
    public K8sNode withNodeSelectors(Map<String, String> nodeSelectors)
    {
        return new K8sNode(id, hostname, nodeSelectors, labels, servicePort);
    }

    @Override
    public String toString()
    {
        return "K8sNode{" +
            "id='" + id + '\'' +
            ", hostname='" + hostname + '\'' +
            ", labels=" + labels +
            ", nodeSelectors=" + nodeSelectors +
            ", servicePort=" + servicePort +
            '}';
    }

    public static final class Builder
    {
        private String id;
        private String hostname;
        private Map<String, String> nodeSelectors = new HashMap<>();
        private Map<String, String> labels = new HashMap<>();
        private int servicePort = -1;

        public Builder withId(String id)
        {
            this.id = id;
            return this;
        }

        public Builder withHostname(String hostname)
        {
            this.hostname = hostname;
            return this;
        }

        public Builder withNodeSelectors(Map<String, String> nodeSelectors)
        {
            this.nodeSelectors = nodeSelectors;
            return this;
        }

        public Builder withNodeSelector(String key, String value)
        {
            this.nodeSelectors.put(key, value);
            return this;
        }

        public Builder withLabels(Map<String, String> labels)
        {
            this.labels = labels;
            return this;
        }

        public Builder withLabel(String key, String value)
        {
            this.labels.put(key, value);
            return this;
        }

        public Builder withServicePort(int port)
        {
            this.servicePort = port;
            return this;
        }

        public K8sNode build()
        {
            return new K8sNode(id, hostname, nodeSelectors, labels, servicePort);
        }
    }
}
