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
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Set;

/**
 * Base interface for node-backed NIO FileSystems (SSH/SFTP and Kubernetes).
 * Provides the extra methods that {@link NodePath} and {@link NodeFileSystemProvider} need
 * beyond the standard {@link FileSystem} API.
 */
abstract class NodeFileSystem extends FileSystem
{
    abstract String getHostId();

    abstract boolean isWindows();

    abstract InputStream newInputStream(NodePath path, OpenOption... options) throws IOException;

    abstract SeekableByteChannel newByteChannel(NodePath path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException;

    abstract DirectoryStream<Path> newDirectoryStream(NodePath dir, DirectoryStream.Filter<? super Path> filter) throws IOException;

    abstract <A extends BasicFileAttributes> A readAttributes(NodePath path, Class<A> type, LinkOption... options) throws IOException;

    abstract Path getPath(boolean absolute, List<String> segments);
    
    /**
     * Check access to the given path for the specified modes.
     * Default implementation uses basic file existence check.
     * Subclasses should override for more sophisticated permission checking.
     */
    void checkAccess(NodePath path, AccessMode... modes) throws IOException
    {
        // Default implementation - just check if file exists
        readAttributes(path, BasicFileAttributes.class);
    }
}
