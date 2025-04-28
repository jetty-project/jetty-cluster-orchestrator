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
import org.apache.curator.test.TestingServer;

public class ZooKeeperServer implements Closeable
{
    // TODO this requires curator-test; get rid of that dependency by
    //  manually building an embedded ZK server.
    private final TestingServer testingServer;

    public ZooKeeperServer() throws Exception
    {
        testingServer = new TestingServer(true);
    }

    public int getPort()
    {
        return testingServer.getPort();
    }

    @Override
    public void close() throws IOException
    {
        testingServer.close();
    }
}
