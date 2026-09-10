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

/**
 * Which node this is and which host it runs on. That is all the cluster needs.
 * How a host gets created is the launcher's business, so launchers bring their own
 * {@code Node} types along with their own {@link NodeArrayConfiguration}.
 */
public interface Node
{
    String getId();

    String getHostname();
}
