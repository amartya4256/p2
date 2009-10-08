/*******************************************************************************
 * Copyright (c) 2007, 2008 compeople AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * 	compeople AG (Stefan Liebig) - initial API and implementation
 * 	IBM Corporation - adaptation to JAR deltas and on-going development
*******************************************************************************/
package org.eclipse.equinox.internal.p2.artifact.optimizers.jardelta;

import org.eclipse.equinox.internal.provisional.p2.metadata.Version;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.internal.p2.metadata.ArtifactKey;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.*;
import org.eclipse.equinox.internal.provisional.p2.artifact.repository.processing.*;
import org.eclipse.equinox.internal.provisional.p2.core.ProvisionException;
import org.eclipse.equinox.internal.provisional.p2.metadata.IArtifactKey;

public class Optimizer {

	private IArtifactRepository repository;
	private int width;
	private int depth;

	private static final String JAR_DELTA_FORMAT = "jarDelta"; //$NON-NLS-1$
	private static final String JAR_DELTA_PATCH_STEP = "org.eclipse.equinox.p2.processing.JarDeltaPatchStep"; //$NON-NLS-1$

	private static final Comparator ARTIFACT_DESCRIPTOR_VERSION_COMPARATOR = new ArtifactDescriptorVersionComparator();
	private static final Comparator ARTIFACT_KEY_VERSION_COMPARATOR = new ArtifactKeyVersionComparator();

	/**
	 * This optimizer performs delta generation based on (currently) jbdiff. 
	 * The optimization can be controlled with the �width� and the �depth� parameter.
	 * �width� defines for how many �related� artifact keys a delta should be generated,
	 * starting from the most up-to-date.
	 * �depth� defines to how many predecessor a delta should be generated.
	 * 
	 * With AK(c-v) : AK - artifact key, c - artifact id, v - artifact version
	 * the �repository content� can be viewed a two dimensional array, where the
	 * artifact keys for the same component are in order of their version: 
	 * <pre><code>
	 *     w=1       w=2
	 *      |        |
	 *      | +------.------------+ d=2
	 *      | | +----.---+ d=1    |
	 *      | | |    |   |        v
	 * [    v | |    v   v        v
	 * [ AK(x,2.0) AK(x,1.5) AK(x,1.1) ]
	 * [ AK(y,2.0) AK(y,1.9) ]
	 * [ AK(z,2.0) AK(z,1.5) AK(z,1.3) AK(z,1.0) ]
	 * ]
	 * </code></pre>  
	 * E.g: with a �width� of one and a �depth� of two the optimizer would
	 * create two deltas for component �x� from 1.5 to 2.0 and from 1.1 to 2.0.    
	 * 
	 * @param repository
	 * @param width
	 * @param depth
	 */
	public Optimizer(IArtifactRepository repository, int width, int depth) {
		this.repository = repository;
		this.width = width;
		this.depth = depth;
	}

	public void run() {
		System.out.println("Starting delta (jardelta) optimizations (width=" + width + ", depth=" + depth + ")");
		IArtifactKey[][] keys = getSortedRelatedArtifactKeys(repository.getArtifactKeys());
		for (int i = 0; i < keys.length; i++) {
			if (keys[i].length < 2)
				// Nothing to diff here!
				continue;
			int minWidth = Math.min(width, keys[i].length);
			for (int j = 0; j < minWidth; j++) {
				IArtifactKey key = keys[i][j];
				boolean isArchive = key.getClassifier().equals("plugin"); //$NON-NLS-1$
				optimize(keys[i], key);
			}
		}
		System.out.println("Done.");

	}

	private void optimize(IArtifactKey[] keys, IArtifactKey key) {
		IArtifactDescriptor[] descriptors = repository.getArtifactDescriptors(key);
		IArtifactDescriptor canonical = null;
		for (int k = 0; k < descriptors.length; k++) {
			IArtifactDescriptor descriptor = descriptors[k];
			boolean optimized = false;
			if (isCanonical(descriptor))
				canonical = descriptor;
			else
				optimized |= isOptimized(descriptor);
			if (!optimized)
				optimize(canonical, keys);
		}
	}

	private IArtifactKey getVersionlessKey(IArtifactKey key) {
		return new ArtifactKey(key.getClassifier(), key.getId(), Version.emptyVersion);
	}

	/**
	 * This method retrieves a list of list of IArtifactKeys. The artifact keys in the
	 * list of artifact keys are all �strongly related� to each other such that are  
	 * equal but not considering the versions. This list is sorted such that the 
	 * newer versions are first in the list.<p>
	 * With AK(c-v) : AK - artifact key, c - artifact id, v - artifact version
	 * the result is than, e.g.
	 * <pre><code>
	 * [
	 * [ AK(x,2.0) AK(x,1.5) AK(x,1.1) ]
	 * [ AK(y,2.0) AK(y,1.9) ]
	 * [ AK(z,2.0) AK(z,1.5) AK(z,1.3) AK(z,1.0) ]
	 * ]
	 * </code></pre>  
	 * @param artifactKeys
	 * @return the sorted artifact keys
	 */
	private IArtifactKey[][] getSortedRelatedArtifactKeys(IArtifactKey[] artifactKeys) {
		Map map = new HashMap();
		for (int i = 0; i < artifactKeys.length; i++) {
			IArtifactKey freeKey = getVersionlessKey(artifactKeys[i]);
			List values = (List) map.get(freeKey);
			if (values == null) {
				values = new ArrayList();
				map.put(freeKey, values);
			}
			values.add(artifactKeys[i]);
		}
		IArtifactKey[][] lists = new IArtifactKey[map.size()][];
		int i = 0;
		for (Iterator iterator = map.values().iterator(); iterator.hasNext();) {
			List artifactKeyList = (List) iterator.next();
			IArtifactKey[] relatedArtifactKeys = (IArtifactKey[]) artifactKeyList.toArray(new IArtifactKey[artifactKeyList.size()]);
			Arrays.sort(relatedArtifactKeys, ARTIFACT_KEY_VERSION_COMPARATOR);
			lists[i++] = relatedArtifactKeys;
		}
		int candidates = 0;
		for (int ii = 0; ii < lists.length; ii++) {
			for (int jj = 0; jj < lists[ii].length; jj++) {
				System.out.println(lists[ii][jj] + ", ");
			}
			System.out.println("");
			if (lists[ii].length > 1)
				candidates++;
		}
		System.out.println("Candidates found: " + candidates);
		return lists;
	}

