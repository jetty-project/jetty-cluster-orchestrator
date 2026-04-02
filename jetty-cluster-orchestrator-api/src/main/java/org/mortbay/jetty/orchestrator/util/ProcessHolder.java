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

import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ProcessHolder implements Serializable
{
    private final long pid;

    public static ProcessHolder from(Process process)
    {
        return new ProcessHolder(process.toHandle().pid());
    }

    private ProcessHolder(long pid)
    {
        this.pid = pid;
    }

    public long getPid()
    {
        return pid;
    }

    public boolean isAlive()
    {
        return ProcessHandle.of(pid)
            .map(ProcessHandle::isAlive)
            .orElse(false);
    }

    public void destroy() throws Exception
    {
        Optional<ProcessHandle> optional = ProcessHandle.of(pid);
        if (optional.isPresent())
        {
            ProcessHandle processHandle = optional.get();
            CompletableFuture<ProcessHandle> onExit = processHandle.onExit();
            processHandle.destroy();
            try
            {
                onExit.get(10, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                processHandle.destroyForcibly();
                onExit.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public String toString()
    {
        return "ProcessHolder{" +
            "pid=" + pid +
            '}';
    }
}
