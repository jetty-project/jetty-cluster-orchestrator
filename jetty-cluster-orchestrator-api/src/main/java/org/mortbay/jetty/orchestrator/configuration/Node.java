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
 * Identity of a node within a node array: which node it is, and which host it runs on.
 *
 * <p>This is all the orchestrator core needs to know about a node. Anything describing
 * <em>how</em> a host gets created belongs to the launcher that creates it, so launchers
 * define their own {@code Node} implementations alongside their own
 * {@link NodeArrayConfiguration}.</p>
 */
public interface Node
{
    String getId();

    String getHostname();
}
