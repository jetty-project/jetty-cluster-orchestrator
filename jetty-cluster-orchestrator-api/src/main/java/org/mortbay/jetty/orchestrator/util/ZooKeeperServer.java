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

package org.mortbay.jetty.orchestrator.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.apache.zookeeper.server.embedded.ZooKeeperServerEmbedded;

public class ZooKeeperServer implements Closeable
{
    private final ZooKeeperServerEmbedded zk;
    private final String connectString;
    private final Path baseDir;

    public ZooKeeperServer() throws Exception
    {
        baseDir = createFreshBaseDir();
        zk = new ZooKeeperServerEmbedded.ZookKeeperServerEmbeddedBuilder()
            .baseDir(baseDir)
            .configuration(createConfiguration())
            .build();
        zk.start();
        connectString = zk.getConnectionString();
    }

    private static Properties createConfiguration()
    {
        Properties configuration = new Properties();
        configuration.put("clientPort", "0");
        return configuration;
    }

    private Path createFreshBaseDir() throws IOException
    {
        long pid = ProcessHandle.current().pid();
        Path baseDir = Paths.get(System.getProperty("java.io.tmpdir")).resolve("jco-zk-" + pid);
        IOUtil.deltree(baseDir);
        Files.createDirectories(baseDir);
        return baseDir;
    }

    public String getConnectString()
    {
        return connectString;
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            zk.close();
        }
        finally
        {
            IOUtil.deltree(baseDir);
        }
    }
}
