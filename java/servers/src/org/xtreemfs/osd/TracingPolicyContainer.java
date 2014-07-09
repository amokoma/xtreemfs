/*
 * Copyright (c) 2014 by Martin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */
package org.xtreemfs.osd;

public class TracingPolicyContainer {

    public String traceLogString;

    public TracingPolicyContainer(String traceLogString) {
        this.traceLogString = traceLogString;
    }

    public String getTraceLogString() {
        return this.traceLogString;
    }

}
