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

package sshd;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.MapEntryUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.common.util.io.IoUtils;
import org.apache.sshd.common.util.logging.AbstractLoggingBean;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.channel.PuttyRequestHandler;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.shell.InvertedShell;
import org.apache.sshd.server.shell.TtyFilterInputStream;
import org.apache.sshd.server.shell.TtyFilterOutputStream;

class HomeProcessShell extends AbstractLoggingBean implements InvertedShell
{
    private final String homePath;
    private final List<String> command;
    private String cmdValue;
    private ServerSession session;
    private ChannelSession channelSession;
    private Process process;
    private TtyFilterOutputStream in;
    private TtyFilterInputStream out;
    private TtyFilterInputStream err;

    public HomeProcessShell(String homePath, Collection<String> command) {
        this.homePath = homePath;
        // we copy the original list so as not to change it
        this.command = new ArrayList<>(
            ValidateUtils.checkNotNullAndNotEmpty(command, "No process shell command(s)"));
        this.cmdValue = GenericUtils.join(command, ' ');
    }

    @Override
    public ServerSession getServerSession() {
        return session;
    }

    @Override
    public void setSession(ServerSession session) {
        this.session = Objects.requireNonNull(session, "No server session");
        ValidateUtils.checkTrue(process == null, "Session set after process started");
    }

    @Override
    public ChannelSession getServerChannelSession() {
        return channelSession;
    }

    @Override
    public void start(ChannelSession channel, Environment env) throws IOException
    {
        this.channelSession = channel;

        Map<String, String> varsMap = resolveShellEnvironment(env.getEnv());
        for (int i = 0; i < command.size(); i++) {
            String cmd = command.get(i);
            if ("$USER".equals(cmd)) {
                cmd = varsMap.get("USER");
                command.set(i, cmd);
                cmdValue = GenericUtils.join(command, ' ');
            }
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        if (MapEntryUtils.size(varsMap) > 0) {
            try {
                Map<String, String> procEnv = builder.environment();
                procEnv.putAll(varsMap);
            } catch (Exception e) {
                warn("start({}) - Failed ({}) to set environment for command={}: {}",
                    channel, e.getClass().getSimpleName(), cmdValue, e.getMessage(), e);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("start({}): command='{}', env={}",
                channel, builder.command(), builder.environment());
        }

        builder.directory(new File(homePath));

        Map<PtyMode, ?> modes = resolveShellTtyOptions(env.getPtyModes());
        try
        {
            process = builder.start();
            out = new TtyFilterInputStream(process.getInputStream(), modes);
            err = new TtyFilterInputStream(process.getErrorStream(), modes);
            in = new TtyFilterOutputStream(process.getOutputStream(), err, modes);
        }
        catch (IOException ioe)
        {
            out = new TtyFilterInputStream(InputStream.nullInputStream(), modes);
            ByteArrayInputStream errStream = new ByteArrayInputStream((ioe.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            err = new TtyFilterInputStream(errStream, modes);
            in = new TtyFilterOutputStream(OutputStream.nullOutputStream(), err, modes);
        }
    }

    protected Map<String, String> resolveShellEnvironment(Map<String, String> env) {
        return env;
    }

    // for some reason these modes provide best results BOTH with Linux SSH client and PUTTY
    protected Map<PtyMode, Integer> resolveShellTtyOptions(Map<PtyMode, Integer> modes) {
        if (PuttyRequestHandler.isPuttyClient(getServerSession())) {
            return PuttyRequestHandler.resolveShellTtyOptions(modes);
        } else {
            return modes;
        }
    }

    @Override
    public OutputStream getInputStream() {
        return in;
    }

    @Override
    public InputStream getOutputStream() {
        return out;
    }

    @Override
    public InputStream getErrorStream() {
        return err;
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    public int exitValue() {
        if (isAlive()) {
            try {
                return process.waitFor();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            return process == null ? 127 : process.exitValue();
        }
    }

    @Override
    public void destroy(ChannelSession channel) {
        // NOTE !!! DO NOT NULL-IFY THE PROCESS SINCE "exitValue" is called subsequently
        boolean debugEnabled = log.isDebugEnabled();
        if (process != null) {
            if (debugEnabled) {
                log.debug("destroy({}) Destroy process for '{}'", channel, cmdValue);
            }
            process.destroy();
        }

        IOException e = IoUtils.closeQuietly(getInputStream(), getOutputStream(), getErrorStream());
        if (e != null) {
            debug("destroy({}) {} while destroy streams of '{}': {}",
                channel, e.getClass().getSimpleName(), this, e.getMessage(), e);
        }
    }

    @Override
    public String toString() {
        return GenericUtils.isEmpty(cmdValue) ? super.toString() : cmdValue;
    }
}
