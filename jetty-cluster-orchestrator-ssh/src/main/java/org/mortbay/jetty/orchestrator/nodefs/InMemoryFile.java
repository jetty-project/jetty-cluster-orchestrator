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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import net.schmizz.sshj.xfer.InMemoryDestFile;

class InMemoryFile extends InMemoryDestFile
{
    private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    // Method used by SFTP
    @Override
    public OutputStream getOutputStream(boolean append) throws IOException
    {
        return outputStream;
    }

    @Override
    public ByteArrayOutputStream getOutputStream()
    {
        return outputStream;
    }

    // Method used by SFTP
    @Override
    public long getLength()
    {
        return -1;
    }
}
