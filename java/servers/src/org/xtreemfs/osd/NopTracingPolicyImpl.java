/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd;

import java.util.List;

import org.xtreemfs.osd.operations.OSDOperation;
import org.xtreemfs.osd.stages.Stage.StageRequest;

/** TODO: Brief description of the purpose of this type and its relation to other types. */
public class NopTracingPolicyImpl implements TracingPolicy {


    public NopTracingPolicyImpl() {
    }

    @Override
    public List<Object> compact(List<Object> container) {
        return container;
    }

    @Override
    public Object extract(StageRequest request, OSDOperation operation) {

        return new Object();
    }

    @Override
    public byte[] toByteArray() {
        return new byte[1];
    }

}