	private void optimize(IArtifactDescriptor canonical, IArtifactKey[] relatedArtifactKeys) {
		System.out.println("Optimizing " + canonical);

		IArtifactDescriptor[] descriptors = getSortedCompletePredecessors(canonical.getArtifactKey(), relatedArtifactKeys);

		int minDepth = Math.min(depth, descriptors.length);
		for (int i = 0; i < minDepth; i++) {
			System.out.println("\t with jar delta against " + descriptors[i].getArtifactKey());
			String predecessorData = descriptors[i].getArtifactKey().toExternalForm();
			ArtifactDescriptor newDescriptor = new ArtifactDescriptor(canonical);
			ProcessingStepDescriptor patchStep = new ProcessingStepDescriptor(JAR_DELTA_PATCH_STEP, predecessorData, true);
			ProcessingStepDescriptor[] steps = new ProcessingStepDescriptor[] {patchStep};
			newDescriptor.setProcessingSteps(steps);
			newDescriptor.setProperty(IArtifactDescriptor.FORMAT, JAR_DELTA_FORMAT);
			OutputStream repositoryStream = null;
			try {
				repositoryStream = repository.getOutputStream(newDescriptor);

				// Add in all the processing steps needed to optimize (e.g., pack200, ...)
				ProcessingStep optimizerStep = new JarDeltaOptimizerStep(repository);
				optimizerStep.initialize(patchStep, newDescriptor);
				ProcessingStepHandler handler = new ProcessingStepHandler();
				OutputStream destination = handler.link(new ProcessingStep[] {optimizerStep}, repositoryStream, null);

				// Do the actual work by asking the repo to get the artifact and put it in the destination.
				IStatus status = repository.getArtifact(canonical, destination, new NullProgressMonitor());
				if (!status.isOK()) {
					System.out.println("Getting the artifact is not ok."); //$NON-NLS-1$
					System.out.println(status);
				}
			} catch (ProvisionException e) {
				System.out.println("Skipping optimization of: " + descriptors[i].getArtifactKey()); //$NON-NLS-1$
				System.out.println(e.getMessage());
				e.printStackTrace();
			} finally {
				if (repositoryStream != null)
					try {
						repositoryStream.close();
						IStatus status = ProcessingStepHandler.checkStatus(repositoryStream);
						if (!status.isOK()) {
							System.out.println("Skipping optimization of: " + descriptors[i].getArtifactKey()); //$NON-NLS-1$
							System.out.println(status.toString());
						}
					} catch (IOException e) {
						System.out.println("Skipping optimization of: " + descriptors[i].getArtifactKey()); //$NON-NLS-1$
						System.out.println(e.getMessage());
						e.printStackTrace();
					}
			}
		}
	}

	private IArtifactDescriptor[] getSortedCompletePredecessors(IArtifactKey artifactKey, IArtifactKey[] relatedArtifactKeys) {
		// get all artifact keys
		List completeDescriptors = new ArrayList(relatedArtifactKeys.length);
		for (int i = 0; i < relatedArtifactKeys.length; i++) {
			// if we find �our self� skip
			if (relatedArtifactKeys[i].equals(artifactKey))
				continue;
			// look for a complete artifact descriptor of the current key  
			IArtifactDescriptor[] descriptors = repository.getArtifactDescriptors(relatedArtifactKeys[i]);
			for (int j = 0; j < descriptors.length; j++) {
				if (isCanonical(descriptors[j])) {
					completeDescriptors.add(descriptors[j]);
					break;
				}
			}
		}

		IArtifactDescriptor[] completeSortedDescriptors = (IArtifactDescriptor[]) completeDescriptors.toArray(new IArtifactDescriptor[completeDescriptors.size()]);
		// Sort, so to allow a depth lookup!
		Arrays.sort(completeSortedDescriptors, ARTIFACT_DESCRIPTOR_VERSION_COMPARATOR);
		return completeSortedDescriptors;
	}

	private boolean isOptimized(IArtifactDescriptor descriptor) {
		if (descriptor.getProcessingSteps().length != 1)
			return false;
		return JAR_DELTA_FORMAT.equals(descriptor.getProperty(IArtifactDescriptor.FORMAT));
	}

	private boolean isCanonical(IArtifactDescriptor descriptor) {
		// TODO length != 0 is not necessarily an indicator for not being canonical!   
		return descriptor.getProcessingSteps().length == 0;
	}

	static final class ArtifactDescriptorVersionComparator implements Comparator {
		public int compare(Object artifactDescriptor0, Object artifactDescriptor1) {
			return -1 * ((IArtifactDescriptor) artifactDescriptor0).getArtifactKey().getVersion().compareTo(((IArtifactDescriptor) artifactDescriptor1).getArtifactKey().getVersion());
		}
	}

	static final class ArtifactKeyVersionComparator implements Comparator {
		public int compare(Object artifactKey0, Object artifactKey1) {
			return -1 * ((IArtifactKey) artifactKey0).getVersion().compareTo(((IArtifactKey) artifactKey1).getVersion());
		}
	}
}
