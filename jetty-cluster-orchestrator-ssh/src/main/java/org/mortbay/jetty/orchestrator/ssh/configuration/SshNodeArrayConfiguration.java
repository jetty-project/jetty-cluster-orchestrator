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

package org.mortbay.jetty.orchestrator.ssh.configuration;

import org.mortbay.jetty.orchestrator.configuration.AbstractNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.configuration.SimpleNode;
import org.mortbay.jetty.orchestrator.localhost.launcher.LocalHostLauncher;
import org.mortbay.jetty.orchestrator.ssh.launcher.SshRemoteHostLauncher;

/**
 * Node array run by {@link SshRemoteHostLauncher} on machines reached over SSH.
 * Nodes naming the same machine share one host JVM.
 */
public class SshNodeArrayConfiguration extends AbstractNodeArrayConfiguration
{
    public SshNodeArrayConfiguration(String id)
    {
        super(id);
    }

    /**
     * Adds a node whose id is the hostname it runs on.
     */
    public SshNodeArrayConfiguration node(String hostname)
    {
        return node(hostname, hostname);
    }

    public SshNodeArrayConfiguration node(String id, String hostname)
    {
        if (LocalHostLauncher.HOSTNAME.equals(hostname))
            throw new IllegalArgumentException("'" + LocalHostLauncher.HOSTNAME + "' is reserved for LocalNodeArrayConfiguration, use a resolvable hostname instead");
        addNode(new SimpleNode(id, hostname));
        return this;
    }

    @Override
    public SshNodeArrayConfiguration jvm(Jvm jvm)
    {
        super.jvm(jvm);
        return this;
    }
}
