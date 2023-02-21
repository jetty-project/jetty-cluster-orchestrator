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
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ProcessHolder implements Serializable
{
    private final int pid;

    public static ProcessHolder from(Process process)
    {
        try
        {
            return new ProcessHolder(process);
        }
        catch (ClassNotFoundException | NoSuchMethodException e)
        {
            throw new IllegalStateException("Neither of the JDK 9+ ProcessHandle API nor the ZT Process Killer API is available", e);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private ProcessHolder(Process process) throws Exception
    {
        this.pid = asPid(process);
    }

    public int getPid()
    {
        return pid;
    }

    @Override
    public String toString()
    {
        return "ProcessHolder{" +
            "pid=" + pid +
            '}';
    }

    private int asPid(Process process) throws Exception
    {
        try
        {
            return asPidWithProcessHandle(process);
        }
        catch (ClassNotFoundException | NoSuchMethodException e)
        {
            return asPidWithZtProcessKiller(process);
        }
    }

    public boolean isAlive() throws Exception
    {
        try
        {
            return isAliveWithProcessHandle();
        }
        catch (ClassNotFoundException | NoSuchMethodException e)
        {
            return isAliveWithZtProcessKiller();
        }
    }

    public void destroy() throws Exception
    {
        try
        {
            destroyWithProcessHandle();
        }
        catch (ClassNotFoundException | NoSuchMethodException e)
        {
            destroyWithZtProcessKiller();
        }
    }

    private void destroyWithZtProcessKiller() throws Exception
    {
        Class<?> processesClass = Class.forName("org.zeroturnaround.process.Processes");
        Method newPidProcessMethod = processesClass.getDeclaredMethod("newPidProcess", int.class);
        Class<?> processUtilClass = Class.forName("org.zeroturnaround.process.ProcessUtil");
        Class<?> systemProcessClass = Class.forName("org.zeroturnaround.process.SystemProcess");
        Method destroyGracefullyOrForcefullyAndWaitMethod = processUtilClass.getDeclaredMethod("destroyGracefullyOrForcefullyAndWait", systemProcessClass, long.class, TimeUnit.class, long.class, TimeUnit.class);

        Object pidProcess = newPidProcessMethod.invoke(processesClass, pid);

        destroyGracefullyOrForcefullyAndWaitMethod.invoke(processUtilClass, pidProcess, 10L, TimeUnit.SECONDS, 10L, TimeUnit.SECONDS);
    }

    private void destroyWithProcessHandle() throws Exception
    {
        Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");
        Method ofMethod = processHandleClass.getDeclaredMethod("of", long.class);
        Method destroyMethod = processHandleClass.getDeclaredMethod("destroy");
        Method destroyForciblyMethod = processHandleClass.getDeclaredMethod("destroyForcibly");
        Method onExitMethod = processHandleClass.getDeclaredMethod("onExit");

        Optional<?> optProcessHandle = (Optional<?>)ofMethod.invoke(processHandleClass, (long) pid);
        if (optProcessHandle.isPresent())
        {
            Object processHandle = optProcessHandle.get();
            destroyMethod.invoke(processHandle);

            CompletableFuture<?> cf = (CompletableFuture)onExitMethod.invoke(processHandle);
            try
            {
                cf.get(10, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                destroyForciblyMethod.invoke(processHandle);
                try
                {
                    cf.get(10, TimeUnit.SECONDS);
                }
                catch (TimeoutException ex)
                {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    private boolean isAliveWithZtProcessKiller() throws Exception
    {
        Class<?> processesClass = Class.forName("org.zeroturnaround.process.Processes");
        Method newPidProcessMethod = processesClass.getDeclaredMethod("newPidProcess", int.class);
        Class<?> systemProcessClass = Class.forName("org.zeroturnaround.process.SystemProcess");
        Method isAliveMethod = systemProcessClass.getDeclaredMethod("isAlive");

        Object pidProcess = newPidProcessMethod.invoke(processesClass, pid);

        return (boolean)isAliveMethod.invoke(pidProcess);
    }

    private boolean isAliveWithProcessHandle() throws Exception
    {
        Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");
        Method ofMethod = processHandleClass.getDeclaredMethod("of", long.class);
        Method isAliveMethod = processHandleClass.getDeclaredMethod("isAlive");

        Optional<?> optProcessHandle = (Optional<?>)ofMethod.invoke(processHandleClass, (long) pid);
        if (optProcessHandle.isPresent())
        {
            Object processHandle = optProcessHandle.get();
            return (boolean)isAliveMethod.invoke(processHandle);
        }
        return false;
    }

    private int asPidWithZtProcessKiller(Process process) throws Exception
    {
        Class<?> processesClass = Class.forName("org.zeroturnaround.process.Processes");
        Method newPidProcessMethod = processesClass.getDeclaredMethod("newPidProcess", Process.class);
        Class<?> pidProcessClass = Class.forName("org.zeroturnaround.process.PidProcess");
        Method getPidMethod = pidProcessClass.getDeclaredMethod("getPid");

        Object pidProcess = newPidProcessMethod.invoke(processesClass, process);

        return (int)getPidMethod.invoke(pidProcess);
    }

    private int asPidWithProcessHandle(Process process) throws Exception
    {
        Method toHandleMethod = Process.class.getDeclaredMethod("toHandle");
        Object processHandle = toHandleMethod.invoke(process);

        Class<?> processHandleClass = Class.forName("java.lang.ProcessHandle");
        Method pidMethod = processHandleClass.getDeclaredMethod("pid");

        long pid = (long)pidMethod.invoke(processHandle);
        return (int)pid;
    }
}
