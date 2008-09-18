/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.p2.tests.reconciler.dropins;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.equinox.internal.p2.core.helpers.*;
import org.eclipse.equinox.internal.p2.update.*;
import org.eclipse.equinox.internal.provisional.p2.core.ProvisionException;
import org.eclipse.equinox.p2.tests.AbstractProvisioningTest;
import org.eclipse.equinox.p2.tests.TestActivator;
import org.eclipse.osgi.service.datalocation.Location;

public class AbstractReconcilerTest extends AbstractProvisioningTest {

	private static File output;
	protected static Set toRemove = new HashSet();

	/*
	 * Constructor for the class.
	 */
	public AbstractReconcilerTest(String name) {
		super(name);
	}

	/*
	 * Set up the platform binary download and get it ready to run the tests.
	 * This method is not intended to be called by clients, it will be called
	 * automatically when the clients use a ReconcilerTestSuite.
	 */
	public void initialize() throws Exception {
		File file = getPlatformZip();
		output = getTempFolder();
		toRemove.add(output);
		try {
			FileUtils.unzipFile(file, output);
		} catch (IOException e) {
			fail("0.99", e);
		}
	}

	/*
	 * Helper method to return the install location. Return null if it is unavailable.
	 */
	public static File getInstallLocation() {
		Location installLocation = (Location) ServiceHelper.getService(TestActivator.getContext(), Location.class.getName(), Location.INSTALL_FILTER);
		if (installLocation == null || !installLocation.isSet())
			return null;
		URL url = installLocation.getURL();
		if (url == null)
			return null;
		return URLUtil.toFile(url);
	}

	/*
	 * Return a file handle pointing to the platform binary zip. Method never returns null because
	 * it will fail an assert before that.
	 */
	private File getPlatformZip() {
		// Check to see if the user set a system property first
		String property = TestActivator.getContext().getProperty("org.eclipse.equinox.p2.reconciler.tests.platform.archive");
		File file = null;
		if (property == null) {
			// the releng test framework copies the zip so let's look for it...
			// it will be a sibling of the eclipse/ folder that we are running
			File installLocation = getInstallLocation();
			if (installLocation != null) {
				// parent will be "eclipse" and the parent's parent will be "eclipse-testing"
				File parent = installLocation.getParentFile();
				if (parent != null) {
					parent = parent.getParentFile();
					if (parent != null) {
						File[] children = parent.listFiles(new FileFilter() {
							public boolean accept(File pathname) {
								String name = pathname.getName();
								return name.startsWith("eclipse-platform-");
							}
						});
						if (children != null && children.length == 1)
							file = children[0];
					}
				}
			}
		} else {
			file = new File(property);
		}
		String message = "Need to set the \"org.eclipse.equinox.p2.reconciler.tests.platform.archive\" system property with a valid path to the platform binary drop or copy the archive to be a sibling of the install folder.";
		assertNotNull(message, file);
		assertTrue(message, file.exists());
		return file;
	}

	/*
	 * Add the given bundle to the given folder (do a copy).
	 * The folder can be one of dropins, plugins or features.
	 * If the file handle points to a directory, then do a deep copy.
	 */
	public void add(String message, String target, File file) {
		if (!(target.startsWith("dropins") || target.startsWith("plugins") || target.startsWith("features")))
			fail("Destination folder for resource copying should be either dropins, plugins or features.");
		File destinationParent = new File(output, "eclipse/" + target);
		destinationParent.mkdirs();
		copy(message, file, new File(destinationParent, file.getName()));
	}

	public void add(String message, String target, File[] files) {
		assertNotNull(files);
		for (int i = 0; i < files.length; i++)
			add(message, target, files[i]);
	}

	/*
	 * Remove the given filename from the given folder.
	 */
	public boolean remove(String message, String target, String filename) {
		if (!(target.startsWith("dropins") || target.startsWith("plugins") || target.startsWith("features")))
			fail("Target folder for resource deletion should be either dropins, plugins or features.");
		File folder = new File(output, "eclipse/" + target);
		File targetFile = new File(folder, filename);
		if (!targetFile.exists())
			return false;
		return delete(targetFile);
	}

	/*
	 * Remove the files with the given names from the target folder.
	 */
	public void remove(String message, String target, String[] names) {
		assertNotNull(names);
		for (int i = 0; i < names.length; i++)
			remove(message, target, names[i]);
	}

	/*
	 * Return a boolean value indicating whether or not a bundle with the given id
	 * is listed in the bundles.info file. Ignore the version number and return true
	 * if there are any matches in the file.
	 */
	public boolean isInBundlesInfo(String bundleId) throws IOException {
		return isInBundlesInfo(bundleId, null);
	}

	/*
	 * Return a boolean value indicating whether or not a bundle with the given id
	 * is listed in the bundles.info file. If the version is non-null, check to ensure the
	 * version is the expected one.
	 */
	public boolean isInBundlesInfo(String bundleId, String version) throws IOException {
		File bundlesInfo = new File(output, "eclipse/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info");
		if (!bundlesInfo.exists())
			return false;
		String line;
		Exception exception = null;
		BufferedReader reader = new BufferedReader(new FileReader(bundlesInfo));
		try {
			while ((line = reader.readLine()) != null) {
				StringTokenizer tokenizer = new StringTokenizer(line, ",");
				if (bundleId.equals(tokenizer.nextToken())) {
					if (version == null)
						return true;
					if (version.equals(tokenizer.nextToken()))
						return true;
				}
			}
		} catch (IOException e) {
			exception = e;
		} finally {
			try {
				reader.close();
			} catch (IOException ex) {
				if (exception == null)
					throw ex;
			}
		}
		return false;
	}

