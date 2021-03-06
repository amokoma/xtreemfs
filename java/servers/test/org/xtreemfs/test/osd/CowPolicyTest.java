/*
 * Copyright (c) 2009-2011 by Bjoern Kolbeck, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.test.osd;

import junit.framework.TestCase;
import org.xtreemfs.osd.storage.CowPolicy;
import org.xtreemfs.osd.storage.CowPolicy.cowMode;

/**
 *
 * @author bjko
 */
public class CowPolicyTest extends TestCase {
    
    public CowPolicyTest(String testName) {
        super(testName);
    }            

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testNoCow() throws Exception {
        CowPolicy p = new CowPolicy(cowMode.NO_COW);
        assertFalse(p.isCOW(0));
        assertFalse(p.isCOW(10));
        assertFalse(p.isCOW(55));
        p.objectChanged(0);
        assertFalse(p.isCOW(0));
    }
    
    public void testAlwaysCow() throws Exception {
        CowPolicy p = new CowPolicy(cowMode.ALWAYS_COW);
        assertTrue(p.isCOW(0));
        assertTrue(p.isCOW(10));
        assertTrue(p.isCOW(55));
        p.objectChanged(0);
        assertTrue(p.isCOW(0));
    }
    
    public void testCowOnce() throws Exception {
        CowPolicy p = new CowPolicy(cowMode.COW_ONCE);
        p.initCowFlagsIfRequired(115);
        assertTrue(p.isCOW(0));
        assertTrue(p.isCOW(10));
        assertTrue(p.isCOW(55));
        p.objectChanged(0);
        //check entrie 8 bits since COW uses byte array for COW_ONCE bitmap
        assertFalse(p.isCOW(0));
        assertTrue(p.isCOW(1));
        assertTrue(p.isCOW(2));
        assertTrue(p.isCOW(3));
        assertTrue(p.isCOW(4));
        assertTrue(p.isCOW(5));
        assertTrue(p.isCOW(6));
        assertTrue(p.isCOW(7));
        
        p.objectChanged(5);
        //check entrie 8 bits since COW uses byte array for COW_ONCE bitmap
        assertFalse(p.isCOW(0));
        assertTrue(p.isCOW(1));
        assertTrue(p.isCOW(2));
        assertTrue(p.isCOW(3));
        assertTrue(p.isCOW(4));
        assertFalse(p.isCOW(5));
        assertTrue(p.isCOW(6));
        assertTrue(p.isCOW(7));
        
        p.objectChanged(9);
        //check entrie 8 bits since COW uses byte array for COW_ONCE bitmap
        assertTrue(p.isCOW(8));
        assertFalse(p.isCOW(9));
        assertTrue(p.isCOW(10));
        assertTrue(p.isCOW(11));
        assertTrue(p.isCOW(12));
        assertTrue(p.isCOW(13));
        assertTrue(p.isCOW(14));
        assertTrue(p.isCOW(15));
        
        //any new object (here > 114) must return false
        assertFalse(p.isCOW(22556));
        p.objectChanged(22556);
        assertFalse(p.isCOW(22556));
    }

}
