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

public interface TracingPolicy {

    public List<Object> compact(List<Object> container); // TODO change Object

    // TODO change return value
    public Object extract(StageRequest request, OSDOperation operation);

    public byte[] toByteArray();

}