	/*
	 * Run the reconciler to discover changes in the drop-ins folder and update the system state.
	 */
	public void reconcile(String message) {
		String command = output.getAbsolutePath() + "/eclipse/eclipse -nosplash -application org.eclipse.equinox.p2.reconciler.application";
		try {
			Process process = Runtime.getRuntime().exec(command);
			process.waitFor();
		} catch (IOException e) {
			fail(message, e);
		} catch (InterruptedException e) {
			fail(message, e);
		}
	}

	/*
	 * If a bundle with the given id and version exists in the bundles.info file then
	 * throw an AssertionFailedException.
	 */
	public void assertDoesNotExistInBundlesInfo(String message, String bundleId, String version) {
		try {
			assertTrue(message, !isInBundlesInfo(bundleId, version));
		} catch (IOException e) {
			fail(message, e);
		}
	}

	/*
	 * If a bundle with the given id in the bundles.info file then throw an AssertionFailedException.
	 */
	public void assertDoesNotExistInBundlesInfo(String message, String bundleId) {
		assertDoesNotExistInBundlesInfo(message, bundleId, null);
	}

	/*
	 * If a bundle with the given id and version does not exist in the bundles.info file then
	 * throw an AssertionFailedException.
	 */
	public void assertExistsInBundlesInfo(String message, String bundleId, String version) {
		try {
			assertTrue(message, isInBundlesInfo(bundleId, version));
		} catch (IOException e) {
			fail(message, e);
		}
	}

	/*
	 * If a bundle with the given id does not exist in the bundles.info file then throw an AssertionFailedException.
	 */
	public void assertExistsInBundlesInfo(String message, String bundleId) {
		assertExistsInBundlesInfo(message, bundleId, null);
	}

	/*
	 * Clean up the temporary data used to run the tests.
	 * This method is not intended to be called by clients, it will be called
	 * automatically when the clients use a ReconcilerTestSuite.
	 */
	public void cleanup() throws Exception {
		// rm -rf eclipse sub-dir
		for (Iterator iter = toRemove.iterator(); iter.hasNext();) {
			File next = (File) iter.next();
			FileUtils.deleteAll(next);
		}
		output = null;
		toRemove.clear();
	}

	/*
	 * Read and return the configuration object. Will not return null.
	 */
	public Configuration getConfiguration() {
		File configLocation = new File(output, "eclipse/configuration/org.eclipse.update/platform.xml");
		File installLocation = new File(output, "eclipse");
		if (installLocation == null)
			fail("Unable to determine install location.");
		try {
			return Configuration.load(configLocation, installLocation.toURL());
		} catch (ProvisionException e) {
			fail("Error while reading configuration from " + configLocation);
		} catch (MalformedURLException e) {
			fail("Unable to convert install location to URL " + installLocation);
		}
		assertTrue("Unable to read configuration from " + configLocation, false);
		// avoid compiler error
		return null;
	}

	/*
	 * Save the given configuration to disk.
	 */
	public void save(String message, Configuration configuration) {
		File configLocation = new File(output, "eclipse/configuration/org.eclipse.update/platform.xml");
		File installLocation = new File(output, "eclipse");
		try {
			configuration.save(configLocation, installLocation.toURL());
		} catch (ProvisionException e) {
			fail(message, e);
		} catch (MalformedURLException e) {
			fail(message, e);
		}
	}

	/*
	 * Iterate over the sites in the given configuration and remove the one which
	 * has a url matching the given location.
	 */
	public boolean removeSite(Configuration configuration, String location) {
		IPath path = new Path(location);
		List sites = configuration.getSites();
		for (Iterator iter = sites.iterator(); iter.hasNext();) {
			Site tempSite = (Site) iter.next();
			String siteURL = tempSite.getUrl();
			if (path.equals(new Path(siteURL)))
				return configuration.removeSite(tempSite);
		}
		return false;
	}

	/*
	 * Create and return a new feature object with the given parameters.
	 */
	public Feature createFeature(Site site, String id, String version, String url) {
		Feature result = new Feature(site);
		result.setId(id);
		result.setVersion(version);
		result.setUrl(url);
		return result;
	}

	/*
	 * Create and return a new site object with the given parameters.
	 */
	public Site createSite(String policy, boolean enabled, boolean updateable, String url, String[] plugins) {
		Site result = new Site();
		result.setPolicy(policy);
		result.setEnabled(enabled);
		result.setUpdateable(updateable);
		result.setUrl(url);
		if (plugins != null)
			for (int i = 0; i < plugins.length; i++)
				result.addPlugin(plugins[i]);
		return result;
	}

	/*
	 * Assert that a feature with the given id exists in the configuration. If 
	 * a version is specified then match the version, otherwise any version will
	 * do.
	 */
	public void assertFeatureExists(String message, Configuration configuration, String id, String version) {
		List sites = configuration.getSites();
		assertNotNull(message, sites);
		boolean found = false;
		for (Iterator iter = sites.iterator(); iter.hasNext();) {
			Site site = (Site) iter.next();
			Feature[] features = site.getFeatures();
			for (int i = 0; features != null && i < features.length; i++) {
				if (id.equals(features[i].getId())) {
					if (version == null)
						found = true;
					else if (version.equals(features[i].getVersion()))
						found = true;
				}
			}
		}
		assertTrue(message, found);
	}
}