/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.yangtools.yang.binding.util;

import org.junit.Test;
import org.opendaylight.yangtools.yang.binding.TreeNode;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BindingReflectionsTest {

    @Test
    public void testBindingWithDummyObject() {
        assertEquals("Package name should be equal to string", "org.opendaylight.yang.gen.v1.test.rev990939",
                BindingReflections.getModelRootPackageName("org.opendaylight.yang.gen.v1.test.rev990939"));
        assertEquals("ModuleInfoClassName should be equal to string", "test.$YangModuleInfoImpl",
                BindingReflections.getModuleInfoClassName("test"));
        assertEquals("Module info should be empty Set", Collections.EMPTY_SET,
                BindingReflections.loadModuleInfos());
        assertFalse("Should not be RpcType", BindingReflections.isRpcType(TreeNode.class));
        assertFalse("Should not be AugmentationChild", BindingReflections.isAugmentationChild(TreeNode.class));
        assertTrue("Should be BindingClass", BindingReflections.isBindingClass(TreeNode.class));
        assertFalse("Should not be Notification", BindingReflections.isNotification(TreeNode.class));
    }
}