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

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

/**
 * BasicFileAttributes implementation for Kubernetes pods, backed by enhanced
 * stat command output. Provides file type, size, timestamps, and permission
 * information parsed from kubectl exec stat commands.
 */
class KubernetesNodeFileAttributes implements BasicFileAttributes
{
    private final String fileType;
    private final long size;
    private final long modificationTime;
    private final long accessTime;
    private final long statusChangeTime;
    private final int permissions;
    private final int userId;
    private final int groupId;

    /**
     * Creates file attributes from enhanced stat command output.
     * Expected format: "%F %s %a %u %g %Y %X %Z"
     * 
     * @param fileType file type string (e.g., "regular file", "directory")
     * @param size file size in bytes
     * @param permissions octal permissions (e.g., 755, 644)
     * @param userId user ID of file owner
     * @param groupId group ID of file owner
     * @param modificationTime modification time in seconds since epoch
     * @param accessTime access time in seconds since epoch
     * @param statusChangeTime status change time in seconds since epoch
     */
    KubernetesNodeFileAttributes(String fileType, long size, int permissions, 
                                int userId, int groupId, long modificationTime,
                                long accessTime, long statusChangeTime)
    {
        this.fileType = fileType;
        this.size = size;
        this.permissions = permissions;
        this.userId = userId;
        this.groupId = groupId;
        this.modificationTime = modificationTime;
        this.accessTime = accessTime;
        this.statusChangeTime = statusChangeTime;
    }

    /**
     * Gets the octal permissions as an integer.
     * @return permissions in octal format (e.g., 0755)
     */
    public int getPermissions()
    {
        return permissions;
    }

    /**
     * Gets the user ID of the file owner.
     * @return user ID
     */
    public int getUserId()
    {
        return userId;
    }

    /**
     * Gets the group ID of the file owner.
     * @return group ID
     */
    public int getGroupId()
    {
        return groupId;
    }

    /**
     * Checks if the specified access mode is allowed by file permissions.
     * 
     * @param mode access mode mask (e.g., 0444 for read, 0222 for write, 0111 for execute)
     * @return true if access is allowed
     */
    public boolean hasPermission(int mode)
    {
        return (permissions & mode) != 0;
    }

    /**
     * Checks if file has read permission for owner, group, or others.
     * @return true if readable
     */
    public boolean isReadable()
    {
        return hasPermission(0444);
    }

    /**
     * Checks if file has write permission for owner, group, or others.
     * @return true if writable
     */
    public boolean isWritable()
    {
        return hasPermission(0222);
    }

    /**
     * Checks if file has execute permission for owner, group, or others.
     * @return true if executable
     */
    public boolean isExecutable()
    {
        return hasPermission(0111);
    }

    @Override
    public FileTime lastModifiedTime()
    {
        return FileTime.from(modificationTime, TimeUnit.SECONDS);
    }

    @Override
    public FileTime lastAccessTime()
    {
        return FileTime.from(accessTime, TimeUnit.SECONDS);
    }

    @Override
    public FileTime creationTime()
    {
        // Linux doesn't track creation time, use status change time as closest approximation
        return FileTime.from(statusChangeTime, TimeUnit.SECONDS);
    }

    @Override
    public boolean isRegularFile()
    {
        return "regular file".equals(fileType);
    }

    @Override
    public boolean isDirectory()
    {
        return "directory".equals(fileType);
    }

    @Override
    public boolean isSymbolicLink()
    {
        return "symbolic link".equals(fileType);
    }

    @Override
    public boolean isOther()
    {
        return !isDirectory() && !isRegularFile() && !isSymbolicLink();
    }

    @Override
    public long size()
    {
        return size;
    }

    @Override
    public Object fileKey()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return "KubernetesNodeFileAttributes{" +
            "fileType='" + fileType + '\'' +
            ", size=" + size +
            ", permissions=" + Integer.toOctalString(permissions) +
            ", userId=" + userId +
            ", groupId=" + groupId +
            ", modificationTime=" + modificationTime +
            ", accessTime=" + accessTime +
            ", statusChangeTime=" + statusChangeTime +
            '}';
    }
}