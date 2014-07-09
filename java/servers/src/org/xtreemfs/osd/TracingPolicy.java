/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd;

public interface TracingPolicy {

    public TracingPolicyContainer compact(TracingPolicyContainer container);

    public void extract();

}
