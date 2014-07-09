/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd;

/** TODO: Brief description of the purpose of this type and its relation to other types. */
public class NopTracingPolicyImpl implements TracingPolicy {


    public NopTracingPolicyImpl() {
    }

    @Override
    public TracingPolicyContainer compact(TracingPolicyContainer container) {
        return container;
    }

    @Override
    public void extract() {

    }

}
