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

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.schmizz.sshj.sftp.SFTPClient;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystem;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemFactory;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;

/**
 * Factory for creating SFTP-based NodeFileSystem instances.
 */
public class SFTPNodeFileSystemFactory implements NodeFileSystemFactory
{
    @Override
    public boolean canHandle(Map<String, ?> env)
    {
        return env.containsKey(SFTPClient.class.getName());
    }

    @Override
    public NodeFileSystem createFileSystem(NodeFileSystemProvider provider, URI uri, Map<String, ?> env) throws IOException
    {
        boolean windows = (Boolean)env.get(NodeFileSystemProvider.IS_WINDOWS_ENV_PROPERTY);
        SFTPClient sftpClient = (SFTPClient)env.get(SFTPClient.class.getName());
        String hostId = extractHostId(uri);
        String pathStr = extractPath(uri);
        List<String> path = pathStr.isEmpty() ? Collections.emptyList() : java.util.Arrays.asList(pathStr.split("/"));
        return new SFTPNodeFileSystem(provider, sftpClient, hostId, path, windows);
    }

    @Override
    public int getPriority()
    {
        return 100; // High priority for SSH
    }
    
    private String extractHostId(URI uri)
    {
        String hostId = uri.getHost();
        if (hostId == null)
        {
            String ssp = uri.getSchemeSpecificPart();
            if (ssp.startsWith("//"))
                ssp = ssp.substring(2);
            int idx = ssp.indexOf('!');
            if (idx >= 0)
                hostId = ssp.substring(0, idx);
            else
                hostId = ssp;
        }
        return hostId;
    }
    
    private String extractPath(URI uri)
    {
        String fragment = uri.getFragment();
        if (fragment != null && fragment.startsWith("/"))
            return fragment.substring(1);
        
        String ssp = uri.getSchemeSpecificPart();
        int idx = ssp.indexOf('!');
        if (idx >= 0 && idx + 2 < ssp.length() && ssp.charAt(idx + 1) == '/')
            return ssp.substring(idx + 2);
        
        return "";
    }
}