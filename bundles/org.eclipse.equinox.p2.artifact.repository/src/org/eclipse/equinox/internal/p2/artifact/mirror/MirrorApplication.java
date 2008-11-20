/*******************************************************************************
 * Copyright (c) 2007, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.p2.artifact.mirror;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.equinox.internal.p2.artifact.processors.md5.MD5ArtifactComparator;
import org.eclipse.equinox.internal.p2.artifact.repository.*;
import org.eclipse.equinox.internal.p2.core.helpers.LogHelper;
import org.eclipse.equinox.internal.p2.core.helpers.ServiceHelper;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.IArtifactRepository;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.IArtifactRepositoryManager;
import org.eclipse.equinox.internal.provisional.p2.core.ProvisionException;
import org.eclipse.osgi.util.NLS;

/**
 * An application that performs mirroring of artifacts between repositories.
 */
public class MirrorApplication implements IApplication {

	private URI sourceLocation;
	private URI destinationLocation;
	private IArtifactRepository source;
	private IArtifactRepository destination;
	private boolean append = false;
	private boolean raw = false;
	private boolean failOnError = true;
	private boolean verbose = false;
	private IArtifactRepositoryManager cachedManager;
	private boolean sourceLoaded = false;
	private boolean destinationLoaded = false;
	private boolean compare = false;
	private String comparatorID = MD5ArtifactComparator.MD5_COMPARATOR_ID; //use MD5 as default

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.app.IApplication#start(org.eclipse.equinox.app.IApplicationContext)
	 */
	public Object start(IApplicationContext context) throws Exception {
		Map args = context.getArguments();
		initializeFromArguments((String[]) args.get(IApplicationContext.APPLICATION_ARGS));
		setupRepositories();

		Mirroring mirroring = new Mirroring(source, destination, raw);
		mirroring.setCompare(compare);
		mirroring.setComparatorId(comparatorID);

		IStatus result = mirroring.run(failOnError, verbose);
		IStatus[] children = result.getChildren();
		for (int i = 0; i < children.length; i++) {
			if (verbose && !children[i].isOK()) {
				System.err.println(children[i].getMessage());
				if (children[i].getSeverity() == IStatus.ERROR)
					LogHelper.log(children[i]);
			}
		}
		//if the repository was not already loaded before the mirror application started, close it.
		if (!sourceLoaded)
			getManager().removeRepository(sourceLocation);
		if (!destinationLoaded)
			getManager().removeRepository(destinationLocation);

		return IApplication.EXIT_OK;
	}

	/*
	 * Return the artifact repository manager. We need to check the service here
	 * as well as creating one manually in case we are running a stand-alone application
	 * in which no one has registered a manager yet.
	 */
	private IArtifactRepositoryManager getManager() {
		if (cachedManager != null)
			return cachedManager;
		IArtifactRepositoryManager result = (IArtifactRepositoryManager) ServiceHelper.getService(Activator.getContext(), IArtifactRepositoryManager.class.getName());
		// service not available... create one and hang onto it
		if (result == null) {
			cachedManager = new ArtifactRepositoryManager();
			result = cachedManager;
		}
		return result;
	}

	private void setupRepositories() throws ProvisionException {
		if (destinationLocation == null || sourceLocation == null)
			throw new IllegalStateException(Messages.exception_needSourceDestination);

		//Check if repositories are already loaded
		sourceLoaded = getManager().contains(sourceLocation);
		destinationLoaded = getManager().contains(destinationLocation);

		//must execute before initializeDestination is called
		source = getManager().loadRepository(sourceLocation, null);
		destination = initializeDestination();
	}

	private IArtifactRepository initializeDestination() throws ProvisionException {
		try {
			IArtifactRepository repository = getManager().loadRepository(destinationLocation, null);
			if (!repository.isModifiable())
				throw new IllegalArgumentException(NLS.bind(Messages.exception_destinationNotModifiable, destinationLocation));
			if (!append)
				repository.removeAll();
			return repository;
		} catch (ProvisionException e) {
			//fall through and create a new repository below
		}
		//This code assumes source has been successfully loaded before this point
		//No existing repository; create a new repository at destinationLocation but with source's attributes.
		// TODO for now create a Simple repo by default.
		return getManager().createRepository(destinationLocation, source.getName(), IArtifactRepositoryManager.TYPE_SIMPLE_REPOSITORY, source.getProperties());
	}

	/* (non-Javadoc)
	 * @see org.eclipse.equinox.app.IApplication#stop()
	 */
	public void stop() {
		// nothing to do
	}

	public void initializeFromArguments(String[] args) throws Exception {
		if (args == null)
			return;
		for (int i = 0; i < args.length; i++) {
			// check for args without parameters (i.e., a flag arg)
			if (args[i].equalsIgnoreCase("-raw")) //$NON-NLS-1$
				raw = true;
			if (args[i].equalsIgnoreCase("-append")) //$NON-NLS-1$
				append = true;
			if (args[i].equalsIgnoreCase("-ignoreErrors")) //$NON-NLS-1$
				failOnError = false;
			if (args[i].equalsIgnoreCase("-verbose")) //$NON-NLS-1$
				verbose = true;
			if (args[i].equalsIgnoreCase("-compare")) //$NON-NLS-1$
				compare = true;

			// check for args with parameters. If we are at the last argument or 
			// if the next one has a '-' as the first character, then we can't have 
			// an arg with a param so continue.
			if (i == args.length - 1 || args[i + 1].startsWith("-")) //$NON-NLS-1$
				continue;
			String arg = args[++i];

			if (args[i - 1].equalsIgnoreCase("-comparator")) //$NON-NLS-1$
				comparatorID = arg;

			try {
				if (args[i - 1].equalsIgnoreCase("-source")) //$NON-NLS-1$
					sourceLocation = URIUtil.fromString(arg);
				if (args[i - 1].equalsIgnoreCase("-destination")) //$NON-NLS-1$
					destinationLocation = URIUtil.fromString(arg);
			} catch (URISyntaxException e) {
				throw new IllegalArgumentException(NLS.bind(Messages.exception_malformedRepoURI, arg));
			}
		}
	}
}
