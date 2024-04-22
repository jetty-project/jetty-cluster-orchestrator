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

class NodeFileAttributes // implements BasicFileAttributes
{
//    private final FileAttributes lstat;
//
//    NodeFileAttributes(FileAttributes lstat)
//    {
//        this.lstat = lstat;
//    }
//
//    public FileAttributes getLstat()
//    {
//        return lstat;
//    }
//
//    @Override
//    public FileTime lastModifiedTime()
//    {
//        return FileTime.from(lstat.getMtime(), TimeUnit.MILLISECONDS);
//    }
//
//    @Override
//    public FileTime lastAccessTime()
//    {
//        return FileTime.from(lstat.getAtime(), TimeUnit.MILLISECONDS);
//    }
//
//    @Override
//    public FileTime creationTime()
//    {
//        return lastModifiedTime();
//    }
//
//    @Override
//    public boolean isRegularFile()
//    {
//        return lstat.getType() == FileMode.Type.REGULAR;
//    }
//
//    @Override
//    public boolean isDirectory()
//    {
//        return lstat.getType() == FileMode.Type.DIRECTORY;
//    }
//
//    @Override
//    public boolean isSymbolicLink()
//    {
//        return lstat.getType() == FileMode.Type.SYMLINK;
//    }
//
//    @Override
//    public boolean isOther()
//    {
//        return !isDirectory() && !isRegularFile() && !isSymbolicLink();
//    }
//
//    @Override
//    public long size()
//    {
//        return lstat.getSize();
//    }
//
//    @Override
//    public Object fileKey()
//    {
//        return null;
//    }
}
