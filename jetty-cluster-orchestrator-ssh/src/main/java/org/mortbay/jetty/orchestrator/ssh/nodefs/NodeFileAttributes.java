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

package org.mortbay.jetty.orchestrator.ssh.nodefs;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

import org.apache.sshd.sftp.client.SftpClient;

class NodeFileAttributes implements BasicFileAttributes {
    private final SftpClient.Attributes lstat;

    NodeFileAttributes(SftpClient.Attributes lstat) {
        this.lstat = lstat;
    }

    public SftpClient.Attributes getLstat() {
        return lstat;
    }

    @Override
    public FileTime lastModifiedTime() {
        return lstat.getModifyTime();
    }

    @Override
    public FileTime lastAccessTime() {
        return lstat.getAccessTime();
    }

    @Override
    public FileTime creationTime() {
        return lastModifiedTime();
    }

    @Override
    public boolean isRegularFile() {
        return lstat.isRegularFile();
    }

    @Override
    public boolean isDirectory() {
        return lstat.isDirectory();
    }

    @Override
    public boolean isSymbolicLink() {
        return lstat.isSymbolicLink();
    }

    @Override
    public boolean isOther() {
        return lstat.isOther();
    }

    @Override
    public long size() {
        return lstat.getSize();
    }

    @Override
    public Object fileKey() {
        return null;
    }
}
