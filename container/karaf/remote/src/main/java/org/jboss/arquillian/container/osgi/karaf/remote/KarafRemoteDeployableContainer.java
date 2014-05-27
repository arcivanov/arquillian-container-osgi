/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.container.osgi.karaf.remote;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.jboss.arquillian.container.osgi.CommonDeployableContainer;
import org.jboss.arquillian.container.osgi.EmbeddedDeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.JMXContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.context.annotation.ContainerScoped;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.osgi.spi.BundleInfo;
import org.jboss.osgi.vfs.AbstractVFS;
import org.jboss.osgi.vfs.VFSUtils;
import org.jboss.osgi.vfs.VirtualFile;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.osgi.framework.BundleException;
import org.osgi.jmx.framework.BundleStateMBean;
import org.osgi.jmx.framework.FrameworkMBean;
import org.osgi.jmx.framework.ServiceStateMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KarafRemoteDeployableContainer
 *
 * @author thomas.diesler@jboss.com
 * @author sbunciak@redhat.com
 */
public class KarafRemoteDeployableContainer<T extends KarafRemoteContainerConfiguration> extends
        CommonDeployableContainer<T> {

    @Inject
    @ContainerScoped
    private InstanceProducer<MBeanServerConnection> mbeanServerInstance;
    private final Map<String, BundleHandle> deployedBundles = new HashMap<String, BundleHandle>();
    private KarafRemoteContainerConfiguration config;

    private FrameworkMBean frameworkMBean;
    private BundleStateMBean bundleStateMBean;
    private ServiceStateMBean serviceStateMBean;

    static final Logger LOGGER = LoggerFactory.getLogger(KarafRemoteDeployableContainer.class.getPackage().getName());

    @Override
    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {

        try {
            BundleHandle handle = installBundle(archive);
            deployedBundles.put(archive.getName(), handle);
        } catch (RuntimeException rte) {
            throw rte;
        } catch (Exception ex) {
            throw new DeploymentException("Cannot deploy: " + archive.getName(), ex);
        }

        MBeanServerConnection mbeanServer = mbeanServerInstance.get();
        return new ProtocolMetaData().addContext(new JMXContext(mbeanServer));
    }

    @Override
    public Class<T> getConfigurationClass() {
        @SuppressWarnings("unchecked")
        Class<T> clazz = (Class<T>) KarafRemoteContainerConfiguration.class;
        return clazz;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("jmx-osgi");
    }

    @Override
    public void setup(T config) {
        super.setup(config);
        this.config = config;
    }

    @Override
    public void start() throws LifecycleException {
        // In the case of remote container adapters, this is ideally the place
        // to verify if the container is running, along with any other necessary
        // validations.

        MBeanServerConnection mbeanServer = null;

        // Try to connect to an already running server
        try {
            mbeanServer = getMBeanServerConnection(30, TimeUnit.SECONDS);
            mbeanServerInstance.set(mbeanServer);
        } catch (TimeoutException e) {
            LOGGER.error("Error connecting to Karaf MBeanServer: ", e);
        }

        try {
            // Get the FrameworkMBean
            ObjectName oname = new ObjectName("osgi.core:type=framework,*");
            frameworkMBean = getMBeanProxy(mbeanServer, oname, FrameworkMBean.class, 30, TimeUnit.SECONDS);

            // Get the BundleStateMBean
            oname = new ObjectName("osgi.core:type=bundleState,*");
            bundleStateMBean = getMBeanProxy(mbeanServer, oname, BundleStateMBean.class, 30, TimeUnit.SECONDS);

            // Get the ServiceStateMBean
            oname = new ObjectName("osgi.core:type=serviceState,*");
            serviceStateMBean = getMBeanProxy(mbeanServer, oname, ServiceStateMBean.class, 30, TimeUnit.SECONDS);

            // Install the arquillian bundle to become active
            installArquillianBundle();

            // Await the arquillian bundle to become active
            awaitArquillianBundleActive(30, TimeUnit.SECONDS);

            // Await the bootstrap complete marker service to become available
            String completeService = config.getBootstrapCompleteService();
            if (completeService != null)
                awaitBootstrapCompleteService(completeService, 30, TimeUnit.SECONDS);
        } catch (RuntimeException rte) {
            throw rte;
        } catch (Exception ex) {
            throw new LifecycleException("Cannot start Karaf container", ex);
        }
    }

    @Override
    public void stop() throws LifecycleException {
        // Intentionally left blank, at the moment
        // Any cleanup operations can be performed in this method
    }

    @Override
    public void undeploy(Archive<?> archive) throws DeploymentException {
        BundleHandle handle = deployedBundles.remove(archive.getName());
        if (handle != null) {
            String bundleState = null;
            try {
                long bundleId = handle.getBundleId();
                CompositeData bundleType = bundleStateMBean.getBundle(bundleId);
                if (bundleType != null) {
                    bundleState = (String) bundleType.get(BundleStateMBean.STATE);
                }
            } catch (IOException e) {
                // ignore non-existent bundle
                return;
            }
            if (bundleState != null && !bundleState.equals(BundleStateMBean.UNINSTALLED)) {
                try {
                    long bundleId = handle.getBundleId();
                    frameworkMBean.uninstallBundle(bundleId);
                } catch (IOException ex) {
                    LOGGER.error("Cannot undeploy: " + archive.getName(), ex);
                }
            }
        }
    }

    @Override
    public void undeploy(Descriptor arg0) throws DeploymentException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deploy(Descriptor arg0) throws DeploymentException {
        throw new UnsupportedOperationException();
    }

    /*
     * ================= Helper methods =================
     *
     * @see {@link org.jboss.arquillian.container.osgi.karaf.managed#
     * KarafManagedDeployableContainer}
     */

    static class BundleHandle {
        private long bundleId;
        private String symbolicName;

        BundleHandle(long bundleId, String symbolicName) {
            this.bundleId = bundleId;
            this.symbolicName = symbolicName;
        }

        long getBundleId() {
            return bundleId;
        }

        String getSymbolicName() {
            return symbolicName;
        }

        @Override
        public String toString() {
            return "[" + bundleId + "]" + symbolicName;
        }
    }

    private void awaitBootstrapCompleteService(String serviceName, long timeout, TimeUnit unit) throws TimeoutException, InterruptedException, IOException {
        long timeoutMillis = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < timeoutMillis) {
            TabularData list = serviceStateMBean.listServices(serviceName, null);
            if (list.size() > 0) {
                return;
            } else {
                Thread.sleep(500);
            }
        }
        throw new TimeoutException("Cannot obtain service: " + serviceName);
    }

    private MBeanServerConnection getMBeanServerConnection(final long timeout, final TimeUnit unit)
            throws TimeoutException {
        Callable<MBeanServerConnection> callable = new Callable<MBeanServerConnection>() {
            @Override
            public MBeanServerConnection call() throws Exception {
                Exception lastException = null;
                long timeoutMillis = System.currentTimeMillis() + unit.toMillis(timeout);
                while (System.currentTimeMillis() < timeoutMillis) {
                    try {
                        return getMBeanServerConnection();
                    } catch (Exception ex) {
                        lastException = ex;
                        Thread.sleep(500);
                    }
                }
                TimeoutException timeoutException = new TimeoutException();
                timeoutException.initCause(lastException);
                throw timeoutException;
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<MBeanServerConnection> future = executor.submit(callable);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private MBeanServerConnection getMBeanServerConnection() throws IOException {
        String[] credentials = new String[] { config.getJmxUsername(), config.getJmxPassword() };
        Map<String, ?> env = Collections.singletonMap(JMXConnector.CREDENTIALS, credentials);
        JMXServiceURL serviceURL = new JMXServiceURL(config.getJmxServiceURL());
        JMXConnector connector = JMXConnectorFactory.connect(serviceURL, env);
        return connector.getMBeanServerConnection();
    }

    private BundleHandle installBundle(Archive<?> archive) throws BundleException, IOException {
        VirtualFile virtualFile = toVirtualFile(archive);
        try {
            return installBundle(archive.getName(), virtualFile);
        } finally {
            VFSUtils.safeClose(virtualFile);
        }
    }

    private BundleHandle installBundle(String location, VirtualFile virtualFile) throws BundleException, IOException {
        BundleInfo info = BundleInfo.createBundleInfo(virtualFile);
        URL streamURL = info.getRoot().getStreamURL();
        return installBundle(location, streamURL);
    }

    private BundleHandle installBundle(String location, URL streamURL) throws BundleException, IOException {
        long bundleId = frameworkMBean.installBundleFromURL(location, streamURL.toExternalForm());
        String symbolicName = bundleStateMBean.getSymbolicName(bundleId);
        return new BundleHandle(bundleId, symbolicName);
    }

    private VirtualFile toVirtualFile(Archive<?> archive) throws IOException {
        ZipExporter exporter = archive.as(ZipExporter.class);
        return AbstractVFS.toVirtualFile(archive.getName(), exporter.exportAsInputStream());
    }

    private <T> T getMBeanProxy(final MBeanServerConnection mbeanServer, final ObjectName oname, final Class<T> type,
            final long timeout, final TimeUnit unit) throws TimeoutException {

        Callable<T> callable = new Callable<T>() {
            @Override
            public T call() throws Exception {
                IOException lastException = null;
                long timeoutMillis = System.currentTimeMillis() + unit.toMillis(timeout);
                while (System.currentTimeMillis() < timeoutMillis) {
                    Set<ObjectName> names = mbeanServer.queryNames(oname, null);
                    if (names.size() == 1) {
                        ObjectName instanceName = names.iterator().next();
                        return MBeanServerInvocationHandler.newProxyInstance(mbeanServer, instanceName, type, false);
                    } else {
                        Thread.sleep(500);
                    }
                }
                LOGGER.warn("Cannot get MBean proxy for type: " + oname, lastException);
                throw new TimeoutException();
            }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(callable);
        try {
            return future.get(timeout, unit);
        } catch (TimeoutException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    protected void installArquillianBundle() throws LifecycleException, IOException {

        List<BundleHandle> bundleList = listBundles("arquillian-osgi-bundle");
        if (bundleList.isEmpty()) {
            try {
                // Note, the bundle does not have an ImplementationVersion, we
                // use the one of the container.
                String arqVersion = EmbeddedDeployableContainer.class.getPackage().getImplementationVersion();
                if (arqVersion == null) {
                    arqVersion = System.getProperty("arquillian.osgi.version");
                }
                installBundle("org.jboss.arquillian.osgi", "arquillian-osgi-bundle", arqVersion, true);
            } catch (BundleException ex) {
                throw new LifecycleException("Cannot install arquillian-osgi-bundle", ex);
            }
        }
    }

    private void awaitArquillianBundleActive(long timeout, TimeUnit unit) throws IOException, TimeoutException,
            InterruptedException {

        String symbolicName = "arquillian-osgi-bundle";
        List<BundleHandle> list = listBundles(symbolicName);
        if (list.size() != 1)
            throw new IllegalStateException("Cannot obtain: " + symbolicName);

        String bundleState = null;
        long bundleId = list.get(0).getBundleId();
        long timeoutMillis = System.currentTimeMillis() + unit.toMillis(timeout);
        while (System.currentTimeMillis() < timeoutMillis) {
            bundleState = bundleStateMBean.getState(bundleId);
            if (BundleStateMBean.ACTIVE.equals(bundleState)) {
                return;
            } else {
                Thread.sleep(500);
            }
        }
        throw new TimeoutException("Arquillian bundle [" + bundleId + "] not started: " + bundleState);
    }

    private List<BundleHandle> listBundles(String symbolicName) throws IOException {
        List<BundleHandle> bundleList = new ArrayList<BundleHandle>();
        TabularData listBundles = bundleStateMBean.listBundles();
        Iterator<?> iterator = listBundles.values().iterator();
        while (iterator.hasNext()) {
            CompositeData bundleType = (CompositeData) iterator.next();
            Long bundleId = (Long) bundleType.get(BundleStateMBean.IDENTIFIER);
            String auxName = (String) bundleType.get(BundleStateMBean.SYMBOLIC_NAME);
            if (symbolicName == null || symbolicName.equals(auxName)) {
                bundleList.add(new BundleHandle(bundleId, symbolicName));
            }
        }
        return bundleList;
    }

    private BundleHandle installBundle(String groupId, String artifactId, String version, boolean startBundle)
            throws BundleException, IOException {
        String filespec = groupId + ":" + artifactId + ":jar:" + version;
        File[] resolved = Maven.resolver().resolve(filespec).withoutTransitivity().asFile();
        if (resolved == null || resolved.length == 0)
            throw new BundleException("Cannot obtain maven artifact: " + filespec);
        if (resolved.length > 1)
            throw new BundleException("Multiple maven artifacts for: " + filespec);

        URL fileURL = resolved[0].toURI().toURL();

        long bundleId = frameworkMBean.installBundleFromURL(filespec, fileURL.toExternalForm());
        String symbolicName = bundleStateMBean.getSymbolicName(bundleId);
        BundleHandle handle = new BundleHandle(bundleId, symbolicName);

        if (startBundle) {
            frameworkMBean.startBundle(handle.getBundleId());
        }
        return handle;
    }

    @Override
    public void startBundle(String symbolicName, String version) throws Exception {
        BundleHandle bHandle = getBundle(symbolicName, version);
        if (bHandle == null) {
            throw new IllegalStateException("Bundle '" + symbolicName + ":" + version + "' was not found");
        }
        frameworkMBean.startBundle(bHandle.getBundleId());
    }

    private BundleHandle getBundle(String symbolicName, String version) throws Exception {
        TabularData listBundles = bundleStateMBean.listBundles();
        Iterator<?> iterator = listBundles.values().iterator();
        while (iterator.hasNext()) {
            CompositeData bundleType = (CompositeData) iterator.next();
            Long bundleId = (Long) bundleType.get(BundleStateMBean.IDENTIFIER);
            String auxName = (String) bundleType.get(BundleStateMBean.SYMBOLIC_NAME);
            String auxVersion = (String) bundleType.get(BundleStateMBean.VERSION);
            if (symbolicName.equals(auxName) && version.equals(auxVersion)) {
                return new BundleHandle(bundleId, symbolicName);
            }
        }
        return null;
    }
}
