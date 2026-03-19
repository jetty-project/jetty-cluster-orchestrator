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

package org.mortbay.jetty.orchestrator.nodefs;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * Factory interface for creating NodeFileSystem instances.
 * Implementation modules should provide implementations of this interface
 * and register them via ServiceLoader.
 */
public interface NodeFileSystemFactory
{
    /**
     * Check if this factory can handle the given environment
     * @param env the environment properties
     * @return true if this factory can create a filesystem for this environment
     */
    boolean canHandle(Map<String, ?> env);
    
    /**
     * Create a NodeFileSystem instance
     * @param provider the filesystem provider
     * @param uri the filesystem URI
     * @param env the environment properties
     * @return the created filesystem
     * @throws IOException if the filesystem cannot be created
     */
    NodeFileSystem createFileSystem(NodeFileSystemProvider provider, URI uri, Map<String, ?> env) throws IOException;
    
    /**
     * Get the priority of this factory (higher priority factories are tried first)
     * @return the priority value
     */
    default int getPriority()
    {
        return 0;
    }
}