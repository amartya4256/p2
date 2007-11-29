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
package org.eclipse.equinox.p2.ui.dialogs;

import org.eclipse.core.runtime.*;
import org.eclipse.equinox.internal.p2.ui.ProvUIMessages;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.director.ProvisioningPlan;
import org.eclipse.equinox.p2.engine.Profile;
import org.eclipse.equinox.p2.engine.phases.Sizing;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.ui.ProvUI;
import org.eclipse.equinox.p2.ui.model.IUElement;
import org.eclipse.equinox.p2.ui.operations.*;
import org.eclipse.swt.widgets.Shell;

public class InstallDialog extends UpdateInstallDialog {

	public InstallDialog(Shell parentShell, IInstallableUnit[] ius, Profile profile) {
		super(parentShell, ius, profile, ProvUIMessages.InstallIUOperationLabel, ProvUIMessages.InstallDialog_InstallSelectionMessage);
	}

	protected String getOkButtonString() {
		return ProvUIMessages.InstallIUOperationLabelWithMnemonic;
	}

	protected long getSize(IInstallableUnit iu, IProgressMonitor monitor) {
		long size;
		SubMonitor sub = SubMonitor.convert(monitor);
		sub.setWorkRemaining(100);
		try {
			ProvisioningPlan plan = ProvisioningUtil.getInstallPlan(new IInstallableUnit[] {iu}, profile, sub.newChild(50));
			Sizing info = ProvisioningUtil.getSizeInfo(plan, profile, sub.newChild(50));
			if (info == null)
				size = IUElement.SIZE_UNKNOWN;
			else
				size = info.getDiskSize();
		} catch (ProvisionException e) {
			size = IUElement.SIZE_UNKNOWN;
		}
		return size;
	}

	protected String getOperationLabel() {
		return ProvUIMessages.InstallIUOperationLabel;
	}

	protected ProfileModificationOperation createProfileModificationOperation(Object[] selectedElements, IProgressMonitor monitor) {
		try {
			IInstallableUnit[] selected = elementsToIUs(selectedElements);
			ProvisioningPlan plan = ProvisioningUtil.getInstallPlan(selected, profile, monitor);
			IStatus status = plan.getStatus();
			if (status.isOK())
				return new InstallOperation(getOperationLabel(), profile.getProfileId(), plan, selected);
			ProvUI.reportStatus(status);
		} catch (ProvisionException e) {
			ProvUI.handleException(e, null);
		}
		return null;
	}
}