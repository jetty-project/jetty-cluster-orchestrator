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

package org.mortbay.jetty.orchestrator.k8s.nodefs;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystem;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemFactory;
import org.mortbay.jetty.orchestrator.nodefs.NodeFileSystemProvider;

/**
 * Factory for creating Kubernetes-based NodeFileSystem instances.
 */
public class KubernetesNodeFileSystemFactory implements NodeFileSystemFactory
{
    @Override
    public boolean canHandle(Map<String, ?> env)
    {
        return env.containsKey(KubernetesClient.class.getName());
    }

    @Override
    public NodeFileSystem createFileSystem(NodeFileSystemProvider provider, URI uri, Map<String, ?> env) throws IOException
    {
        KubernetesClient k8sClient = (KubernetesClient)env.get(KubernetesClient.class.getName());
        String ns = (String)env.get(NodeFileSystemProvider.K8S_NAMESPACE_ENV_PROPERTY);
        String podName = (String)env.get(NodeFileSystemProvider.K8S_POD_NAME_ENV_PROPERTY);
        String podHome = (String)env.get(NodeFileSystemProvider.K8S_POD_HOME_ENV_PROPERTY);
        String hostId = extractHostId(uri);
        String pathStr = extractPath(uri);
        List<String> path = pathStr.isEmpty() ? Collections.emptyList() : java.util.Arrays.asList(pathStr.split("/"));
        return new KubernetesNodeFileSystem(provider, k8sClient, ns, podName, podHome, hostId, path);
    }

    @Override
    public int getPriority()
    {
        return 200; // Higher priority for Kubernetes
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