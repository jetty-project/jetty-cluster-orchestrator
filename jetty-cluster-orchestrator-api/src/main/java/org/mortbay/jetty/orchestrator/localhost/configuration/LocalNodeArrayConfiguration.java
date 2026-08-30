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

package org.mortbay.jetty.orchestrator.localhost.configuration;

import org.mortbay.jetty.orchestrator.configuration.AbstractNodeArrayConfiguration;
import org.mortbay.jetty.orchestrator.configuration.Jvm;
import org.mortbay.jetty.orchestrator.configuration.SimpleNode;
import org.mortbay.jetty.orchestrator.localhost.launcher.LocalHostLauncher;

/**
 * Node array running in the JVM that started the cluster, launched by
 * {@link LocalHostLauncher}. Every node lives on the same host, so only an id is needed.
 */
public class LocalNodeArrayConfiguration extends AbstractNodeArrayConfiguration
{
    public LocalNodeArrayConfiguration(String id)
    {
        super(id);
    }

    public LocalNodeArrayConfiguration node(String id)
    {
        addNode(new SimpleNode(id, LocalHostLauncher.HOSTNAME));
        return this;
    }

    @Override
    public LocalNodeArrayConfiguration jvm(Jvm jvm)
    {
        super.jvm(jvm);
        return this;
    }
}
