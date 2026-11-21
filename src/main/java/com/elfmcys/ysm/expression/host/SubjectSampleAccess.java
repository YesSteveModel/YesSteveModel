package com.elfmcys.ysm.expression.host;

/** Entity storage only; reads never acquire a capability or an animation execution window. */
public interface SubjectSampleAccess {
    SubjectSample ysm$subjectSample();

    void ysm$subjectSample(SubjectSample sample);
}
