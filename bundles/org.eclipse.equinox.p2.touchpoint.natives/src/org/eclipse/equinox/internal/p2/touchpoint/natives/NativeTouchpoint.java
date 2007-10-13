/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.touchpoint.natives;

import java.io.File;
import java.net.URL;
import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.equinox.p2.artifact.repository.*;
import org.eclipse.equinox.p2.core.helpers.ServiceHelper;
import org.eclipse.equinox.p2.core.location.AgentLocation;
import org.eclipse.equinox.p2.engine.*;
import org.eclipse.equinox.p2.metadata.*;
import org.osgi.framework.Version;

public class NativeTouchpoint extends Touchpoint {
	//	private final static String CONFIGURATION_DATA = "configurationData";
	private static final String ID = "org.eclipse.equinox.p2.touchpoint.natives"; //$NON-NLS-1$

	private final Set supportedPhases = new HashSet(); //TODO This should probably come from XML
	{
		supportedPhases.add("collect");
		supportedPhases.add("install");
		supportedPhases.add("uninstall");
	}

	public boolean supports(String phaseId) {
		return supportedPhases.contains(phaseId);
	}

	public ProvisioningAction getAction(String actionId) {
		if (actionId.equals("collect")) {
			return new ProvisioningAction() {
				public IStatus execute(Map parameters) {
					Profile profile = (Profile) parameters.get("profile");
					Operand operand = (Operand) parameters.get("operand");
					IArtifactRequest[] requests = collect(operand.second(), profile);
					Collection artifactRequests = (Collection) parameters.get("artifactRequests");
					artifactRequests.add(requests);
					return Status.OK_STATUS;
				}

				public IStatus undo(Map parameters) {
					// nothing to do for now
					return Status.OK_STATUS;
				}
			};
		}

		if (actionId.equals("unzip")) {
			return new ProvisioningAction() {
				public IStatus execute(Map parameters) {
					String source = (String) parameters.get("source");
					String target = (String) parameters.get("target");

					if (source.equals("@artifact")) {
						IInstallableUnit iu = (IInstallableUnit) parameters.get("iu");
						//TODO: fix wherever this occurs -- investigate as this is probably not desired
						if (iu.getArtifacts() == null || iu.getArtifacts().length == 0)
							return Status.OK_STATUS;

						IArtifactKey artifactKey = iu.getArtifacts()[0];

						IFileArtifactRepository downloadCache = getDownloadCacheRepo();
						File fileLocation = downloadCache.getArtifactFile(artifactKey);
						if (!fileLocation.exists())
							return new Status(IStatus.ERROR, ID, "The file is not available" + fileLocation.getAbsolutePath());
						source = fileLocation.getAbsolutePath();
					}

					new Zip().unzip(source, target, null);
					return Status.OK_STATUS;
				}

				public IStatus undo(Map parameters) {
					//TODO: implement undo
					return Status.OK_STATUS;
				}
			};
		}
		if (actionId.equals("chmod")) {
			return new ProvisioningAction() {
				public IStatus execute(Map parameters) {
					String targetDir = (String) parameters.get("targetDir");
					String targetFile = (String) parameters.get("targetFile");
					String permissions = (String) parameters.get("permissions");

					new Permissions().chmod(targetDir, targetFile, null);
					return Status.OK_STATUS;
				}

				public IStatus undo(Map parameters) {
					//TODO: implement undo ??
					return Status.OK_STATUS;
				}
			};
		}

		return null;
	}

	public TouchpointType getTouchpointType() {
		return new TouchpointType("native", new Version(1, 0, 0));
	}

	private IArtifactRequest[] collect(IInstallableUnit installableUnit, Profile profile) {
		IArtifactRepository destination = getDownloadCacheRepo();
		IArtifactKey[] toDownload = installableUnit.getArtifacts();
		if (toDownload == null)
			return new IArtifactRequest[0];
		IArtifactRequest[] requests = new IArtifactRequest[toDownload.length];
		int count = 0;
		for (int i = 0; i < toDownload.length; i++) {
			//TODO Here there are cases where the download is not necessary again because what needs to be done is just a configuration step
			requests[count++] = getArtifactRepositoryManager().createMirrorRequest(toDownload[i], destination);
		}

		if (requests.length == count)
			return requests;
		IArtifactRequest[] result = new IArtifactRequest[count];
		System.arraycopy(requests, 0, result, 0, count);
		return result;
	}

	private String getInstallFolder(Profile profile) {
		return profile.getValue(Profile.PROP_INSTALL_FOLDER);
	}

	private static AgentLocation getAgentLocation() {
		return (AgentLocation) ServiceHelper.getService(Activator.getContext(), AgentLocation.class.getName());
	}

	static private IArtifactRepositoryManager getArtifactRepositoryManager() {
		return (IArtifactRepositoryManager) ServiceHelper.getService(Activator.getContext(), IArtifactRepositoryManager.class.getName());
	}

	static private void tagAsImplementation(IArtifactRepository repository) {
		//		if (repository != null && repository.getProperties().getProperty(IRepositoryInfo.IMPLEMENTATION_ONLY_KEY) == null) {
		//			IWritableRepositoryInfo writableInfo = (IWritableRepositoryInfo) repository.getAdapter(IWritableRepositoryInfo.class);
		//			if (writableInfo != null) {
		//				writableInfo.getModifiableProperties().setProperty(IRepositoryInfo.IMPLEMENTATION_ONLY_KEY, Boolean.valueOf(true).toString());
		//			}
		//		}
	}

	static private IFileArtifactRepository getDownloadCacheRepo() {
		URL location = getDownloadCacheLocation();
		IArtifactRepositoryManager manager = getArtifactRepositoryManager();
		IArtifactRepository repository = manager.loadRepository(location, null);
		if (repository == null) {
			// 	the given repo location is not an existing repo so we have to create something
			// TODO for now create a random repo by default.
			String repositoryName = location + " - Agent download cache"; //$NON-NLS-1$
			repository = manager.createRepository(location, repositoryName, "org.eclipse.equinox.p2.artifact.repository.simpleRepository");
			// TODO: do we still need to do this
			tagAsImplementation(repository);
		}

		IFileArtifactRepository downloadCache = (IFileArtifactRepository) repository.getAdapter(IFileArtifactRepository.class);
		if (downloadCache == null) {
			throw new IllegalArgumentException("Agent download cache not writeable: " + location); //$NON-NLS-1$
		}
		return downloadCache;
	}

	static private URL getDownloadCacheLocation() {
		AgentLocation location = getAgentLocation();
		return (location != null ? location.getArtifactRepositoryURL() : null);
	}

	public IStatus initializePhase(IProgressMonitor monitor, Profile profile, String phaseId, Map touchpointParameters) {
		touchpointParameters.put("installFolder", getInstallFolder(profile));
		return null;
	}
}
