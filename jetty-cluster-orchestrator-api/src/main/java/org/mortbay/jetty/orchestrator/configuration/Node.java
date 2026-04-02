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

import java.util.HashMap;
import java.util.Map;

public class Node
{
    private final String id;
    private final String hostname;
    private Map<String, String> nodeSelectors;
    private final Map<String, String> labels;
    private final int servicePort;


    private Node(String id, String hostname, Map<String, String> nodeSelectors, Map<String, String> labels, int servicePort)
    {
        this.id = id;
        this.hostname = hostname;
        this.nodeSelectors = Map.copyOf(nodeSelectors);
        this.labels = Map.copyOf(labels);
        this.servicePort = servicePort;
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

    public Map<String, String> getLabels() {
        return labels;
    }

    public int getServicePort() {
        return servicePort;
    }

    public Node withNodeSelectors(Map<String, String> nodeSelectors) {
        this.nodeSelectors = nodeSelectors;
        return this;
    }

    @Override
    public String toString() {
        return "Node{" +
                "id='" + id + '\'' +
                ", hostname='" + hostname + '\'' +
                ", label='" + labels + '\'' +
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

        public Builder withLabels(Map<String, String> labels)
        {
            this.labels = labels;
            return this;
        }

        public Builder withServicePort(int port) {
            this.servicePort = port;
            return this;
        }

        public Node build()
        {
            return new Node(id, hostname, nodeSelectors, labels, servicePort);
        }
    }

}
