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

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import java.io.InputStream;
import java.io.Serializable;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@FunctionalInterface
public interface FilenameSupplier extends Serializable {
    String get(FileSystem fileSystem, String hostname);

    class CurrentJvm implements FilenameSupplier {
        @Override
        public String get(FileSystem fileSystem, String hostname) {
            Path javaExec = JvmUtil.findCurrentJavaExecutable();
            if (javaExec == null) throw new IllegalStateException("Cannot find executable java command of current JVM");
            return javaExec.toAbsolutePath().toString();
        }

        @Override
        public String toString() {
            return "FilenameSupplier.CurrentJvm{path=" + get(null, null) + "}";
        }
    }

    class MavenToolchains implements FilenameSupplier {
        private static final Logger LOG = LoggerFactory.getLogger(MavenToolchains.class);

        private final String version;

        public MavenToolchains(String version) {
            this.version = version;
        }

        @Override
        public String get(FileSystem fileSystem, String hostname) {
            try {
                String jdkHome = findJavaHomeFromToolchain(fileSystem);
                if (jdkHome != null) {
                    LOG.debug("host '{}' found jdkHome '{}' from toolchain", hostname, jdkHome);
                    Path javaExec = JvmUtil.findJavaExecutable(Paths.get(jdkHome));
                    if (javaExec != null) {
                        // it's coming from toolchains so we trust the result
                        String absolutePath = javaExec.toAbsolutePath().toString();
                        if (LOG.isDebugEnabled())
                            LOG.debug("host '{}' will use java executable {}", hostname, absolutePath);
                        return absolutePath;
                    }
                }
                throw new RuntimeException("Toolchains JDK '" + version + "' not found for host " + hostname);
            } catch (Exception x) {
                throw new RuntimeException(
                        "Error looking for toolchains JDK '" + version + "' for host " + hostname, x);
            }
        }

        protected String findJavaHomeFromToolchain(FileSystem fileSystem) throws Exception {
            Path toolchainsPath = fileSystem.getPath(System.getProperty("user.home"), ".m2", "toolchains.xml");
            // This file is generated from sdkman installations by: mvn
            // org.apache.maven.plugins:maven-toolchains-plugin:3.2.0:generate-jdk-toolchains-xml
            if (!Files.exists(toolchainsPath))
                toolchainsPath = fileSystem.getPath(
                        System.getProperty("user.home"), ".m2", "discovered-jdk-toolchains-cache.xml");

            if (Files.exists(toolchainsPath)) {
                try (InputStream is = Files.newInputStream(toolchainsPath)) {
                    DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder builder = builderFactory.newDocumentBuilder();
                    Document xmlDocument = builder.parse(is);
                    XPath xPath = XPathFactory.newInstance().newXPath();
                    NodeList nodeList = (NodeList)
                            xPath.compile("/toolchains/toolchain").evaluate(xmlDocument, XPathConstants.NODESET);
                    for (int i = 0; i < nodeList.getLength(); i++) {
                        Node node = nodeList.item(i);
                        String version =
                                (String) xPath.compile("provides/version").evaluate(node, XPathConstants.STRING);

                        if (versionMatch(this.version, version)) {
                            String jdkHome = (String)
                                    xPath.compile("configuration/jdkHome").evaluate(node, XPathConstants.STRING);
                            if (LOG.isDebugEnabled())
                                LOG.debug("Found matching JDK: version {} at {}", version, jdkHome);
                            return jdkHome;
                        }
                    }
                    return null;
                }
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("cannot find toolchain file {}", toolchainsPath);
                    try (Stream<Path> stream = Files.list(Paths.get("."))) {
                        LOG.debug("files in directory: {}", stream.collect(Collectors.toList()));
                    }
                }
                return null;
            }
        }

        private boolean versionMatch(String expectedVersion, String foundVersion) {
            return new MavenVersion(expectedVersion).matches(foundVersion);
        }

        static class MavenVersion {
            private final boolean atLeast;
            private final String rawMajor;
            private final String rawMinor;
            private final String rawMicro;
            private final String rawRemaining;

            public MavenVersion(String rawVersion) {
                this.atLeast = rawVersion.startsWith("[");
                String v = atLeast ? rawVersion.substring(1) : rawVersion;
                String[] parts = v.split("\\.", 4);
                this.rawMajor = parts.length > 0 ? parts[0] : null;
                this.rawMinor = parts.length > 1 ? parts[1] : null;
                this.rawMicro = parts.length > 2 ? parts[2] : null;
                this.rawRemaining = parts.length > 3 ? parts[3] : null;
            }

            public boolean matches(String givenVersion) {
                MavenVersion given = new MavenVersion(givenVersion);
                if (atLeast) return matchesAtLeast(given);
                return matchesExactly(given);
            }

            private boolean matchesExactly(MavenVersion given) {
                return componentEquals(rawMajor, given.rawMajor)
                        && componentEquals(rawMinor, given.rawMinor)
                        && componentEquals(rawMicro, given.rawMicro);
            }

            private static boolean componentEquals(String expected, String given) {
                if (expected == null) return true;
                if (given == null) return false;
                return leadingInt(expected) == leadingInt(given);
            }

            private boolean matchesAtLeast(MavenVersion given) {
                String[] expectedComponents = {rawMajor, rawMinor, rawMicro};
                String[] givenComponents = {given.rawMajor, given.rawMinor, given.rawMicro};
                for (int i = 0; i < expectedComponents.length; i++) {
                    if (expectedComponents[i] == null) return true;
                    int expected = leadingInt(expectedComponents[i]);
                    int actual = leadingInt(givenComponents[i]);
                    if (expected != actual) return actual > expected;
                }
                return true;
            }

            private static int leadingInt(String s) {
                if (s == null) return 0;
                int i = 0;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
            }

            @Override
            public String toString() {
                return "MavenVersion{" + "atLeast="
                        + atLeast + ", rawMajor='"
                        + rawMajor + '\'' + ", rawMinor='"
                        + rawMinor + '\'' + ", rawMicro='"
                        + rawMicro + '\'' + ", rawRemaining='"
                        + rawRemaining + '\'' + '}';
            }
        }

        @Override
        public String toString() {
            return "FilenameSupplier.MavenToolchains{version='" + version + "'}";
        }
    }

    class Combined implements FilenameSupplier {
        private final FilenameSupplier filenameSupplier1;
        private final FilenameSupplier filenameSupplier2;

        public Combined(FilenameSupplier filenameSupplier1, FilenameSupplier filenameSupplier2) {
            this.filenameSupplier1 = filenameSupplier1;
            this.filenameSupplier2 = filenameSupplier2;
        }

        @Override
        public String get(FileSystem fileSystem, String hostname) {
            String path = filenameSupplier1.get(fileSystem, hostname);
            if (path != null) return path;
            return filenameSupplier2.get(fileSystem, hostname);
        }

        @Override
        public String toString() {
            return "FilenameSupplier.Combined{" + "s1=" + filenameSupplier1 + ", s2=" + filenameSupplier2 + '}';
        }
    }
}
