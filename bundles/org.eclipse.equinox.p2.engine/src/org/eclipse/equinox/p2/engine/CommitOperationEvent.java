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
package org.eclipse.equinox.p2.engine;

public class CommitOperationEvent extends TransactionEvent {
	private static final long serialVersionUID = -523967775426133720L;

	public CommitOperationEvent(IProfile profile, PhaseSet phaseSet, InstallableUnitOperand[] deltas, Engine engine) {
		super(profile, phaseSet, deltas, engine);
	}

}
