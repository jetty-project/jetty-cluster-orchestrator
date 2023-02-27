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
    private static final Impl IMPL;

    static
    {
        Impl impl;
        try
        {
            impl = new JDK9();
        }
        catch (Exception e)
        {
            try
            {
                impl = new ZtProcessKiller();
            }
            catch (Exception ex)
            {
                throw new IllegalStateException("Neither of the JDK 9+ ProcessHandle API nor the ZT Process Killer API is available", e);
            }
        }
        IMPL = impl;
    }

    private final int pid;

    public static ProcessHolder from(Process process)
    {
        try
        {
            return new ProcessHolder(IMPL.asPid(process));
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private ProcessHolder(int pid)
    {
        this.pid = pid;
    }

    public int getPid()
    {
        return pid;
    }

    public boolean isAlive() throws Exception
    {
        return IMPL.isAlive(pid);
    }

    public void destroy() throws Exception
    {
        IMPL.destroy(pid);
    }

    @Override
    public String toString()
    {
        return "ProcessHolder{" +
            "pid=" + pid +
            '}';
    }

    private static abstract class Impl
    {
        abstract int asPid(Process process) throws Exception;
        abstract boolean isAlive(int pid) throws Exception;
        abstract void destroy(int pid) throws Exception;
    }

    private static class JDK9 extends Impl
    {
        private final Class<?> processHandleClass;

        public JDK9() throws Exception
        {
            processHandleClass = Class.forName("java.lang.ProcessHandle");
        }

        @Override
        int asPid(Process process) throws Exception
        {
            Method toHandleMethod = Process.class.getDeclaredMethod("toHandle");
            Object processHandle = toHandleMethod.invoke(process);

            Method pidMethod = processHandleClass.getDeclaredMethod("pid");

            long pid = (long)pidMethod.invoke(processHandle);
            return (int)pid;
        }

        @Override
        boolean isAlive(int pid) throws Exception
        {
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

        @Override
        void destroy(int pid) throws Exception
        {
            Method ofMethod = processHandleClass.getDeclaredMethod("of", long.class);
            Method destroyMethod = processHandleClass.getDeclaredMethod("destroy");
            Method destroyForciblyMethod = processHandleClass.getDeclaredMethod("destroyForcibly");
            Method onExitMethod = processHandleClass.getDeclaredMethod("onExit");

            Optional<?> optProcessHandle = (Optional<?>)ofMethod.invoke(processHandleClass, (long) pid);
            if (optProcessHandle.isPresent())
            {
                Object processHandle = optProcessHandle.get();
                destroyMethod.invoke(processHandle);

                CompletableFuture<?> cf = (CompletableFuture<?>)onExitMethod.invoke(processHandle);
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
    }

    private static class ZtProcessKiller extends Impl
    {
        private final Class<?> processesClass;
        private final Class<?> pidProcessClass;
        private final Class<?> systemProcessClass;
        private final Class<?> processUtilClass;

        public ZtProcessKiller() throws Exception
        {
            processesClass = Class.forName("org.zeroturnaround.process.Processes");
            pidProcessClass = Class.forName("org.zeroturnaround.process.PidProcess");
            systemProcessClass = Class.forName("org.zeroturnaround.process.SystemProcess");
            processUtilClass = Class.forName("org.zeroturnaround.process.ProcessUtil");
        }

        @Override
        int asPid(Process process) throws Exception
        {
            Method newPidProcessMethod = processesClass.getDeclaredMethod("newPidProcess", Process.class);
            Method getPidMethod = pidProcessClass.getDeclaredMethod("getPid");

            Object pidProcess = newPidProcessMethod.invoke(processesClass, process);

            return (int)getPidMethod.invoke(pidProcess);
        }


        @Override
        boolean isAlive(int pid) throws Exception
        {
            Method newPidProcessMethod = processesClass.getDeclaredMethod("newPidProcess", int.class);
            Method isAliveMethod = systemProcessClass.getDeclaredMethod("isAlive");

            Object pidProcess = newPidProcessMethod.invoke(processesClass, pid);

            return (boolean)isAliveMethod.invoke(pidProcess);
        }

        @Override
        void destroy(int pid) throws Exception
        {
            Method newPidProcessMethod = processesClass.getDeclaredMethod("newPidProcess", int.class);
            Method destroyGracefullyOrForcefullyAndWaitMethod = processUtilClass.getDeclaredMethod("destroyGracefullyOrForcefullyAndWait", systemProcessClass, long.class, TimeUnit.class, long.class, TimeUnit.class);

            Object pidProcess = newPidProcessMethod.invoke(processesClass, pid);

            destroyGracefullyOrForcefullyAndWaitMethod.invoke(processUtilClass, pidProcess, 10L, TimeUnit.SECONDS, 10L, TimeUnit.SECONDS);
        }
    }
}
